package org.mark.llamacpp.server.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;

public class CertController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(CertController.class);

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (uri.startsWith("/api/cert/generate")) {
            this.handleGenerate(ctx, request);
            return true;
        }
        if (uri.startsWith("/api/cert/status")) {
            this.handleStatus(ctx, request);
            return true;
        }
        if (uri.startsWith("/api/cert/download")) {
            this.handleDownload(ctx, request);
            return true;
        }
        return false;
    }

    private void handleStatus(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
            String keystorePath = LlamaServer.getHttpsCertPath();
            Path path = Paths.get(keystorePath).toAbsolutePath().normalize();
            boolean exists = Files.exists(path) && Files.isRegularFile(path);

            Map<String, Object> data = new HashMap<>();
            data.put("exists", exists);
            data.put("path", keystorePath);
            data.put("password", LlamaServer.getHttpsPassword());
            if (exists) {
                try {
                    data.put("size", Files.size(path));
                } catch (Exception ignore) {
                }
            }
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (RequestMethodException e) {
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("获取证书状态时发生异常", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取证书状态失败: " + e.getMessage()));
        }
    }

    private void handleDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.GET) {
            LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "只支持GET请求");
            return;
        }
        try {
            String keystorePath = LlamaServer.getHttpsCertPath();
            Path filePath = Paths.get(keystorePath).toAbsolutePath().normalize();
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "证书文件不存在");
                return;
            }

            String fileName = filePath.getFileName() == null ? "keystore.p12" : filePath.getFileName().toString();
            long fileLength = Files.size(filePath);
            RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");

            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-pkcs12");
            response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            LlamaServer.setCorsHeaders(response.headers());

            ctx.write(response);
            ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
            ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            last.addListener(f -> {
                try {
                    raf.close();
                } catch (Exception ignore) {
                }
                ctx.close();
            });
        } catch (Exception e) {
            logger.error("下载证书时发生异常", e);
            LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "下载证书失败: " + e.getMessage());
        }
    }

    private void handleGenerate(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
            JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
            if (body == null)
                return;

            List<String> ips = JsonUtil.getJsonStringList(body.get("ips"));
            List<String> hostnames = JsonUtil.getJsonStringList(body.get("hostnames"));
            int validity = JsonUtil.getJsonInt(body, "validity", 3650);
            if (validity < 1)
                validity = 3650;
            String password = JsonUtil.getJsonString(body, "password");
            if (password.isEmpty()) {
                password = generatePassword();
            }
            int keysize = JsonUtil.getJsonInt(body, "keysize", 2048);
            if (keysize != 2048 && keysize != 4096)
                keysize = 2048;
            String outputDir = JsonUtil.getJsonString(body, "outputDir", "ssl");

            String userCn = JsonUtil.getJsonString(body, "cn");
            if (userCn.isEmpty()) {
                if (hostnames != null && !hostnames.isEmpty()) {
                    userCn = hostnames.get(0);
                } else {
                    userCn = "localhost";
                }
            }

            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);
            String keystoreFile = outputPath.resolve("keystore.p12").toString();

            StringBuilder sanBuilder = new StringBuilder();
            if (hostnames != null) {
                for (String h : hostnames) {
                    String t = h.trim();
                    if (!t.isEmpty()) {
                        if (sanBuilder.length() > 0)
                            sanBuilder.append(",");
                        sanBuilder.append("DNS:").append(t);
                    }
                }
            }
            if (!sanBuilder.toString().contains("DNS:localhost")) {
                if (sanBuilder.length() > 0)
                    sanBuilder.append(",");
                sanBuilder.append("DNS:localhost");
            }
            if (!sanBuilder.toString().contains("IP:127.0.0.1")) {
                sanBuilder.append(",IP:127.0.0.1");
            }
            if (ips != null) {
                for (String ip : ips) {
                    String t = ip.trim();
                    if (!t.isEmpty() && !"127.0.0.1".equals(t)) {
                        sanBuilder.append(",IP:").append(t);
                    }
                }
            }

            String cn = userCn;
            String dname = "CN=" + cn + ",OU=llamacpp-hub,O=llamacpp-hub,L=Unknown,ST=Unknown,C=CN";

            String javaHome = System.getProperty("java.home");
            boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
            String keytoolPath = javaHome + File.separator + "bin" + File.separator
                    + (isWindows ? "keytool.exe" : "keytool");

            List<String> cmd = new ArrayList<>();
            cmd.add(keytoolPath);
            cmd.add("-genkeypair");
            cmd.add("-alias");
            cmd.add("server");
            cmd.add("-keyalg");
            cmd.add("RSA");
            cmd.add("-keysize");
            cmd.add(String.valueOf(keysize));
            cmd.add("-keystore");
            cmd.add(keystoreFile);
            cmd.add("-storetype");
            cmd.add("PKCS12");
            cmd.add("-storepass");
            cmd.add(password);
            cmd.add("-keypass");
            cmd.add(password);
            cmd.add("-dname");
            cmd.add(dname);
            cmd.add("-validity");
            cmd.add(String.valueOf(validity));
            cmd.add("-ext");
            cmd.add("SAN=" + sanBuilder.toString());

            String cmdString = cmd.stream().map(s -> {
                if (s.contains(" ") || s.contains(",") || s.contains("="))
                    return "\"" + s + "\"";
                return s;
            }).collect(Collectors.joining(" "));
            // 在执行之前，应该删除旧的证书
            try {
            	Path p = outputPath.resolve("keystore.p12");
            	if (Files.exists(p)) {
            		logger.info("删除旧证书：{}", keystoreFile);
                	Files.delete(p);	
            	}
            }catch (IOException e) {
            	e.printStackTrace();
			}
            
            logger.info("执行命令: {}", cmdString);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String err = output.toString().trim();
                logger.error("证书生成失败, exitCode={}, 输出: {}", exitCode, err);
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error("证书生成失败: " + err));
                return;
            }

            String expireDate = LocalDate.now().plusDays(validity).format(DateTimeFormatter.ISO_LOCAL_DATE);

            Map<String, Object> data = new HashMap<>();
            data.put("path", keystoreFile);
            data.put("password", password);
            data.put("san", sanBuilder.toString());
            data.put("expireDate", expireDate);
            data.put("keysize", keysize);
            data.put("command", cmdString);

            logger.info("HTTPS证书生成成功: {}", keystoreFile);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (RequestMethodException e) {
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("证书生成异常", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error("证书生成异常: " + e.getMessage()));
        }
    }

    private static String generatePassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
