# 碎片化聊天存储实施方案

## 目标

舍弃 `/v1/chat/completions` 端点，改为独立处理聊天对话。
将 JSON 聊天记录替换为二进制碎片文件，每条消息独立存储为一个文件，
实现流式磁盘读取构建请求体，最小化内存消耗。

## 碎片文件格式

```
Offset  Size   Content
0       16     文件头 (暂时 0xFF * 16)
16      8      时间戳 (epoch ms, long, little-endian)
24      8      序号 (long, little-endian, conversation 内全局递增)
32      128    预留
160     N      完整 JSON 消息对象字节 (UTF-8, String.getBytes())
```

Header 固定 160 字节。

payload 为完整 JSON 消息对象，包含 `role`, `content`, `tool_calls`(如有), `tool_call_id`(如有) 等字段，直接 `JSON.stringify()` 后写入。

## 索引文件格式

每个会话目录下的元数据文件，固定大小，记录会话基本信息和序号计数器。

```
Offset  Size   Content
0       16     文件头 (暂时 0xFF * 16)
16      4      会话名称长度 (int, little-endian, UTF-8 字节数)
20      4096   会话名称 (UTF-8, 超出部分空字节填充)
4116    8      开始时间 (epoch ms, long, little-endian)
4124    4      助手名称长度 (int, little-endian, UTF-8 字节数)
4128    4096   助手名称 (UTF-8, 用于查找系统提示词)
8224    8      序号计数器 (long, little-endian, 初始 0, 下一条消息的 seq)
```

总大小：8232 字节。

序号计数器使用方式：
- 新建会话时写入 0
- 每次写入消息前读取当前值作为 seq，写入后 +1 回写
- 通过序号从 0 开始循环查找 `0000000000000000.bin`, `0000000000000001.bin` ... 即可遍历所有碎片，无需目录扫描，避免外部文件干扰

## tools.bin 格式

纯文本文件，`String.getBytes(UTF-8)` 直接写入，无 header。内容示例：

```json
{"tools":[{"type":"function","function":{"name":"web_search","description":"...","parameters":{...}}},{"type":"function","function":{"name":"read_file","description":"...","parameters":{...}}}],"tool_choice":"auto"}
```

有工具配置时创建，无工具时不创建该文件。前端每次请求可携带最新 tools 配置，服务层覆盖写入。

## 目录结构

```
cache/easy-chat/
    state.json                          -- 现有状态文件，不变
    conversations/
        {storageKey}.json               -- 旧格式，废弃但保留
    fragments/
        {conversationId}/
            index.bin                   -- 索引文件（元数据 + 序号计数器）
            tools.bin                   -- 工具配置（纯文本 JSON，可选）
            0000000000000000.bin        -- 第1条消息
            0000000000000001.bin        -- 第2条消息
            0000000000000002.bin        -- ...
```

## 新建文件清单

| 文件 | 职责 |
|------|------|
| `service/FragmentFileUtil.java` | 碎片文件 + 索引文件 + tools.bin 的读写工具 |
| `service/StreamChatService.java` | 独立聊天服务：流式构建请求、转发、SSE、持久化 |
| 扩展 `controller/EasyChatController.java` | 新增 `/api/easy-chat/stream-chat` 端点 |

## 端点设计

**POST /api/easy-chat/stream-chat**

请求体：
```json
{
  "conversationId": "xxx",
  "modelId": "xxx",
  "message": { "role": "user", "content": "...", "images": [...], ... },
  "systemPrompt": "...",
  "samplingParams": { "temperature": 0.7, ... },
  "tools": [ { "type": "function", "function": { "name": "...", "description": "...", "parameters": {...} } } ],
  "toolChoice": "auto"
}
```

`tools` 和 `toolChoice` 为可选字段。有值时写入 `tools.bin`，流式构建请求体时追加到 llama.cpp 请求中。

响应：SSE 流式（与 llama.cpp 的 `/v1/chat/completions` 响应格式一致）

## 核心流程（StreamChatService）

```
1. 解析请求参数：conversationId, modelId, message, systemPrompt, samplingParams, tools, toolChoice
2. 获取碎片目录路径：cache/easy-chat/fragments/{conversationId}/
3. 初始化/读取索引文件：
   - 不存在则创建：写入 header, 会话名, 开始时间, 助手名, 序号=0
   - 存在则读取序号计数器 seq
4. 写入 tools.bin（如请求携带 tools）：
   - 直接 getBytes(UTF-8) 覆盖写入
5. 写入消息碎片：
   - 使用索引文件中的 seq 作为序号
   - 写入临时文件 → 原子重命名
   - 索引文件 seq + 1 回写
6. 流式构建请求体，转发到 llama.cpp：
   - 输出: {"model":"...","stream":true,"messages":[
   - 输出: system prompt message
   - 读取 tools.bin（如有）→ 记录 tools JSON 备用
   - 从 seq=0 开始循环读取碎片文件 → 提取 payload → 输出 ",{payload}"
     （文件不存在即停止，无需目录扫描）
   - 输出: ",{user_message}"]
   - 输出: tools + toolChoice（如 tools.bin 存在）
   - 输出: sampling params
   - 输出: }
7. 代理 llama.cpp 的 SSE 响应 → 客户端
8. 同时累积 AI 回复内容（含 tool_calls delta）
9. 流结束后，写入 AI 回复碎片：
   - 使用索引文件中的 seq 作为序号
   - 构建完整 assistant message 对象（含 tool_calls 字段，如有）
   - 写入碎片文件
   - 索引文件 seq + 1 回写
10. 更新 state.json 中的 conversation 摘要（messageCount++）
```

