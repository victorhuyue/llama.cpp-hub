# JVM 内存优化计划

**目标**: JVM heap 限制到 96-128MB  
**场景**: Ollama + LMStudio + MCP 三服务全开，3-5 并发，20+ GGUF 模型  
**策略**: 平衡优化，适度用磁盘换内存  
**日期**: 2026-06-03

---

## JVM 启动参数

```
-Xms64m -Xmx128m -XX:+UseG1GC -XX:MaxGCPauseMillis=50
-XX:MetaspaceSize=32m -XX:MaxMetaspaceSize=64m
-XX:ReservedCodeCacheSize=32m
```

---

## 第1批：关键修复（预计节省 50-80MB）

### [x] 1. GGUFMetaDataReader 改用 MappedByteBuffer

**文件**: `src/main/java/org/mark/llamacpp/gguf/GGUFMetaDataReader.java:12-13`

**现状**:
```java
int bufSize = (int) Math.min(size, 64L * 1024 * 1024);
java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(bufSize);
```

**问题**: 每次扫描一个模型文件，分配 64MB on-heap ByteBuffer。20+ 模型扫描时峰值压力巨大。

**方案**: 改用 `FileChannel.map(FileChannel.MapMode.READ_ONLY, 0, Math.min(size, Integer.MAX_VALUE))` 内存映射。GGUF metadata 只在文件头部，map 后读取完 metadata 即可，不需要把整个文件加载到 heap。

**改动要点**:
- `ByteBuffer.allocate(bufSize)` → `channel.map(FileChannel.MapMode.READ_ONLY, 0, Math.min(size, 64L * 1024 * 1024))`
- MappedByteBuffer 是 off-heap，不占 heap 空间
- 读取完 metadata 后 buffer 自然释放（map 到 OS page cache）

**状态**: ✅ 已完成

---

### [ ] 2. HttpObjectAggregator 16MB → 4MB

**文件及行号**:
- `src/main/java/org/mark/llamacpp/server/LlamaServer.java:359`
- `src/main/java/org/mark/llamacpp/ollama/Ollama.java:50`
- `src/main/java/org/mark/llamacpp/lmstudio/LMStudio.java:42`

**现状**:
```java
private static final int MAX_HTTP_CONTENT_LENGTH = 16 * 1024 * 1024;
```

**问题**: HttpObjectAggregator 会把整个 HTTP request body 聚合到内存中。16MB 上限意味着单个请求可占 16MB heap。

**方案**: 改为 4MB。Chat 请求 body（含 messages 数组）通常 <500KB，4MB 绰绰有余。

**注意**: MCP 服务器的 `NettySseMcpServer.java:74` 已经是 2MB，无需改动。

**状态**: 待执行

---

### [ ] 3. WebSocket max frame Integer.MAX_VALUE → 32KB

**文件**: `src/main/java/org/mark/llamacpp/server/LlamaServer.java:1059,1073`

**现状**:
```java
.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, Integer.MAX_VALUE))
```

**问题**: 允许单个 WebSocket frame 达到 2GB，恶意或异常客户端可耗尽内存。

**方案**: 改为 `65536`（64KB）。WebSocket 消息通常是短控制指令或状态推送，64KB 足够。

**状态**: 待执行

---

### [ ] 4. Console buffer 2MB → 256KB

**文件**: `src/main/java/org/mark/llamacpp/server/LlamaServer.java:381`

**现状**:
```java
private static final int CONSOLE_BUFFER_MAX_BYTES = 2 * 1024 * 1024;
```

**问题**: 静态 StringBuilder 常驻 2MB，且 high-water mark 不会收缩。

**方案**: 改为 `256 * 1024`（256KB）。终端日志保留最近 256KB 足够查看上下文。

**状态**: 待执行

---

## 第2批：EventLoopGroup 线程优化（预计节省 10-20MB thread stacks）

### [ ] 5. 限制 worker 线程数

