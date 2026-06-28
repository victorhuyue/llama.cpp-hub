package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.mark.llamacpp.server.LlamaServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EasyChat 助手头像存储服务。
 * <p>
 * 头像文件与助手 ID 绑定，存储在 {@code cache/easy-chat/avatars/} 目录下。
 * 每个助手最多保存一个头像文件，格式限定为常见图片类型。
 * </p>
 */
public class EasyChatAvatarService {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatAvatarService.class);

	private static final long MAX_AVATAR_UPLOAD_BYTES = 1L * 1024L * 1024L;
	private static final String[] AVATAR_EXTS = new String[] { "png", "jpg", "jpeg", "gif", "webp" };
	private static final String ASSISTANT_ID_PATTERN = "[A-Za-z0-9_-]+";

	private static final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

	private static volatile EasyChatAvatarService instance;

	public static EasyChatAvatarService getInstance() {
		if (instance == null) {
			synchronized (EasyChatAvatarService.class) {
				if (instance == null) {
					instance = new EasyChatAvatarService();
				}
			}
		}
		return instance;
	}

	private EasyChatAvatarService() {
	}

	private Path getAvatarDir() throws IOException {
		Path dir = LlamaServer.getCachePath().resolve("easy-chat").resolve("avatars").toAbsolutePath().normalize();
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	/**
	 * 保存助手头像。
	 *
	 * @param assistantId       助手 ID，只允许字母、数字、下划线、中划线
	 * @param bytes             图片文件内容
	 * @param originalFileName  原始文件名，用于推断扩展名
	 * @param contentType       HTTP Content-Type，用于推断扩展名
	 * @return 保存后的文件名（不含路径）
	 * @throws IllegalArgumentException 参数校验失败
	 * @throws IOException              文件写入失败
	 */
	public String saveAvatar(String assistantId, byte[] bytes, String originalFileName, String contentType) {
		validateAssistantId(assistantId);
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("文件内容为空");
		}
		if (bytes.length > MAX_AVATAR_UPLOAD_BYTES) {
			throw new IllegalArgumentException("头像文件超过最大限制: 1MB");
		}

		String ext = resolveExtension(contentType, originalFileName);
		if (ext == null) {
			throw new IllegalArgumentException("仅支持图片格式: png/jpg/jpeg/gif/webp");
		}

		Path dir;
		try {
			dir = getAvatarDir();
		} catch (IOException e) {
			throw new RuntimeException("创建头像目录失败", e);
		}

		ReentrantLock lock = locks.computeIfAbsent(assistantId, k -> new ReentrantLock());
		lock.lock();
		try {
			Path target = dir.resolve(assistantId + "." + ext).toAbsolutePath().normalize();
			if (!target.startsWith(dir)) {
				throw new IllegalArgumentException("非法文件名");
			}

			// 删除该助手旧的各格式头像
			for (String e : AVATAR_EXTS) {
				try {
					Files.deleteIfExists(dir.resolve(assistantId + "." + e));
				} catch (Exception ignore) {
				}
			}

			Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
			Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

			logger.info("[EasyChat][Avatar] 保存头像成功 assistantId={} file={} size={}", assistantId, target, bytes.length);
			return target.getFileName().toString();
		} catch (IOException e) {
			throw new RuntimeException("保存头像失败", e);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 查找助手头像文件路径。
	 *
	 * @param assistantId 助手 ID
	 * @return 头像文件路径，不存在时返回 null
	 */
	public Path findAvatarFile(String assistantId) {
		validateAssistantId(assistantId);
		Path dir;
		try {
			dir = getAvatarDir();
		} catch (IOException e) {
			logger.warn("[EasyChat][Avatar] 获取头像目录失败", e);
			return null;
		}

		for (String e : AVATAR_EXTS) {
			Path p = dir.resolve(assistantId + "." + e).toAbsolutePath().normalize();
			if (!p.startsWith(dir)) {
				continue;
			}
			try {
				if (Files.isRegularFile(p)) {
					return p;
				}
			} catch (Exception ignore) {
			}
		}
		return null;
	}

	/**
	 * 删除助手头像。
	 *
	 * @param assistantId 助手 ID
	 * @return 是否成功删除了至少一个文件
	 */
	public boolean deleteAvatar(String assistantId) {
		validateAssistantId(assistantId);
		Path dir;
		try {
			dir = getAvatarDir();
		} catch (IOException e) {
			logger.warn("[EasyChat][Avatar] 获取头像目录失败", e);
			return false;
		}

		ReentrantLock lock = locks.computeIfAbsent(assistantId, k -> new ReentrantLock());
		lock.lock();
		try {
			boolean deleted = false;
			for (String e : AVATAR_EXTS) {
				try {
					Path p = dir.resolve(assistantId + "." + e).toAbsolutePath().normalize();
					if (p.startsWith(dir)) {
						deleted = Files.deleteIfExists(p) || deleted;
					}
				} catch (Exception ignore) {
				}
			}
			if (deleted) {
				logger.info("[EasyChat][Avatar] 删除头像成功 assistantId={}", assistantId);
			}
			return deleted;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 根据文件路径推断图片 MIME 类型。
	 */
	public static String inferImageContentType(Path file) {
		if (file == null) {
			return "application/octet-stream";
		}
		String n = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
		if (n.endsWith(".png")) return "image/png";
		if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
		if (n.endsWith(".gif")) return "image/gif";
		if (n.endsWith(".webp")) return "image/webp";
		return "application/octet-stream";
	}

	private static void validateAssistantId(String assistantId) {
		if (assistantId == null || assistantId.trim().isEmpty()) {
			throw new IllegalArgumentException("缺少assistantId参数");
		}
		String id = assistantId.trim();
		if (id.length() > 128) {
			throw new IllegalArgumentException("assistantId过长");
		}
		if (!id.matches(ASSISTANT_ID_PATTERN)) {
			throw new IllegalArgumentException("assistantId包含非法字符");
		}
	}

	private static String resolveExtension(String contentType, String originalFileName) {
		String ext = normalizeAvatarExt(extensionFromContentType(contentType));
		if (ext == null) {
			ext = normalizeAvatarExt(extractSafeFileExtension(originalFileName));
		}
		return ext;
	}

	private static String extensionFromContentType(String contentType) {
		if (contentType == null) {
			return null;
		}
		String ct = contentType.trim().toLowerCase();
		if (ct.isEmpty()) {
			return null;
		}
		// 仅匹配 image/xxx 前缀，忽略参数
		if (!ct.startsWith("image/")) {
			return null;
		}
		String subtype = ct.substring("image/".length()).trim();
		int semi = subtype.indexOf(';');
		if (semi >= 0) {
			subtype = subtype.substring(0, semi).trim();
		}
		if (subtype.isEmpty()) {
			return null;
		}
		if ("jpeg".equals(subtype) || "jpg".equals(subtype)) return "jpg";
		if ("png".equals(subtype)) return "png";
		if ("gif".equals(subtype)) return "gif";
		if ("webp".equals(subtype)) return "webp";
		return null;
	}

	private static String extractSafeFileExtension(String originalFileName) {
		if (originalFileName == null) {
			return null;
		}
		String n = originalFileName.trim();
		if (n.isEmpty()) {
			return null;
		}
		int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
		if (slash >= 0 && slash < n.length() - 1) {
			n = n.substring(slash + 1);
		}
		int dot = n.lastIndexOf('.');
		if (dot <= 0 || dot >= n.length() - 1) {
			return null;
		}
		String ext = n.substring(dot + 1);
		if (ext.length() > 16) {
			return null;
		}
		for (int i = 0; i < ext.length(); i++) {
			char ch = ext.charAt(i);
			boolean ok = (ch >= '0' && ch <= '9')
				|| (ch >= 'a' && ch <= 'z')
				|| (ch >= 'A' && ch <= 'Z');
			if (!ok) {
				return null;
			}
		}
		return ext.toLowerCase();
	}

	private static String normalizeAvatarExt(String ext) {
		if (ext == null) {
			return null;
		}
		String e = ext.trim().toLowerCase();
		if (e.isEmpty()) {
			return null;
		}
		for (String allow : AVATAR_EXTS) {
			if (allow.equals(e)) {
				return e;
			}
		}
		return null;
	}
}