### Tool Call 处理（前端驱动，保持现有架构）

tool call 循环由前端 `completion-runtime.js` 编排，后端不感知多轮 tool loop：

```
前端 → stream-chat (user message) → AI 返回 tool_calls → SSE 透传给前端
  → 前端解析 tool_calls → POST /api/tools/execute → 获取工具结果
  → 前端再次调用 stream-chat (message.role = "tool", 携带 tool_call_id)
  → StreamChatService 写入 tool 碎片 → 流式请求 → AI 返回最终回复
```

- 助手消息带 `tool_calls` 时，payload 完整包含 `tool_calls` 数组，原样存储/读取
- 工具结果消息 `role: "tool"` 同样作为普通碎片写入，payload 包含 `tool_call_id`
- tools.bin 在每次请求时由前端携带最新配置，服务层覆盖写入

## 实施步骤

### Phase 1: 碎片文件 + 索引文件工具类 ✅

- [x] 创建 `service/FragmentFileUtil.java`
  - `write(Path dir, long seq, long timestamp, byte[] payload)` — 写入碎片
  - `readPayload(Path file)` — 跳过 160 字节 header，返回 payload
  - `listFragments(Path dir)` — 按序号排序返回所有碎片文件路径
  - `getNextSeq(Path dir)` — 返回下一个可用序号
  - 文件命名：`String.format("%016d.bin", seq)`
  - `createIndex(Path dir, String convName, long startTime, String assistantName)` — 创建索引文件
  - `readIndex(Path dir)` — 读取索引文件，返回 Index 对象（含 convName, startTime, assistantName, seq）
  - `incrementSeq(Path dir)` — 读取 seq, 返回当前值, 写回 seq+1
  - `writeTools(Path dir, byte[] toolsJson)` — 写入 tools.bin
  - `readTools(Path dir)` — 读取 tools.bin，不存在返回 null

### Phase 2: StreamChatService ✅

- [x] 创建 `service/StreamChatService.java`
  - 单例模式（参考 OpenAIService）
  - 虚拟线程池
  - `handleStreamChatRequest(ctx, request)` 主方法
  - 索引文件初始化/读取，序号计数器管理
  - tools.bin 写入/读取
  - 流式构建请求体：通过 OutputStream 拼接，不经过内存
  - 按序号循环读取碎片（文件不存在即停止），不再目录扫描
  - 请求体追加 tools + toolChoice（如 tools.bin 存在）
  - SSE 响应透传 + 累积 AI 内容（含 tool_calls delta）
  - 流结束后写入 AI 碎片文件（含 tool_calls，如有）
  - 更新 state.json 摘要

### Phase 3: Controller 端点 ✅

- [x] 扩展 `controller/EasyChatController.java`
  - 新增 `PATH_STREAM_CHAT = "/api/easy-chat/stream-chat"`
  - `handleRequest` 中增加路由判断
  - `handleStreamChatRequest()` 解析参数，委托给 StreamChatService

### Phase 4: 前端适配

- [ ] 修改 `web/chat/index.html` + `completion-runtime.js`
  - 新增端点常量 `STREAM_CHAT_ENDPOINT`
  - 发送消息改用新端点
  - 请求体格式调整（携带 tools, toolChoice）
  - SSE 响应处理
  - Tool call 循环：检测到 tool_calls 后，执行 `/api/tools/execute`，再调用 `stream-chat` 发送 tool 结果消息
  - tool 消息格式：`{ "role": "tool", "content": "...", "tool_call_id": "call_xxx" }`

### Phase 5: 测试与清理

- [ ] 功能测试：新建对话、多轮对话、大附件
- [ ] 性能验证：内存占用、磁盘 I/O
- [ ] 旧端点废弃标记

## 关键设计决策

1. **文件头 16 字节**：暂时全填 0xFF，后续可扩展 magic + 版本 + role 类型
2. **序号用 long**：8 字节，足够用
3. **附件 base64 内联**：保持现状，JSON payload 中存储
4. **System prompt 动态插入**：从助手配置读取，拼接时动态添加
5. **旧格式不迁移**：直接废弃，新会话使用新格式
6. **独立服务**：StreamChatService 独立于 OpenAIService，不混入现有逻辑
7. **索引文件**：每个会话一个 index.bin，固定 8232 字节，集中管理元数据和序号计数器
8. **序号循环读取碎片**：从 seq=0 递增查找文件，不存在即停止，避免目录扫描和外部文件干扰
9. **tools.bin 纯文本**：无 header，直接 getBytes 写入，有工具配置时创建
10. **Tool call 前端驱动**：保持现有架构，前端编排 tool loop，后端仅负责存储和透传

## 注意事项

- **编译环境**：项目要求 Java 21，当前开发环境只有 Java 8，需切换到 Java 21 环境编译
- **buildRequestBody**：当前实现将所有碎片 payload 读入 StringBuilder，对于大上下文场景可优化为真正的流式写入（直接写入 HttpURLConnection 的 OutputStream）
- **索引文件并发**：序号计数器的读写需注意并发安全，同一会话同时请求时需加锁
- **tools.bin 覆盖写入**：每次请求携带 tools 配置时覆盖写入，保证与前端状态一致