**文件及行号**:
- `LlamaServer.java:1039`: `new NioEventLoopGroup()` → `new NioEventLoopGroup(4)`
- `Ollama.java:126`: `new NioEventLoopGroup()` → `new NioEventLoopGroup(2)`
- `LMStudio.java:111`: `new NioEventLoopGroup()` → `new NioEventLoopGroup(2)`
- `NettySseMcpServer.java:66`: `new NioEventLoopGroup()` → `new NioEventLoopGroup(2)`

**现状**: 默认 `NioEventLoopGroup()` 创建 `availableProcessors * 2` 个线程。8 核机器 = 16 个 worker threads。4 个服务器 = 64 个 worker + 4 个 boss = 68 个线程，每个 1MB stack = 68MB off-heap committed。

**方案**: 3-5 并发场景下，4+2+2+2 = 10 个 worker 足够。Boss 已经设了 1。

**预期**: 从 ~68MB thread stacks 降到 ~14MB。

**状态**: 待执行

---

## 第3批：请求处理优化（预计节省 5-15MB）

### [⏸] 6. 非流式响应改为流式转发 — 性价比低，暂缓

**文件**: `src/main/java/org/mark/llamacpp/server/service/OpenAIService.java:1263-1328`

**现状**: `handleNonStreamResponse` 用 `StringBuilder` 累积完整响应体，再一次性写入。数据在 heap 上有 4-5 份拷贝（StringBuilder char[], String, byte[], ByteBuf）。

**为什么不能直接流式**: 第 1288-1294 行需要对完整响应做 `JsonUtil.tryParseObject()` + `ensureToolCallIds()` 处理，必须拿到完整 JSON 才能修改，无法逐 chunk 转发。

**折中方案**: 用 `ByteArrayOutputStream` 替代 `StringBuilder`，减少中间拷贝（2 份代替 4-5 份）。

**结论**: 非流式请求占比不高（多数客户端用流式），500KB 在 128MB heap 下仅 0.4%。改动涉及 JSON 处理逻辑，风险大于收益，暂缓。

**状态**: 暂缓

---

### [ ] 7. ChatStreamSession deferred buffer 1MB → 256KB

**文件**: `src/main/java/org/mark/llamacpp/server/service/ChatStreamSession.java:48`

**现状**:
```java
private static final int DEFERRED_MEMORY_LIMIT = 1024 * 1024;
```

**问题**: ByteArrayOutputStream 内部 byte[] 会翻倍增长，1MB limit 实际可能占 2MB。

**方案**: 改为 `256 * 1024`（256KB）。超出 limit 后 spool 到磁盘临时文件。

**状态**: 待执行

---

### [✗] 8. Netty PooledByteBufAllocator 配置 — 已弃用，移除

**状态**: 已移除（`ChannelOption.ALLOCATOR` 在 Netty 4.1 后期版本已弃用）

---

## 第4批：杂项优化（预计节省 1-3MB）

### [ ] 9. 统一 Gson 实例

**现状**: 6+ 处各自创建 `new Gson()`，每个实例维护独立的反射缓存。

**涉及文件**:
- `LlamaServer.java:469`
- `LlamaServerManager.java:68`
- `ConfigManager.java:51`
- `DownloadTaskManager.java:27`
- `WebSocketServerHandler.java:32`
- `CompletionRouterHandler.java:49`

**方案**: 新建 `src/main/java/org/mark/llamacpp/server/tools/GsonFactory.java`（或放入已有的 `JsonUtil.java`），提供全局共享的 `Gson` 单例。各文件改为引用共享实例。

**状态**: 待执行

---

### [ ] 10. DownloadTaskManager 完成任务定期清理

**文件**: `src/main/java/org/mark/file/downloader/DownloadTaskManager.java:46-48`

**现状**: 完成和失败的任务永久保留在 `taskStore` 中。

**方案**: 添加 `ScheduledExecutorService`，每 5 分钟清理一次超过 5 分钟前的已完成/失败任务。

**状态**: 待执行

---

### [ ] 11. ConfigManager 缓存改用 SoftReference

**文件**: `src/main/java/org/mark/llamacpp/server/ConfigManager.java:42-43`

**现状**:
```java
private volatile List<Map<String, Object>> cachedModelsConfig = null;
```

