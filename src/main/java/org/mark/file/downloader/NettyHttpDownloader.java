package org.mark.file.downloader;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.GenericFutureListener;

import org.mark.llamacpp.crawler.ProxyConfig;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.struct.ProxyConfigData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty-based HTTP/HTTPS downloader with proxy support, multi-threaded
 * segmented download, and resume capability. Replaces
 * {@link SimpleHttpDownloader} for environments requiring HTTP CONNECT proxy
 * (HTTPS through HTTP proxy).
 */
public class NettyHttpDownloader implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(NettyHttpDownloader.class);

	private static final int DEFAULT_TIMEOUT_MS = 30_000;
	private static final int DEFAULT_MAX_REDIRECTS = 10;
	private static final long DEFAULT_PROGRESS_EMIT_INTERVAL_MS = 1_000L;
	private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JavaDownloader/1.0";
	private static final int MAX_AGGREGATE_LENGTH = 1024 * 512;

	private static final SslContext INSECURE_SSL_CONTEXT;
	static {
		try {
			INSECURE_SSL_CONTEXT = SslContextBuilder.forClient()
					.trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		} catch (Exception e) {
			throw new RuntimeException("Failed to create SSL context", e);
		}
	}

	private final int threadCount;
	private final int timeoutMs;
	private final int maxRedirects;
	private final String userAgent;
	private volatile boolean stopRequested;
	private volatile ProgressListener progressListener;
	private ProxyConfig proxyConfig;

	private final NioEventLoopGroup eventLoopGroup;

	public NettyHttpDownloader(int threadCount) {
		this(threadCount, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_REDIRECTS, DEFAULT_USER_AGENT);
	}

	public NettyHttpDownloader(int threadCount, int timeoutMs, int maxRedirects, String userAgent) {
		if (threadCount < 1) {
			throw new IllegalArgumentException("threadCount must be >= 1");
		}
		if (timeoutMs < 1) {
			throw new IllegalArgumentException("timeoutMs must be >= 1");
		}
		if (maxRedirects < 0) {
			throw new IllegalArgumentException("maxRedirects must be >= 0");
		}
		this.threadCount = threadCount;
		this.timeoutMs = timeoutMs;
		this.maxRedirects = maxRedirects;
		this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
		this.eventLoopGroup = new NioEventLoopGroup(threadCount);
	}

	public void setProxy(ProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
	}

	private ProxyConfig resolveProxy() {
		if (this.proxyConfig != null) {
			logger.debug("Using explicit proxy {}:{}", this.proxyConfig.getHost(), this.proxyConfig.getPort());
			return this.proxyConfig;
		}
		try {
			ProxyConfigData globalCfg = LlamaServer.getProxyConfig();
			if (globalCfg != null && globalCfg.isEnabled() && globalCfg.getHost() != null
					&& !globalCfg.getHost().trim().isEmpty()) {
				int port = globalCfg.getPort();
				if (port > 0) {
					String user = globalCfg.getUsername();
					if (user != null && !user.isEmpty()) {
						logger.debug("Using global proxy {}:{} (auth)", globalCfg.getHost().trim(), port);
						return ProxyConfig.http(globalCfg.getHost().trim(), port, user,
								globalCfg.getPassword() != null ? globalCfg.getPassword() : "");
					}
					logger.debug("Using global proxy {}:{} (no auth)", globalCfg.getHost().trim(), port);
					return ProxyConfig.http(globalCfg.getHost().trim(), port);
				}
			}
		} catch (Exception e) {
			logger.warn("Failed to resolve proxy config", e);
		}
		return null;
	}

	@Override
	public void close() {
		this.eventLoopGroup.shutdownGracefully();
	}

	// -----------------------------------------------------------------------
	// Public API
	// -----------------------------------------------------------------------

	public DownloadResult download(String sourceUrl, Path targetFile) throws IOException {
		Objects.requireNonNull(sourceUrl, "sourceUrl");
		Objects.requireNonNull(targetFile, "targetFile");
		this.stopRequested = false;
		logger.info("Starting download {} -> {}", sourceUrl, targetFile);

		ProbeResult probe = probe(sourceUrl);
		if (probe.contentLength <= 0) {
			throw new IOException("无法获取文件大小: " + sourceUrl);
		}

		int actualThreads = Math.min(this.threadCount, (int) Math.min(Integer.MAX_VALUE, probe.contentLength));
		if (!probe.rangeSupported && actualThreads > 1) {
			throw new IOException("目标服务器不支持Range分段下载，无法执行多线程下载");
		}

		Path parent = targetFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		List<Range> ranges = splitRanges(probe.contentLength, Math.max(1, actualThreads));
		List<RangeState> rangeStates = createRangeStates(ranges);
		Path tempFile = buildTempFile(targetFile);
		Path metadataFile = buildMetadataFile(targetFile);
		ResumeMetadata metadata = loadMetadata(metadataFile);
		boolean resumed = applyResumeState(metadata, probe, rangeStates, tempFile);
		if (!resumed) {
			initFreshTempFile(tempFile, metadataFile, probe.contentLength);
		}
		boolean restartedAsSingle = false;
		while (true) {
			MetadataStore metadataStore = new MetadataStore(metadataFile, probe.finalUrl, probe.contentLength, probe.etag,
					probe.lastModified, rangeStates);
			metadataStore.persistQuietly();
			DownloadTracker tracker = new DownloadTracker(probe.contentLength, rangeStates, metadataStore::persistQuietly);
			fireProgress(tracker.snapshot());
			try {
				if (rangeStates.size() == 1) {
					downloadRange(probe.finalUrl, tempFile, rangeStates.get(0), tracker);
				} else {
					ExecutorService executor = Executors.newFixedThreadPool(rangeStates.size());
					try {
						List<Future<Void>> futures = new ArrayList<>();
						for (RangeState rangeState : rangeStates) {
							futures.add(executor.submit(new RangeTask(probe.finalUrl, tempFile, rangeState, tracker)));
						}
						for (Future<Void> future : futures) {
							future.get();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						ensureNotStopped();
						throw new IOException("下载被中断", e);
					} catch (ExecutionException e) {
						ensureNotStopped();
						Throwable cause = e.getCause();
						if (cause instanceof IOException ioException) {
							throw ioException;
						}
						throw new IOException("分段下载失败: " + (cause == null ? e.getMessage() : cause.getMessage()),
								cause == null ? e : cause);
					} finally {
						executor.shutdownNow();
					}
				}

				ensureNotStopped();
				tracker.forceEmit();
				metadataStore.persist();
				Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
				Files.deleteIfExists(metadataFile);
				return new DownloadResult(probe.finalUrl, targetFile, probe.contentLength, rangeStates.size());
			} catch (RetryAsSingleDownloadException e) {
				metadataStore.persistQuietly();
				if (restartedAsSingle) {
					throw e;
				}
				restartedAsSingle = true;
				ranges = splitRanges(probe.contentLength, 1);
				rangeStates = createRangeStates(ranges);
				initFreshTempFile(tempFile, metadataFile, probe.contentLength);
			} catch (IOException e) {
				metadataStore.persistQuietly();
				throw e;
			}
		}
	}

	public ProbeResult probe(String sourceUrl) throws IOException {
		logger.info("Probing {}", sourceUrl);
		String finalUrl = resolveRedirects(sourceUrl);
		ProbeResult headProbe = probeByHead(finalUrl);
		if (headProbe.contentLength > 0) {
			return headProbe;
		}
		ProbeResult rangeProbe = probeByRange(finalUrl);
		if (rangeProbe.contentLength > 0) {
			return rangeProbe;
		}
		throw new IOException("探测文件大小失败: " + sourceUrl);
	}

	public String resolveRedirects(String sourceUrl) throws IOException {
		String current = sourceUrl;
		for (int i = 0; i <= this.maxRedirects; i++) {
			ProbeResponse pr = executeProbeRequest(current, "HEAD", Collections.emptyMap());
			int code = pr.statusCode();
			if (!isRedirectCode(code)) {
				return current;
			}
			String location = pr.header("Location");
			logger.debug("Redirect {} -> {}", current, location);
			current = resolveLocation(current, location);
		}
		throw new IOException("重定向次数超过限制: " + sourceUrl);
	}

	public void requestStop() {
		this.stopRequested = true;
	}

	public boolean isStopRequested() {
		return this.stopRequested;
	}

	public void setProgressListener(ProgressListener progressListener) {
		this.progressListener = progressListener;
	}

	// -----------------------------------------------------------------------
	// Probe internals
	// -----------------------------------------------------------------------

	private ProbeResult probeByHead(String finalUrl) throws IOException {
		ProbeResponse pr = executeProbeRequest(finalUrl, "HEAD", Collections.emptyMap());
		int code = pr.statusCode();
		if (isRedirectCode(code)) {
			String redirected = resolveLocation(finalUrl, pr.header("Location"));
			return probeByHead(resolveRedirects(redirected));
		}
		if (code < 200 || code >= 400) {
			return new ProbeResult(finalUrl, -1, false, null, null);
		}
		long contentLength = parseContentLength(pr.header("Content-Length"));
		boolean rangeSupported = isRangeSupported(pr.header("Accept-Ranges"));
		String etag = normalizeHeaderValue(pr.header("ETag"));
		String lastModified = normalizeHeaderValue(pr.header("Last-Modified"));
		return new ProbeResult(finalUrl, contentLength, rangeSupported, etag, lastModified);
	}

	private ProbeResult probeByRange(String finalUrl) throws IOException {
		Map<String, String> extraHeaders = Map.of("Range", "bytes=0-0");
		ProbeResponse pr = executeProbeRequest(finalUrl, "GET", extraHeaders);
		int code = pr.statusCode();
		if (isRedirectCode(code)) {
			String redirected = resolveLocation(finalUrl, pr.header("Location"));
			return probeByRange(resolveRedirects(redirected));
		}
		if (code == 206) {
			long size = parseContentRangeSize(pr.header("Content-Range"));
			String etag = normalizeHeaderValue(pr.header("ETag"));
			String lastModified = normalizeHeaderValue(pr.header("Last-Modified"));
			return new ProbeResult(finalUrl, size, size > 0, etag, lastModified);
		}
		if (code == 200) {
			long size = parseContentLength(pr.header("Content-Length"));
			String etag = normalizeHeaderValue(pr.header("ETag"));
			String lastModified = normalizeHeaderValue(pr.header("Last-Modified"));
			return new ProbeResult(finalUrl, size, false, etag, lastModified);
		}
		return new ProbeResult(finalUrl, -1, false, null, null);
	}

	// -----------------------------------------------------------------------
	// Single probe request (Netty)
	// -----------------------------------------------------------------------

	private ProbeResponse executeProbeRequest(String url, String method, Map<String, String> extraHeaders)
			throws IOException {
		ProxyConfig effectiveProxy = resolveProxy();
		URI uri = URI.create(url);
		String scheme = uri.getScheme();
		boolean isHttps = "https".equalsIgnoreCase(scheme);
		if (!isHttps && !"http".equalsIgnoreCase(scheme)) {
			throw new IOException("Unsupported scheme: " + scheme);
		}
		String host = requireNonBlank(uri.getHost(), "URL host");
		int port = uri.getPort() == -1 ? (isHttps ? 443 : 80) : uri.getPort();

		String connectHost = effectiveProxy != null ? effectiveProxy.getHost() : host;
		int connectPort = effectiveProxy != null ? effectiveProxy.getPort() : port;

		String requestTarget = buildRequestTarget(uri, effectiveProxy != null && !isHttps);
		String hostHeader = buildHostHeader(host, port, isHttps);

		CompletableFuture<ProbeResponse> future = new CompletableFuture<>();

		Bootstrap bootstrap = new Bootstrap();
		bootstrap.group(this.eventLoopGroup).channel(NioSocketChannel.class)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ChannelPipeline p = ch.pipeline();
						p.addLast(new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS));

						if (effectiveProxy != null && isHttps) {
							p.addLast(new HttpClientCodec());
							p.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
							p.addLast(new ConnectTunnelHandler(host, port, effectiveProxy, future,
									new ChannelHandler[]{new HttpClientCodec(),
											new ProbeHandler(future, hostHeader, requestTarget, method,
													extraHeaders, effectiveProxy, true)}));
						} else if (isHttps) {
							p.addLast(INSECURE_SSL_CONTEXT.newHandler(ch.alloc(), host, port));
							p.addLast(new HttpClientCodec());
							p.addLast(new ProbeHandler(future, hostHeader, requestTarget, method,
									extraHeaders, effectiveProxy, true));
						} else {
							p.addLast(new HttpClientCodec());
							p.addLast(new ProbeHandler(future, hostHeader, requestTarget, method,
									extraHeaders, effectiveProxy, false));
						}
					}
				}).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, this.timeoutMs);

		ChannelFuture connectFuture = bootstrap.connect(connectHost, connectPort);
		connectFuture.addListener((GenericFutureListener<ChannelFuture>) f -> {
			if (!f.isSuccess()) {
				future.completeExceptionally(new IOException("Failed to connect", f.cause()));
			}
		});

		try {
			return future.get(this.timeoutMs * 2L, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Probe interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException ioException) {
				logger.warn("Probe request failed for {}: {}", url, ioException.toString());
				throw ioException;
			}
			String causeMsg = cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null";
			logger.warn("Probe request failed for {}: {}", url, causeMsg);
			throw new IOException("Probe failed for " + url + " [" + causeMsg + "]", cause);
		} catch (TimeoutException e) {
			throw new IOException("Probe timed out for " + url, e);
		}
	}

	// -----------------------------------------------------------------------
	// Download internals
	// -----------------------------------------------------------------------

	private void downloadRange(String finalUrl, Path tempFile, RangeState rangeState, DownloadTracker tracker)
			throws IOException {
		long current = rangeState.currentOffset();
		if (current > rangeState.end()) {
			return;
		}
		while (current <= rangeState.end()) {
			ensureNotStopped();
			try {
				executeStreamDownload(finalUrl, tempFile, current, rangeState.end(), rangeState, tracker);
				if (rangeState.currentOffset() > rangeState.end()) {
					rangeState.markCompleted();
					tracker.onPartCompleted();
				}
				return;
			} catch (RedirectSignalException rse) {
				String redirected = resolveLocation(finalUrl, rse.location);
				finalUrl = resolveRedirects(redirected);
				current = rangeState.currentOffset();
			}
		}
	}

	private void executeStreamDownload(String url, Path tempFile, long startOffset, long endOffset,
			RangeState rangeState, DownloadTracker tracker) throws IOException {
		ProxyConfig effectiveProxy = resolveProxy();
		URI uri = URI.create(url);
		String scheme = uri.getScheme();
		boolean isHttps = "https".equalsIgnoreCase(scheme);
		if (!isHttps && !"http".equalsIgnoreCase(scheme)) {
			throw new IOException("Unsupported scheme: " + scheme);
		}
		String host = requireNonBlank(uri.getHost(), "URL host");
		int port = uri.getPort() == -1 ? (isHttps ? 443 : 80) : uri.getPort();

		String connectHost = effectiveProxy != null ? effectiveProxy.getHost() : host;
		int connectPort = effectiveProxy != null ? effectiveProxy.getPort() : port;

		String requestTarget = buildRequestTarget(uri, effectiveProxy != null && !isHttps);
		String hostHeader = buildHostHeader(host, port, isHttps);

		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("User-Agent", this.userAgent);
		headers.put("Accept-Encoding", "identity");
		headers.put("Range", "bytes=" + startOffset + "-" + endOffset);

		CompletableFuture<Void> future = new CompletableFuture<>();

		DownloadHandler downloadHandler = new DownloadHandler(future, hostHeader, requestTarget, headers,
				effectiveProxy, tempFile, startOffset, endOffset, rangeState, tracker, isHttps);

		Bootstrap bootstrap = new Bootstrap();
		bootstrap.group(this.eventLoopGroup).channel(NioSocketChannel.class)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ChannelPipeline p = ch.pipeline();
						p.addLast(new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS));

						if (effectiveProxy != null && isHttps) {
							p.addLast(new HttpClientCodec());
							p.addLast(new HttpObjectAggregator(MAX_AGGREGATE_LENGTH));
							p.addLast(new ConnectTunnelHandler(host, port, effectiveProxy, future,
									new ChannelHandler[]{new HttpClientCodec(), downloadHandler}));
						} else if (isHttps) {
							p.addLast(INSECURE_SSL_CONTEXT.newHandler(ch.alloc(), host, port));
							p.addLast(new HttpClientCodec());
							p.addLast(downloadHandler);
						} else {
							p.addLast(new HttpClientCodec());
							p.addLast(downloadHandler);
						}
					}
				}).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, this.timeoutMs);

		ChannelFuture connectFuture = bootstrap.connect(connectHost, connectPort);
		connectFuture.addListener((GenericFutureListener<ChannelFuture>) f -> {
			if (!f.isSuccess()) {
				future.completeExceptionally(new IOException("Failed to connect", f.cause()));
			}
		});

		try {
			future.get(Math.max(this.timeoutMs, 600_000L), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RedirectSignalException) {
				throw (RedirectSignalException) cause;
			}
			if (cause instanceof RetryAsSingleDownloadException) {
				throw (RetryAsSingleDownloadException) cause;
			}
			if (cause instanceof IOException ioException) {
				logger.warn("Stream download failed for {} (range {}-{}): {}", url, startOffset, endOffset,
						ioException.toString());
				throw ioException;
			}
			String causeMsg = cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null";
			logger.warn("Stream download failed for {} (range {}-{}): {}", url, startOffset, endOffset, causeMsg);
			throw new IOException(
					"Download failed for " + url + " range " + startOffset + "-" + endOffset + " [" + causeMsg + "]",
					cause);
		} catch (TimeoutException e) {
			throw new IOException("Download timed out for " + url, e);
		}
	}

	// -----------------------------------------------------------------------
	// Netty Handlers
	// -----------------------------------------------------------------------

	/**
	 * Handles CONNECT tunnel for HTTPS via HTTP proxy. Once the tunnel is
	 * established (200), removes stage-1 handlers and installs stage-2 handlers
	 * (SSL + codec + final handler).
	 */
	private static class ConnectTunnelHandler extends ChannelInboundHandlerAdapter {
		private final String targetHost;
		private final int targetPort;
		private final ProxyConfig proxy;
		private final CompletableFuture<?> future;
		private final ChannelHandler[] afterTunnelHandlers;

		ConnectTunnelHandler(String targetHost, int targetPort, ProxyConfig proxy,
				CompletableFuture<?> future, ChannelHandler[] afterTunnelHandlers) {
			this.targetHost = targetHost;
			this.targetPort = targetPort;
			this.proxy = proxy;
			this.future = future;
			this.afterTunnelHandlers = afterTunnelHandlers;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			FullHttpRequest connectRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT,
					targetHost + ":" + targetPort);
			connectRequest.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);
			if (proxy != null && proxy.hasAuth()) {
				String credentials = proxy.getUsername() + ":" + proxy.getPassword();
				String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
				connectRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encoded);
			}
			ctx.writeAndFlush(connectRequest);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof FullHttpResponse response) {
				int status = response.status().code();
				response.release();
				if (status == 200) {
					ctx.pipeline().remove(this);
					ctx.pipeline().remove(HttpClientCodec.class);
					ctx.pipeline().remove(HttpObjectAggregator.class);
					try {
						ctx.pipeline().addFirst(INSECURE_SSL_CONTEXT.newHandler(ctx.alloc(), targetHost, targetPort));
						for (ChannelHandler handler : afterTunnelHandlers) {
							ctx.pipeline().addLast(handler);
						}
					} catch (Exception e) {
						fail(future, new IOException("Failed to establish SSL tunnel", e));
						ctx.close();
					}
				} else {
					fail(future, new IOException("CONNECT failed: " + status));
					ctx.close();
				}
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.warn("ConnectTunnelHandler exception for {}:{}: {}", targetHost, targetPort, cause.toString());
			fail(future, cause);
			ctx.close();
		}
	}

	/**
	 * Sends a probe request (HEAD or GET+Range) and completes the future with
	 * the response status and headers. Does NOT use HttpObjectAggregator —
	 * reads the {@link HttpResponse} for headers immediately and discards any
	 * body chunks, avoiding {@code TooLongFrameException} when the response
	 * Content-Length is large.
	 */
	private static class ProbeHandler extends SimpleChannelInboundHandler<HttpObject> {
		private final CompletableFuture<ProbeResponse> future;
		private final String hostHeader;
		private final String requestTarget;
		private final String method;
		private final Map<String, String> extraHeaders;
		private final ProxyConfig proxy;
		private final boolean waitForTlsHandshake;
		private final AtomicBoolean sent = new AtomicBoolean(false);
		private boolean responseProcessed;

		ProbeHandler(CompletableFuture<ProbeResponse> future, String hostHeader, String requestTarget,
				String method, Map<String, String> extraHeaders, ProxyConfig proxy,
				boolean waitForTlsHandshake) {
			this.future = future;
			this.hostHeader = hostHeader;
			this.requestTarget = requestTarget;
			this.method = method;
			this.extraHeaders = extraHeaders;
			this.proxy = proxy;
			this.waitForTlsHandshake = waitForTlsHandshake;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			if (!waitForTlsHandshake && sent.compareAndSet(false, true)) {
				sendRequest(ctx);
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof SslHandshakeCompletionEvent handshake) {
				if (handshake.isSuccess()) {
					if (sent.compareAndSet(false, true)) {
						sendRequest(ctx);
					}
				} else {
					future.completeExceptionally(new IOException("TLS handshake failed", handshake.cause()));
					ctx.close();
				}
				return;
			}
			super.userEventTriggered(ctx, evt);
		}

		private void sendRequest(ChannelHandlerContext ctx) {
			FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
					HttpMethod.valueOf(this.method), requestTarget);
			request.headers().set(HttpHeaderNames.HOST, hostHeader);
			request.headers().set(HttpHeaderNames.USER_AGENT, DEFAULT_USER_AGENT);
			request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "identity");
			for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
				request.headers().set(entry.getKey(), entry.getValue());
			}
			if (proxy != null && proxy.hasAuth()) {
				String credentials = proxy.getUsername() + ":" + proxy.getPassword();
				String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
				request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encoded);
			}
			ctx.writeAndFlush(request);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
			if (!responseProcessed && msg instanceof HttpResponse response) {
				responseProcessed = true;
				int statusCode = response.status().code();
				Map<String, String> headers = new LinkedHashMap<>();
				response.headers().forEach(entry -> headers.putIfAbsent(
						entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
				future.complete(new ProbeResponse(statusCode, headers));
			}
			if (msg instanceof LastHttpContent) {
				ctx.close();
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.warn("ProbeHandler exception on {} {}: {}", method, requestTarget, cause.toString());
			if (!future.isDone()) {
				future.completeExceptionally(cause);
			}
			ctx.close();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			if (!future.isDone()) {
				logger.warn("ProbeHandler channel closed before response for {} {}", method, requestTarget);
				future.completeExceptionally(new IOException("Connection closed unexpectedly"));
			}
		}
	}

	/**
	 * Streams an HTTP response body directly to a RandomAccessFile at the
	 * correct offset. Receives {@link HttpResponse} /
	 * {@link HttpContent} separately (no aggregator).
	 */
	private class DownloadHandler extends SimpleChannelInboundHandler<HttpObject> {
		private final CompletableFuture<Void> future;
		private final String hostHeader;
		private final String requestTarget;
		private final Map<String, String> headers;
		private final ProxyConfig proxy;
		private final Path tempFile;
		private final long startOffset;
		private final long endOffset;
		private final RangeState rangeState;
		private final DownloadTracker tracker;
		private final boolean waitForTlsHandshake;

		private RandomAccessFile raf;
		private long written;
		private boolean responseStarted;
		private final AtomicBoolean sent = new AtomicBoolean(false);

		DownloadHandler(CompletableFuture<Void> future, String hostHeader, String requestTarget,
				Map<String, String> headers, ProxyConfig proxy, Path tempFile,
				long startOffset, long endOffset, RangeState rangeState, DownloadTracker tracker,
				boolean waitForTlsHandshake) {
			this.future = future;
			this.hostHeader = hostHeader;
			this.requestTarget = requestTarget;
			this.headers = headers;
			this.proxy = proxy;
			this.tempFile = tempFile;
			this.startOffset = startOffset;
			this.endOffset = endOffset;
			this.rangeState = rangeState;
			this.tracker = tracker;
			this.waitForTlsHandshake = waitForTlsHandshake;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			if (!waitForTlsHandshake && sent.compareAndSet(false, true)) {
				sendRequest(ctx);
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof SslHandshakeCompletionEvent handshake) {
				if (handshake.isSuccess()) {
					if (sent.compareAndSet(false, true)) {
						sendRequest(ctx);
					}
				} else {
					future.completeExceptionally(new IOException("TLS handshake failed", handshake.cause()));
					ctx.close();
				}
				return;
			}
			super.userEventTriggered(ctx, evt);
		}

		private void sendRequest(ChannelHandlerContext ctx) {
			FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, requestTarget);
			request.headers().set(HttpHeaderNames.HOST, hostHeader);
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				request.headers().set(entry.getKey(), entry.getValue());
			}
			if (proxy != null && proxy.hasAuth() && proxy.getType() == java.net.Proxy.Type.HTTP) {
				String credentials = proxy.getUsername() + ":" + proxy.getPassword();
				String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
				request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encoded);
			}
			ctx.writeAndFlush(request);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
			if (stopRequested) {
				cleanup();
				future.completeExceptionally(new IOException("下载已暂停"));
				ctx.close();
				return;
			}

			if (msg instanceof HttpResponse response) {
				if (!responseStarted) {
					responseStarted = true;
					int statusCode = response.status().code();
					if (statusCode == 200 || statusCode == 206) {
						if (statusCode == 200 && startOffset > rangeState.start()) {
							future.completeExceptionally(
									new RetryAsSingleDownloadException("服务端忽略Range续传，切换为单线程全量下载"));
							ctx.close();
							return;
						}
						try {
							raf = new RandomAccessFile(tempFile.toFile(), "rw");
							raf.seek(startOffset);
						} catch (IOException e) {
							future.completeExceptionally(e);
							ctx.close();
							return;
						}
					} else if (statusCode >= 300 && statusCode < 400) {
						String location = response.headers().get(HttpHeaderNames.LOCATION);
						future.completeExceptionally(new RedirectSignalException(statusCode, location));
						ctx.close();
						return;
					} else {
						future.completeExceptionally(
								new IOException("HTTP " + statusCode + " for " + requestTarget));
						ctx.close();
						return;
					}
				}
			} else if (msg instanceof HttpContent content) {
				ByteBuf buf = content.content();
				if (buf.readableBytes() > 0 && raf != null) {
					try {
						long remaining = endOffset - startOffset - written + 1;
						if (remaining <= 0) {
							// server sent more than requested, stop reading
						} else {
							int toWrite = (int) Math.min(remaining, buf.readableBytes());
							byte[] bytes = new byte[toWrite];
							buf.readBytes(bytes, 0, toWrite);
							raf.write(bytes);
							written += toWrite;
							rangeState.onBytes(toWrite);
							tracker.onBytes(toWrite);
						}
					} catch (IOException e) {
						future.completeExceptionally(e);
						cleanup();
						ctx.close();
						return;
					}
				}

				if (content instanceof LastHttpContent) {
					cleanup();
					future.complete(null);
					ctx.close();
				}
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			if (!future.isDone()) {
				logger.warn("DownloadHandler exception on {} (range {}-{}): {}", requestTarget, startOffset,
						endOffset, cause.toString());
				cleanup();
				future.completeExceptionally(cause);
			}
			ctx.close();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			if (!future.isDone()) {
				logger.warn("DownloadHandler channel closed before completion for {} (range {}-{})",
						requestTarget, startOffset, endOffset);
				cleanup();
				future.completeExceptionally(new IOException("Connection closed unexpectedly"));
			}
		}

		private void cleanup() {
			if (raf != null) {
				try {
					raf.close();
				} catch (IOException ignore) {
				}
				raf = null;
			}
		}
	}

	// -----------------------------------------------------------------------
	// URL helpers
	// -----------------------------------------------------------------------

	private static String buildRequestTarget(URI uri, boolean absoluteForm) {
		String rawPath = uri.getRawPath();
		if (rawPath == null || rawPath.isEmpty()) {
			rawPath = "/";
		}
		if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
			rawPath += "?" + uri.getRawQuery();
		}
		if (!absoluteForm) {
			return rawPath;
		}
		String authority = uri.getRawAuthority();
		if (authority == null || authority.isBlank()) {
			authority = buildHostHeader(uri.getHost(),
					uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort(),
					"https".equalsIgnoreCase(uri.getScheme()));
		}
		return uri.getScheme() + "://" + authority + rawPath;
	}

	private static String buildHostHeader(String host, int port, boolean isHttps) {
		int defaultPort = isHttps ? 443 : 80;
		return port == defaultPort ? host : host + ":" + port;
	}

	private static String requireNonBlank(String value, String label) throws IOException {
		if (value == null || value.isBlank()) {
			throw new IOException("Invalid " + label + ": " + value);
		}
		return value;
	}

	// -----------------------------------------------------------------------
	// Stop / Progress helpers
	// -----------------------------------------------------------------------

	private void ensureNotStopped() throws IOException {
		if (this.stopRequested || Thread.currentThread().isInterrupted()) {
			throw new IOException("下载已暂停");
		}
	}

	private void fireProgress(ProgressSnapshot snapshot) {
		ProgressListener listener = this.progressListener;
		if (listener != null) {
			listener.onProgress(snapshot.downloadedBytes(), snapshot.totalBytes(), snapshot.partsCompleted(),
					snapshot.partsTotal());
		}
	}

	// -----------------------------------------------------------------------
	// Range splitting & state
	// -----------------------------------------------------------------------

	private List<Range> splitRanges(long totalSize, int count) {
		long chunk = totalSize / count;
		long remainder = totalSize % count;
		long cursor = 0;
		List<Range> ranges = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			long size = chunk + (i < remainder ? 1 : 0);
			long start = cursor;
			long end = cursor + size - 1;
			ranges.add(new Range(start, end));
			cursor = end + 1;
		}
		return ranges;
	}

	private static Path buildTempFile(Path targetFile) {
		return targetFile.resolveSibling(targetFile.getFileName().toString() + ".downloading");
	}

	private static Path buildMetadataFile(Path targetFile) {
		return targetFile.resolveSibling(targetFile.getFileName().toString() + ".downloading.meta");
	}

	private static List<RangeState> createRangeStates(List<Range> ranges) {
		List<RangeState> result = new ArrayList<>(ranges.size());
		for (Range range : ranges) {
			result.add(new RangeState(range.start, range.end, 0));
		}
		return result;
	}

	private static void initFreshTempFile(Path tempFile, Path metadataFile, long size) throws IOException {
		Files.deleteIfExists(metadataFile);
		Files.deleteIfExists(tempFile);
		try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
			raf.setLength(size);
		}
	}

	// -----------------------------------------------------------------------
	// Resume / Metadata
	// -----------------------------------------------------------------------

	private static ResumeMetadata loadMetadata(Path metadataFile) {
		if (!Files.exists(metadataFile)) {
			return null;
		}
		Properties properties = new Properties();
		try (InputStream inputStream = Files.newInputStream(metadataFile)) {
			properties.load(inputStream);
		} catch (IOException e) {
			return null;
		}
		long contentLength = parseLong(properties.getProperty("contentLength"), -1);
		int partCount = (int) parseLong(properties.getProperty("partCount"), -1);
		if (contentLength <= 0 || partCount <= 0) {
			return null;
		}
		List<RangeSnapshot> ranges = new ArrayList<>(partCount);
		for (int i = 0; i < partCount; i++) {
			long start = parseLong(properties.getProperty("part." + i + ".start"), -1);
			long end = parseLong(properties.getProperty("part." + i + ".end"), -1);
			long downloaded = parseLong(properties.getProperty("part." + i + ".downloaded"), -1);
			if (start < 0 || end < start || downloaded < 0) {
				return null;
			}
			ranges.add(new RangeSnapshot(start, end, downloaded));
		}
		String finalUrl = normalizeHeaderValue(properties.getProperty("finalUrl"));
		String etag = normalizeHeaderValue(properties.getProperty("etag"));
		String lastModified = normalizeHeaderValue(properties.getProperty("lastModified"));
		return new ResumeMetadata(finalUrl, contentLength, etag, lastModified, ranges);
	}

	private static boolean applyResumeState(ResumeMetadata metadata, ProbeResult probe, List<RangeState> rangeStates,
			Path tempFile) {
		if (metadata == null || !Files.exists(tempFile)) {
			return false;
		}
		if (metadata.contentLength() != probe.contentLength() || metadata.ranges().size() != rangeStates.size()) {
			return false;
		}
		if (!isSameIdentity(metadata.etag(), probe.etag())) {
			return false;
		}
		if (!isSameIdentity(metadata.lastModified(), probe.lastModified())) {
			return false;
		}
		for (int i = 0; i < rangeStates.size(); i++) {
			RangeState rangeState = rangeStates.get(i);
			RangeSnapshot snapshot = metadata.ranges().get(i);
			if (rangeState.start() != snapshot.start() || rangeState.end() != snapshot.end()) {
				return false;
			}
		}
		try {
			if (Files.size(tempFile) != probe.contentLength()) {
				try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
					raf.setLength(probe.contentLength());
				}
			}
		} catch (IOException e) {
			return false;
		}
		for (int i = 0; i < rangeStates.size(); i++) {
			RangeState rangeState = rangeStates.get(i);
			RangeSnapshot snapshot = metadata.ranges().get(i);
			rangeState.setDownloaded(snapshot.downloaded());
		}
		return true;
	}

	// -----------------------------------------------------------------------
	// Static parsing utilities
	// -----------------------------------------------------------------------

	private static boolean isRedirectCode(int code) {
		return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
	}

	private static boolean isRangeSupported(String acceptRanges) {
		return acceptRanges != null && acceptRanges.toLowerCase(Locale.ROOT).contains("bytes");
	}

	private static long parseContentLength(String contentLength) {
		if (contentLength == null || contentLength.isBlank()) {
			return -1;
		}
		try {
			return Long.parseLong(contentLength.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static long parseContentRangeSize(String contentRange) {
		if (contentRange == null || contentRange.isBlank()) {
			return -1;
		}
		int slash = contentRange.lastIndexOf('/');
		if (slash < 0 || slash + 1 >= contentRange.length()) {
			return -1;
		}
		String sizePart = contentRange.substring(slash + 1).trim();
		if ("*".equals(sizePart)) {
			return -1;
		}
		try {
			return Long.parseLong(sizePart);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static long parseLong(String value, long defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static String normalizeHeaderValue(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private static boolean isSameIdentity(String previous, String current) {
		if (previous == null || current == null) {
			return true;
		}
		return previous.equals(current);
	}

	private static String resolveLocation(String currentUrl, String location) throws IOException {
		if (location == null || location.isBlank()) {
			throw new IOException("重定向响应缺少Location头: " + currentUrl);
		}
		try {
			URI currentUri = new URI(currentUrl);
			return currentUri.resolve(location).toString();
		} catch (URISyntaxException e) {
			throw new IOException("解析重定向地址失败: " + location, e);
		}
	}

	private static void fail(CompletableFuture<?> future, Throwable cause) {
		future.completeExceptionally(cause);
	}

	// -----------------------------------------------------------------------
	// Inner types
	// -----------------------------------------------------------------------

	private static class Range {
		private final long start;
		private final long end;

		private Range(long start, long end) {
			this.start = start;
			this.end = end;
		}
	}

	private class RangeTask implements Callable<Void> {
		private final String finalUrl;
		private final Path tempFile;
		private final RangeState rangeState;
		private final DownloadTracker tracker;

		private RangeTask(String finalUrl, Path tempFile, RangeState rangeState, DownloadTracker tracker) {
			this.finalUrl = finalUrl;
			this.tempFile = tempFile;
			this.rangeState = rangeState;
			this.tracker = tracker;
		}

		@Override
		public Void call() throws Exception {
			downloadRange(this.finalUrl, this.tempFile, this.rangeState, this.tracker);
			return null;
		}
	}

	private class DownloadTracker {
		private final long totalBytes;
		private final int partsTotal;
		private final AtomicLong downloadedBytes;
		private final AtomicInteger partsCompleted;
		private final AtomicLong lastEmitMs = new AtomicLong(0L);
		private final Runnable checkpointAction;

		private DownloadTracker(long totalBytes, List<RangeState> rangeStates, Runnable checkpointAction) {
			this.totalBytes = totalBytes;
			this.partsTotal = rangeStates.size();
			this.checkpointAction = checkpointAction;
			long downloaded = 0L;
			int completed = 0;
			for (RangeState state : rangeStates) {
				downloaded += state.downloaded();
				if (state.isCompleted()) {
					completed++;
				}
			}
			this.downloadedBytes = new AtomicLong(downloaded);
			this.partsCompleted = new AtomicInteger(completed);
			this.lastEmitMs.set(System.currentTimeMillis());
		}

		private void onBytes(int delta) {
			this.downloadedBytes.addAndGet(delta);
			emitIfNeeded(false);
		}

		private void onPartCompleted() {
			int next = this.partsCompleted.incrementAndGet();
			if (next > this.partsTotal) {
				this.partsCompleted.set(this.partsTotal);
			}
			emitIfNeeded(true);
		}

		private void forceEmit() {
			emitIfNeeded(true);
		}

		private void emitIfNeeded(boolean force) {
			long now = System.currentTimeMillis();
			if (!force) {
				while (true) {
					long prev = this.lastEmitMs.get();
					if (now - prev < DEFAULT_PROGRESS_EMIT_INTERVAL_MS) {
						return;
					}
					if (this.lastEmitMs.compareAndSet(prev, now)) {
						break;
					}
				}
			} else {
				this.lastEmitMs.set(now);
			}
			fireProgress(snapshot());
			this.checkpointAction.run();
		}

		private ProgressSnapshot snapshot() {
			return new ProgressSnapshot(this.downloadedBytes.get(), this.totalBytes, this.partsCompleted.get(),
					this.partsTotal);
		}
	}

	@FunctionalInterface
	public interface ProgressListener {
		void onProgress(long downloadedBytes, long totalBytes, int partsCompleted, int partsTotal);
	}

	private record ProgressSnapshot(long downloadedBytes, long totalBytes, int partsCompleted, int partsTotal) {
	}

	private static class RangeState {
		private final long start;
		private final long end;
		private final AtomicLong downloaded;

		private RangeState(long start, long end, long downloaded) {
			this.start = start;
			this.end = end;
			this.downloaded = new AtomicLong(Math.max(0, downloaded));
		}

		private long start() {
			return this.start;
		}

		private long end() {
			return this.end;
		}

		private long size() {
			return this.end - this.start + 1;
		}

		private long downloaded() {
			long value = this.downloaded.get();
			return Math.min(value, size());
		}

		private void setDownloaded(long value) {
			long bounded = Math.max(0, Math.min(value, size()));
			this.downloaded.set(bounded);
		}

		private long currentOffset() {
			return this.start + downloaded();
		}

		private boolean isCompleted() {
			return downloaded() >= size();
		}

		private void onBytes(long delta) {
			if (delta <= 0) {
				return;
			}
			this.downloaded.updateAndGet(v -> {
				long next = v + delta;
				long max = size();
				return next > max ? max : next;
			});
		}

		private void markCompleted() {
			this.downloaded.set(size());
		}
	}

	private record RangeSnapshot(long start, long end, long downloaded) {
	}

	private record ResumeMetadata(String finalUrl, long contentLength, String etag, String lastModified,
			List<RangeSnapshot> ranges) {
	}

	private static class MetadataStore {
		private final Path metadataFile;
		private final String finalUrl;
		private final long contentLength;
		private final String etag;
		private final String lastModified;
		private final List<RangeState> rangeStates;

		private MetadataStore(Path metadataFile, String finalUrl, long contentLength, String etag, String lastModified,
				List<RangeState> rangeStates) {
			this.metadataFile = metadataFile;
			this.finalUrl = finalUrl;
			this.contentLength = contentLength;
			this.etag = etag;
			this.lastModified = lastModified;
			this.rangeStates = rangeStates;
		}

		private void persist() throws IOException {
			Properties properties = new Properties();
			properties.setProperty("finalUrl", this.finalUrl == null ? "" : this.finalUrl);
			properties.setProperty("contentLength", String.valueOf(this.contentLength));
			properties.setProperty("etag", this.etag == null ? "" : this.etag);
			properties.setProperty("lastModified", this.lastModified == null ? "" : this.lastModified);
			properties.setProperty("partCount", String.valueOf(this.rangeStates.size()));
			for (int i = 0; i < this.rangeStates.size(); i++) {
				RangeState state = this.rangeStates.get(i);
				properties.setProperty("part." + i + ".start", String.valueOf(state.start()));
				properties.setProperty("part." + i + ".end", String.valueOf(state.end()));
				properties.setProperty("part." + i + ".downloaded", String.valueOf(state.downloaded()));
			}
			try (OutputStream outputStream = Files.newOutputStream(this.metadataFile)) {
				properties.store(outputStream, null);
			}
		}

		private void persistQuietly() {
			try {
				persist();
			} catch (IOException ignored) {
			}
		}
	}

	private static class RetryAsSingleDownloadException extends IOException {
		private static final long serialVersionUID = 1L;

		private RetryAsSingleDownloadException(String message) {
			super(message);
		}
	}

	/**
	 * Thrown by DownloadHandler when a redirect response is received during
	 * streaming download. Caught by downloadRange() which follows the redirect.
	 */
	private static class RedirectSignalException extends IOException {
		private static final long serialVersionUID = 1L;
		final String location;

		RedirectSignalException(int statusCode, String location) {
			super("HTTP " + statusCode + " redirect: " + location);
			this.location = location;
		}
	}

	public record ProbeResult(String finalUrl, long contentLength, boolean rangeSupported, String etag,
			String lastModified) {
	}

	public record DownloadResult(String finalUrl, Path targetFile, long contentLength, int parts) {
	}

	private record ProbeResponse(int statusCode, Map<String, String> headers) {
		private String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}
	}

	// -----------------------------------------------------------------------
	// main
	// -----------------------------------------------------------------------

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			throw new IllegalArgumentException("用法: java ...NettyHttpDownloader <url> <output-path> [threads]");
		}

		String url = args[0];
		Path target = Path.of(args[1]);
		int threads = args.length >= 3 ? Integer.parseInt(args[2])
				: Math.max(2, Runtime.getRuntime().availableProcessors());

		try (NettyHttpDownloader downloader = new NettyHttpDownloader(threads)) {
			ProbeResult probe = downloader.probe(url);
			System.out.println("finalUrl=" + probe.finalUrl());
			System.out.println("contentLength=" + probe.contentLength());
			System.out.println("rangeSupported=" + probe.rangeSupported());

			DownloadResult result = downloader.download(url, target);
			System.out.println("downloaded=" + result.targetFile());
			System.out.println("size=" + result.contentLength());
			System.out.println("parts=" + result.parts());
		}
	}
}
