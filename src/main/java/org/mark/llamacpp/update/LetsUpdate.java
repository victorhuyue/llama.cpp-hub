package org.mark.llamacpp.update;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LetsUpdate {

	private static final Logger logger = LoggerFactory.getLogger(LetsUpdate.class);

	private static final LetsUpdate INSTANCE = new LetsUpdate();

	public enum UpdateStatus {
		IDLE("idle"),
		DOWNLOADING("downloading"),
		READY("ready"),
		APPLYING("applying"),
		ROLLBACK("rollback");

		private final String label;
		UpdateStatus(String label) { this.label = label; }
		public String getLabel() { return label; }
	}

	private final AtomicReference<UpdateStatus> status = new AtomicReference<>(UpdateStatus.IDLE);

	private static final String CACHE_DIR = "cache";
	private static final String UPDATE_ZIP = CACHE_DIR + File.separator + "update.zip";
	private static final String UPDATE_PENDING = CACHE_DIR + File.separator + "update-pending";
	private static final String UPDATE_PENDING_VERSION = CACHE_DIR + File.separator + "update-pending-version";

	private static final String[] TARGET_DIRS = {"classes"};

	public static LetsUpdate getInstance() {
		return INSTANCE;
	}

	private LetsUpdate() {
	}

	/**
	 * 从指定 URL 下载更新包。
	 * 状态转换：IDLE → DOWNLOADING → READY
	 * @param url 更新包下载链接
	 * @param version 版本号（如 v0.8.0），下载成功后持久化到磁盘
	 * @return 结果 Map：success, zipPath, size, error, status, version
	 */
	public Map<String, Object> download(String url, String version) {
		Map<String, Object> result = new ConcurrentHashMap<>();
		UpdateStatus current = status.get();
		if (current == UpdateStatus.IDLE) {
			if (!status.compareAndSet(UpdateStatus.IDLE, UpdateStatus.DOWNLOADING)) {
				current = status.get();
				result.put("success", false);
				result.put("error", getCurrentStatusError(current));
				result.put("status", current.getLabel());
				return result;
			}
		} else if (current == UpdateStatus.READY) {
			if (!status.compareAndSet(UpdateStatus.READY, UpdateStatus.DOWNLOADING)) {
				current = status.get();
				result.put("success", false);
				result.put("error", getCurrentStatusError(current));
				result.put("status", current.getLabel());
				return result;
			}
			Path userDir = Paths.get(System.getProperty("user.dir"));
			Path oldZip = userDir.resolve(UPDATE_ZIP).normalize();
			deleteQuietly(oldZip);
			Path oldVersionFile = userDir.resolve(UPDATE_PENDING_VERSION).normalize();
			deleteQuietly(oldVersionFile);
		} else {
			result.put("success", false);
			result.put("error", getCurrentStatusError(current));
			result.put("status", current.getLabel());
			return result;
		}
		try {
			if (url == null || url.trim().isEmpty()) {
				status.set(UpdateStatus.IDLE);
				result.put("success", false);
				result.put("error", "缺少下载链接");
				return result;
			}
			Path zipPath = downloadZip(url.trim(), UPDATE_ZIP);
			if (zipPath == null) {
				status.set(UpdateStatus.IDLE);
				result.put("success", false);
				result.put("error", "下载更新包失败");
				return result;
			}
			Path userDir = Paths.get(System.getProperty("user.dir"));
			savePendingVersion(userDir, version);
			status.set(UpdateStatus.READY);
			result.put("success", true);
			result.put("zipPath", UPDATE_ZIP);
			result.put("size", Files.size(zipPath));
			result.put("version", version);
			result.put("status", UpdateStatus.READY.getLabel());
			return result;
		} catch (Exception e) {
			logger.error("下载更新包时发生错误", e);
			status.set(UpdateStatus.IDLE);
			result.put("success", false);
			result.put("error", "下载更新失败: " + e.getMessage());
			return result;
		}
	}

	/**
	 * 将版本号写入磁盘，供跨请求恢复状态使用。
	 */
	private void savePendingVersion(Path userDir, String version) {
		try {
			Path versionFile = userDir.resolve(UPDATE_PENDING_VERSION).normalize();
			Path parent = versionFile.getParent();
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
			try (BufferedWriter writer = Files.newBufferedWriter(versionFile)) {
				writer.write(version != null ? version : "");
			}
		} catch (IOException e) {
			logger.warn("保存待更新版本号失败: {}", e.getMessage());
		}
	}

	/**
	 * 从磁盘读取待应用的版本号。
	 * @return 版本号，不存在则返回 null
	 */
	public String getPendingVersion() {
		try {
			Path userDir = Paths.get(System.getProperty("user.dir"));
			Path versionFile = userDir.resolve(UPDATE_PENDING_VERSION).normalize();
			if (!Files.exists(versionFile)) {
				return null;
			}
			try (BufferedReader reader = Files.newBufferedReader(versionFile)) {
				String line = reader.readLine();
				return line != null && !line.isEmpty() ? line : null;
			}
		} catch (IOException e) {
			logger.warn("读取待更新版本号失败: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * 应用更新包，替换程序文件。
	 * 状态转换：READY → APPLYING → IDLE（成功）或 APPLYING → ROLLBACK → IDLE（失败）
	 * @param zip 更新包路径
	 * @return 结果 Map：success, message, error, status
	 */
	public Map<String, Object> doUpdate(File zip) {
		Map<String, Object> result = new ConcurrentHashMap<>();
		UpdateStatus current = status.get();
		if (current == UpdateStatus.READY) {
			if (!status.compareAndSet(UpdateStatus.READY, UpdateStatus.APPLYING)) {
				current = status.get();
				result.put("success", false);
				result.put("error", getCurrentStatusError(current));
				result.put("status", current.getLabel());
				return result;
			}
		} else {
			result.put("success", false);
			result.put("error", getCurrentStatusError(current));
			result.put("status", current.getLabel());
			return result;
		}
		try {
			if (zip == null || !zip.exists()) {
				status.set(UpdateStatus.IDLE);
				result.put("success", false);
				result.put("error", "更新包文件不存在");
				return result;
			}
			Path userDir = Paths.get(System.getProperty("user.dir"));
			Path pendingDir = userDir.resolve(UPDATE_PENDING).normalize();
			Path zipFile = zip.toPath().normalize();
			try {
				extractZip(zipFile, pendingDir);
				backupOldFiles(userDir);
				moveNewFiles(userDir, pendingDir);
			} catch (Exception e) {
				logger.error("应用更新时发生错误，尝试回滚", e);
				status.set(UpdateStatus.ROLLBACK);
				rollback(userDir);
				status.set(UpdateStatus.IDLE);
				cleanup(pendingDir);
				result.put("success", false);
				result.put("error", "应用更新失败: " + e.getMessage());
				return result;
			}
			cleanup(pendingDir);
			Path zipToDelete = userDir.resolve(UPDATE_ZIP).normalize();
			deleteQuietly(zipToDelete);
			Path versionFile = userDir.resolve(UPDATE_PENDING_VERSION).normalize();
			deleteQuietly(versionFile);
			status.set(UpdateStatus.IDLE);
			result.put("success", true);
			result.put("message", "更新已应用，请重启程序生效");
			return result;
		} catch (Exception e) {
			logger.error("应用更新时发生错误", e);
			status.set(UpdateStatus.IDLE);
			result.put("success", false);
			result.put("error", "应用更新失败: " + e.getMessage());
			return result;
		}
	}

	/**
	 * 获取当前状态不允许操作的错误信息。
	 */
	private String getCurrentStatusError(UpdateStatus current) {
		switch (current) {
			case DOWNLOADING: return "正在下载更新包，请稍候";
			case READY: return "已有更新包待应用，请先应用或重新下载";
			case APPLYING: return "正在应用更新，请稍候";
			case ROLLBACK: return "正在回滚更新，请稍候";
			default: return "正在更新中，请稍后再试";
		}
	}

	/**
	 * 获取当前更新状态。
	 */
	public UpdateStatus getStatus() {
		return status.get();
	}

	/**
	 * 取消待应用的更新包，回到空闲状态。
	 * 状态转换：READY → IDLE
	 * @return 结果 Map：success, error
	 */
	public Map<String, Object> cancelDownload() {
		Map<String, Object> result = new ConcurrentHashMap<>();
		UpdateStatus current = status.get();
		if (current == UpdateStatus.READY) {
			if (!status.compareAndSet(UpdateStatus.READY, UpdateStatus.IDLE)) {
				current = status.get();
				result.put("success", false);
				result.put("error", getCurrentStatusError(current));
				result.put("status", current.getLabel());
				return result;
			}
			Path userDir = Paths.get(System.getProperty("user.dir"));
			Path zipToDelete = userDir.resolve(UPDATE_ZIP).normalize();
			deleteQuietly(zipToDelete);
			Path versionFile = userDir.resolve(UPDATE_PENDING_VERSION).normalize();
			deleteQuietly(versionFile);
			result.put("success", true);
			result.put("status", UpdateStatus.IDLE.getLabel());
			return result;
		} else {
			result.put("success", false);
			result.put("error", getCurrentStatusError(current));
			result.put("status", current.getLabel());
			return result;
		}
	}

	/**
	 * 通过 HttpURLConnection 下载文件。
	 */
	private Path downloadZip(String downloadUrl, String targetPath) throws IOException {
		Path target = Paths.get(targetPath);
		Path parent = target.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		URL url = URI.create(downloadUrl).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("User-Agent", "llama.cpp-hub-updater");
		conn.setRequestProperty("Accept", "*/*");
		conn.setConnectTimeout(30_000);
		conn.setReadTimeout(120_000);
		int respCode = conn.getResponseCode();
		if (respCode != HttpURLConnection.HTTP_OK) {
			conn.disconnect();
			throw new IOException("下载失败: HTTP " + respCode);
		}
		try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target.toFile())) {
			byte[] buf = new byte[8192];
			int len;
			while ((len = in.read(buf)) != -1) {
				out.write(buf, 0, len);
			}
			out.flush();
		}
		conn.disconnect();
		return target;
	}

	/**
	 * 解压 ZIP，跳过顶层目录，只提取 classes/。
	 */
	private void extractZip(Path zipFile, Path targetDir) throws IOException {
		if (Files.exists(targetDir)) {
			deleteDir(targetDir);
		}
		Files.createDirectories(targetDir);
		try (ZipFile zfile = new ZipFile(zipFile.toFile())) {
			zfile.entries().asIterator().forEachRemaining(entry -> {
				try {
					String name = entry.getName();
					String relPath = getRelPath(name);
					if (relPath == null) {
						return;
					}
					if (!isTargetDir(relPath)) {
						return;
					}
					Path outPath = targetDir.resolve(relPath).normalize();
					if (!startsWith(outPath, targetDir)) {
						logger.warn("ZIP 条目路径不安全，跳过: {}", name);
						return;
					}
					if (entry.isDirectory()) {
						Files.createDirectories(outPath);
					} else {
						Path parent = outPath.getParent();
						if (parent != null && !Files.exists(parent)) {
							Files.createDirectories(parent);
						}
						try (InputStream in = zfile.getInputStream(entry)) {
							Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
						}
					}
				} catch (IOException e) {
					logger.error("解压条目失败: {}", entry.getName(), e);
				}
			});
		}
	}

	/**
	 * 获取 ZIP 条目相对于目标目录的路径。跳过顶层目录（如 "llama.cpp-hub-v0.8.0-windows/"），
	 * 返回以目标目录开头的路径（如 "classes/org/mark/..."）。如果条目不属于任何目标目录则返回 null。
	 */
	private String getRelPath(String entryName) {
		for (String dir : TARGET_DIRS) {
			String prefix = dir + "/";
			int idx = entryName.indexOf(prefix);
			if (idx >= 0) {
				String rel = entryName.substring(idx);
				return rel;
			}
		}
		return null;
	}

	/**
	 * 判断相对路径是否属于目标目录。
	 */
	private boolean isTargetDir(String relPath) {
		for (String dir : TARGET_DIRS) {
			if (relPath.startsWith(dir + "/") || relPath.equals(dir)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 检查 child 是否以 parent 开头。
	 */
	private boolean startsWith(Path child, Path parent) {
		return child.startsWith(parent);
	}

	/**
	 * 备份旧文件：classes/ → cache/old-classes/。
	 */
	private void backupOldFiles(Path userDir) throws IOException {
		for (String dir : TARGET_DIRS) {
			Path src = userDir.resolve(dir).normalize();
			if (!Files.exists(src)) {
				continue;
			}
			Path backupDir = userDir.resolve(CACHE_DIR).resolve("old-" + dir).normalize();
			if (Files.exists(backupDir)) {
				deleteDir(backupDir);
			}
			Files.createDirectories(backupDir.getParent());
			Files.move(src, backupDir, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * 移入新文件：从 pendingDir 移动 classes/ 到 userDir。
	 */
	private void moveNewFiles(Path userDir, Path pendingDir) throws IOException {
		for (String dir : TARGET_DIRS) {
			Path src = pendingDir.resolve(dir).normalize();
			if (!Files.exists(src)) {
				continue;
			}
			Path target = userDir.resolve(dir).normalize();
			if (Files.exists(target)) {
				deleteDir(target);
			}
			Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * 回滚：将 cache/old-classes 移回原位置。
	 */
	private void rollback(Path userDir) {
		for (String dir : TARGET_DIRS) {
			Path backupDir = userDir.resolve(CACHE_DIR).resolve("old-" + dir).normalize();
			if (!Files.exists(backupDir)) {
				continue;
			}
			Path target = userDir.resolve(dir).normalize();
			try {
				if (Files.exists(target)) {
					deleteDir(target);
				}
				Files.move(backupDir, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				logger.error("回滚失败: {} -> {}", backupDir, target, e);
			}
		}
	}

	/**
	 * 清理临时目录。
	 */
	private void cleanup(Path dir) {
		if (dir != null && Files.exists(dir)) {
			deleteDir(dir);
		}
	}

	private void deleteQuietly(Path path) {
		if (path != null && Files.exists(path)) {
			deleteDir(path);
		}
	}

	/**
	 * 递归删除目录。
	 */
	private void deleteDir(Path dir) {
		try {
			if (!Files.exists(dir)) {
				return;
			}
			Files.walk(dir)
					.sorted((a, b) -> b.compareTo(a))
					.forEach(p -> {
						try {
							Files.delete(p);
						} catch (IOException e) {
							logger.warn("删除失败: {}", p, e);
						}
					});
		} catch (IOException e) {
			logger.warn("遍历目录失败: {}", dir, e);
		}
	}
}