**方案**: 改为 `private volatile SoftReference<List<Map<String, Object>>> cachedModelsConfig = null;`，内存紧张时 GC 自动回收。

**状态**: 待执行

---

## 预期内存分布（128MB 目标）

| 模块 | 优化前 | 优化后 |
|------|--------|--------|
| JVM 基础 (RT, metaspace, code cache) | ~40MB | ~40MB |
| Netty 库 + 依赖 (Gson, SLF4J, log4j) | ~15MB | ~12MB |
| 线程栈 (68 threads × 1MB) | ~68MB off-heap | ~14MB off-heap |
| HttpObjectAggregator (16MB × 3) | 峰值 48MB | 峰值 12MB |
| GGUF reader buffer | 峰值 64MB | 0MB (off-heap map) |
| Console buffer | 2MB | 256KB |
| Netty pool | ~8MB | ~4MB |
| 模型元数据 (20+ 模型) | ~3MB | ~3MB |
| Gson 反射缓存 (×6) | ~500KB | ~80KB |
| 业务可用余量 | ~5MB | ~35MB |

**Heap 总计**: ~96-110MB（不含 off-heap thread stacks）  
**RSS 总计**: ~110-130MB（含 off-heap）

---

## 执行进度

- [x] 第1批：关键修复（~50-80MB 收益）
  - [x] 1. GGUFMetaDataReader MappedByteBuffer
  - [⏸] 2. HttpObjectAggregator 16→4MB — 暂缓，待确认
  - [x] 3. WebSocket max frame 64KB
  - [x] 4. Console buffer 256KB
- [x] 第2批：EventLoopGroup 线程优化（~10-20MB 收益）
  - [x] 5. 限制 worker 线程数 (4+2+2+2=10 workers)
- [ ] 第3批：请求处理优化（~5-15MB 收益）
  - [⏸] 6. 非流式响应流式转发 — 性价比低，见详细说明
  - [x] 7. ChatStreamSession 256KB
  - [✗] 8. Netty allocator 配置 — 已弃用，移除
- [ ] 第4批：杂项优化（~1-3MB 收益）
  - [x] 9. 统一 Gson 实例（12个文件 → JsonUtil.gson()）
  - [ ] 10. DownloadTaskManager 清理 — 见下方说明
  - [ ] 11. ConfigManager SoftReference — 见下方说明

## 额外修复

### [x] EasyChat 后端新增 conversation/save 端点 — `EasyChatController.java`

**问题**: `/api/easy-chat/sync` 每次同步都发送完整 `currentConversation`（含所有 messages 和 base64 图片），body 轻松超过 16MB 触发 413。

**后端方案**: 新增 `POST /api/easy-chat/conversation/save` 端点
- 前端修改会话后调用此端点保存（带 revision 校验）
- 之后 sync 请求 body 中不再携带 `currentConversation`
- sync body 从 MB 级降到 KB 级

**前端调用方式**:
1. `GET /api/easy-chat/state` — 获取状态摘要（不含消息正文）
2. `GET /api/easy-chat/conversation?id=xxx` — 加载单个会话完整内容
3. `POST /api/easy-chat/conversation/save` — 保存单个会话（body: `{baseRevision, conversation}`）
4. `POST /api/easy-chat/sync` — 同步状态摘要（body 中 `currentConversation` 可选，建议省略）

**状态**: ✅ 后端已完成，前端待改

### [⏸] EasyChat 前端 sync 逻辑改造 — `chat/index.html`

**现状**: `buildSyncPayload()`（第 2934 行）始终将 `currentConversation.messages` 全量放入 sync body。

**`persistState` 调用点分析**（共 28 处）：

**需要保存 messages 的场景（7 处）**:
- `pushMessage`（L3944）— 新增消息
- `createNewMessage`（L3960）— 创建新消息
- `updateMessage`（L4568）— 编辑消息
- tool call 消息（L4693）— 工具调用结果
- 消息操作后（L4740）— 删除/重新生成等
- `regenerateMessage`（L4798）— 重新生成回复
- `submitPrompt`（L5305）— **发送消息（含 base64 图片，最大来源）**

**不需要保存 messages 的场景（13+ 处）**:
- 切换隐私模式（L2073）、切换思维链（L2860）、保存设置（L4931）
- 切换模型（L5250, L6177）、新建对话（L5332）、清空对话（L5556）
- 删除会话（L5608）、切换助手（L5680）、创建助手（L5705）、删除助手（L5743）
- `pagehide` 关闭页签（L6388）、页面可见性变化（L6403）

**结论**: sync API 同时承担了"保存聊天记录"和"同步 UI 状态"两个职责。13+ 个设置变更场景每次发送完整 messages 是冗余的。

**前端方案**: `buildSyncPayload` 增加 `includeConversation` 参数：
- 消息变更时传 `{ includeConversation: true }` → 发完整 messages
- 设置变更时不传 → `currentConversation` 为 null，只同步 state 摘要
- 后端 `syncState` 已有 `if (currentConversation != null)` 判断，传 null 时跳过 conversation 文件更新

**注意**: 即使改造后，`submitPrompt` 发送含大图片的消息时仍会发送完整 messages（base64），413 根因是 HttpObjectAggregator 16MB 上限。需配合调大该限制或前端裁剪附件。

**状态**: 待执行，需前端配合

---

## 额外发现

- [x] **MtpHelper.parseWithGrowingBuffer 毒瘤** — `MtpHelper.java:543-589`
  - `ByteBuffer.allocate()` 从 8MB 开始翻倍直到 OOM
  - 已改为 `FileChannel.map()` off-heap 内存映射
  - 删除 `BufferParser` 接口和 `parseWithGrowingBuffer` 方法
  - 新增 `unmap()` 辅助方法
- [ ] **12. run.bat JVM 参数** — `javac-win.bat:122`
  - 当前生成 `-Xms512m -Xmx512m -XX:MaxDirectMemorySize=256m`
  - 必须改为 `-Xms64m -Xmx128m`，否则前面优化无效
  - 需同步检查 `WindowsTray.java` 等其他启动入口是否也有硬编码的 JVM 参数

## 待执行项详细说明

### 10. DownloadTaskManager 清理

**文件**: `src/main/java/org/mark/file/downloader/DownloadTaskManager.java:47-49`

**现状**:
```java
private final Map<String, DownloadTaskInfo> taskStore = new ConcurrentHashMap<>();
private final Map<String, RuntimeTaskContext> runtimeStore = new ConcurrentHashMap<>();
private final Map<String, DownloadProgressListener> listeners = new ConcurrentHashMap<>();
```

**问题**: 完成和失败的任务永久保留在 `taskStore` 中，`runtimeStore` 和 `listeners` 也不会清理。每个任务条目 ~1-2KB，长期运行后累积。

**方案**: 添加 `ScheduledExecutorService`，每 5 分钟清理一次超过 5 分钟前已完成/失败的任务及其关联的 runtime context 和 listener。

**收益**: 随运行时间增长，每个历史任务省 1-2KB。

### 11. ConfigManager SoftReference

**文件**: `src/main/java/org/mark/llamacpp/server/ConfigManager.java:42-43`

**现状**:
```java
private volatile List<Map<String, Object>> cachedModelsConfig = null;
private volatile long cachedModelsConfigLastModified = -1L;
```

**问题**: 整个 `models.json` 配置永久驻留内存。嵌套的 `Map<String, Object>` 结构开销较大（每个 HashMap 条目 ~64 字节 + key/value 对象）。

**方案**: 改为 `private volatile SoftReference<List<Map<String, Object>>> cachedModelsConfig = null;`。内存紧张时 GC 自动回收，下次读取时重新从磁盘加载。

**收益**: 内存压力下可回收 100-500KB。

---

## 验证方法

1. 启动参数加 `-Xmx128m -Xms64m`
2. 启动后访问 `/api/system/info` 查看 JVM 内存指标
3. 加载 3-5 个模型，观察 heap usage
4. 发起 3-5 个并发 chat 请求，观察 peak heap
5. `jmap -heap <pid>` 确认 heap 分配
6. `jstat -gc <pid> 1000` 观察 GC 频率
