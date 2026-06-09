# 碎片化聊天存储实施方案

## 目标

舍弃 `/v1/chat/completions` 端点，改为独立处理聊天对话。
将 JSON 聊天记录替换为二进制碎片文件，每条消息独立存储为一个文件，
实现流式磁盘读取构建请求体，最小化内存消耗。

## 碎片文件格式

### Header（160 字节）

```
Offset  Size   Content
0       16     文件头 (暂时 0xFF * 16)
16       8     时间戳 (epoch ms, long, little-endian)
24       8     序号/消息ID (long, little-endian, conversation 内全局递增)
32       2     消息数量 (short, little-endian, 用户消息=1, AI回复=1~10)
34      40     消息长度表 [10 × uint32, little-endian]，每条 payload 字节数
74      86     预留
```

### 文件体

```
[header 160B][payload#0 N0 bytes][payload#1 N1 bytes][payload#2 N2 bytes]...
```

各 payload 的偏移通过长度表累加计算：`offset_i = 160 + Σ(length_0 ... length_{i-1})`

payload 为完整 JSON 消息对象，包含 `role`, `content`, `tool_calls`(如有), `tool_call_id`(如有) 等字段，直接 `JSON.stringify()` 后写入。

### 多变体存储

一个碎片文件可存储多条 AI 回复变体（最多 10 条），用于"重新生成"功能：

| 消息类型 | count | 说明 |
|---------|-------|------|
| 用户消息 | 1 | 始终单条 |
| AI 回复 | 1~10 | 正常生成 count=1，重新生成追加变体 |
| Tool 结果 | 1 | 始终单条 |

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

## 现有文件

| 文件 | 职责 |
|------|------|
| `service/EasyChatService.java` | 碎片文件 + 索引文件 + tools.bin 读写 + 流式构建请求、转发、SSE、持久化（一体化实现） |
| 扩展 `controller/EasyChatController.java` | 新增 `/api/easy-chat/stream-chat` 端点 |

## 端点设计

### POST /api/easy-chat/stream-chat（发送消息）

为避免 Body 中 JSON 消息对象（可能含 base64 图片等大附件）在内存中展开解析，元数据全部通过 HTTP Header 传递，Body 只放纯 message JSON 字节流，直接写入碎片文件，不经过 Gson 解析。

#### 请求头（Headers）

| Header | 必填 | 类型 | 说明 |
|--------|------|------|------|
| `X-Conversation-Id` | 是 | string | 会话唯一ID |
| `X-Model-Id` | 是 | string | 模型ID或别名 |
| `X-Assistant-Name` | 否 | string | 助手名称，用于查找角色配置中的 system prompt |
| `X-Sampling-Params` | 否 | JSON string | 采样参数，如 `{"temperature":0.7}`，体积极小，安全解析 |
| `X-Tools` | 否 | JSON string | 工具数组，如 `[{"type":"function",...}]` |
| `X-Tool-Choice` | 否 | string | 工具选择策略，如 `auto`、`required`、`none` |
| `X-Regenerate-Id` | 否 | long | 重新生成指定 seq 的 AI 消息，Body 可为空 |
| `X-Variants` | 否 | string | 变体选择，格式 `seq:variantIdx,seq:variantIdx`（如 `1:0,3:1`） |

#### 请求体（Body）

**正常发送**：原始 message JSON 对象字节。即 `Content-Type: application/json` 下的字节内容，**后端不解析**，直接写入碎片文件 payload。示例：

```json
{"role":"user","content":"Hello world","images":["data:image/jpeg;base64,/9j/..."]}
```

**重新生成**：Body 为空（`null`）。后端根据 `X-Regenerate-Id` 找到目标 AI 消息，向前查找对应的用户消息碎片，读取 payload 构建请求上下文。

#### 响应

SSE 流式（与 llama.cpp 的 `/v1/chat/completions` 响应格式一致）

#### 示例请求

```http
POST /api/easy-chat/stream-chat HTTP/1.1
Content-Type: application/json
X-Conversation-Id: conv_abc123
X-Model-Id: llama3-8b
X-Assistant-Name: 通用助手
X-Sampling-Params: {"temperature":0.7,"top_p":0.9}
X-Tools: [{"type":"function","function":{"name":"web_search","parameters":{}}}]
X-Tool-Choice: auto

{"role":"user","content":"今天天气怎么样？"}
```

---

### GET /api/easy-chat/stream-chat?conversationId=xxx（获取历史）

零拷贝流式返回会话全部历史消息。payload 字节通过 `DefaultFileRegion`（底层 `sendfile()`）直接从磁盘 DMA 到 socket，**不进 JVM 堆**。

#### 查询参数

| 参数 | 必填 | 说明 |
|------|------|------|
| `conversationId` | 是 | 会话唯一ID |

#### 响应格式

`Content-Type: application/json`，HTTP Chunked Transfer-Encoding。

所有消息内容统一放在 `variants` 数组中，每条变体的 `content` 字段存储原始 payload 字节（零拷贝 `DefaultFileRegion` 输出）：

```json
{
  "message": "success",
  "totalSize": 12345,
  "recordCount": 4,
  "variantCount": 2,
  "data": [
    {
      "seq": 0,
      "role": "user",
      "variants": [{"content": {"role":"user","content":"你好"}}]
    },
    {
      "seq": 1,
      "role": "assistant",
      "variants": [
        {"content": {"role":"assistant","content":"回复v1"}},
        {"content": {"role":"assistant","content":"回复v2"}},
        {"content": {"role":"assistant","content":"回复v3"}}
      ]
    }
  ]
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `message` | string | 成功时 `"success"`，失败时是具体错误信息 |
| `totalSize` | number | 所有碎片 payload 累计字节数（不含 160B header） |
| `recordCount` | number | 碎片总数（= 消息条数） |
| `variantCount` | number | 额外变体总数（= 总变体数 - recordCount） |
| `data` | array | 消息对象数组，按 seq 顺序排列 |
| `data[].seq` | number | 消息序号，也作为消息ID |
| `data[].variants` | array | 所有变体数组，`variants[i].content` 为原始 payload JSON 对象 |

**错误时** HTTP 状态码仍为 200，通过 `message` 字段表示错误：

```json
{"message":"扫描碎片失败: xxx","totalSize":0,"recordCount":0,"variantCount":0,"data":[]}
```

#### 示例请求

```http
GET /api/easy-chat/stream-chat?conversationId=conv_abc123 HTTP/1.1
```

#### 零拷贝实现原理

```
后端处理分三阶段：

Phase 1 — 预扫描（读 header count/lengths，不读 payload 内容）：
  for seq=0... {
      count = readFragmentCount(convDir, seq)
      for v=0..count-1: totalSize += readFragmentLength(convDir, seq, v)
      if (v > 0) variantCount++
      recordCount++
  }
  → 得到 totalSize, recordCount, variantCount

Phase 2 — 写 HTTP 响应头 + JSON 前缀（微小 ByteBuf）：
  ctx.writeAndFlush(response)
  ctx.writeAndFlush(Unpooled.wrappedBuffer("{\"message\":\"success\",...\"data\":["))

Phase 3 — 零拷贝循环（writeAndFlush 保证字节顺序）：
  for seq=0... {
      if (seq > 0) ctx.writeAndFlush(Unpooled.wrappedBuffer(","))
      ctx.writeAndFlush("{\"seq\":N,\"role\":\"R\",\"variants\":[")
      for v=0..count-1 {
          if (v > 0) ctx.writeAndFlush(",")
          ctx.writeAndFlush("{\"content\":")
          ctx.writeAndFlush(new DefaultFileRegion(file, variantOffset, vLen))
          ctx.writeAndFlush("}")
      }
      ctx.writeAndFlush("]}")  // ] close variants, } close msg
  }
  ctx.writeAndFlush("]}")   // ] close data, } close root
  ctx.writeAndFlush(LastHttpContent).addListener(CLOSE)

⚠ 关键修复：DefaultFileRegion 是异步 sendfile 传输，和 ByteBuf 混合 ctx.write() 时
到达 socket 的顺序不可控，导致 JSON 损坏。改为每个 write 后立即 writeAndFlush，
保证字节按顺序到达。代价是每次多一次系统调用，但历史查询为低频操作，可接受。

仅 {, [, ,, ], } 等分隔符进入 JVM 堆（几十字节）。
payload 字节全程由内核态 DMA 完成，不进用户态内存。
```

## 核心流程（EasyChatService）

### 正常发送消息

```
1. 解析请求 Header：
   - conversationId (X-Conversation-Id, 必填)
   - modelId (X-Model-Id, 必填)
   - assistantName (X-Assistant-Name, 可选)
   - samplingParams (X-Sampling-Params, 可选 JSON 字符串)
   - tools (X-Tools, 可选 JSON 字符串)
   - toolChoice (X-Tool-Choice, 可选字符串)
2. 读取 Body 原始字节：直接 request.content() 拿到 byte[]，不解析为 JsonObject
3. 验证：conversationId 非空、modelId 非空、Body 字节非空
4. 解析小字段：X-Tools → JsonArray（用 Gson），X-Sampling-Params → JsonObject（用 Gson），X-Tool-Choice → String
   （这些字段体积极小，无内存风险）
5. 校验模型：通过 LlamaServerManager 查找 modelId，获取端口
6. 解析 system prompt：用 assistantName 从角色配置中查找 system prompt
7. 获取碎片目录路径：cache/easy-chat/fragments/{conversationId}/
8. 加锁（per-conversation）：
   a. 初始化/读取索引文件：
      - 不存在则创建：写入 header, 会话名, 开始时间, 助手名, 序号=0
      - 存在则读取序号计数器 seq
   b. 写入 tools.bin（如有 tools）：
      - 构建 `{"tools":[...],"tool_choice":"auto"}` → getBytes(UTF-8) 覆盖写入
   c. 写入用户消息碎片：
      - 使用索引文件中的 seq 作为序号
      - Body 原始字节直接作为 payload 写入（不经过 Gson）
      - header 写入 count=1, lengths[0]=bodyBytes.length
      - 写入临时文件 → 原子重命名
   d. 序号计数器预分配：seq → seq+2（用户消息 + AI 回复各占一位）
 9. 异步执行（worker 线程）：
    a. 建立到 llama.cpp 的 HTTP 连接
    b. 流式构建请求体（writeRequestBody）：
       - 输出: {"model":"...","stream":true,"messages":[
       - 输出: system prompt message（如有 assistantName）
       - 从 seq=0 开始循环读取碎片文件 → 提取 payload（variant 0）→ 输出 ",{payload}"
         （文件不存在即停止。刚写入的用户消息碎片 seq=N 会被读回，即 messages 数组
          包含全部历史 + 当前用户消息，无需额外拼接）
       - 输出: ]
       - 输出: tools + toolChoice（如 tools.bin 存在）
       - 输出: sampling params（应用 ModelSamplingService 处理）
       - 输出: }
    c. 代理 llama.cpp 的 SSE 响应 → 客户端
    d. 同时累积 AI 回复内容（含 tool_calls delta）
    e. 流结束后，写入 AI 回复碎片：
       - 构建完整 assistant message 对象（含 tool_calls 字段，如有）
       - writeFragment() 写入碎片文件（count=1）
       - 索引文件 seq 同步
    f. 更新 state.json 中的 conversation 摘要（messageCount+=2）
```

### 重新生成 AI 回复

```
1. 解析 X-Regenerate-Id → regenerateSeq（目标 AI 消息的 seq）
2. 解析 X-Variants → Map<seq, variantIdx>（历史 AI 消息的变体选择）
3. Body 为空（后端从碎片文件读取用户消息）
4. 加锁（per-conversation）：
   a. 不写入用户碎片（已存在）
   b. aiSeq = regenerateSeq（复用已有 AI 碎片的 seq）
   c. 读取/复用 tools.bin
5. 异步执行：
    a. writeRequestBody(convDir, variants, regenerateSeq)：
       - 从 seq=0 遍历碎片
       - AI 消息按 variants 指定取对应变体（readFragmentPayload(dir, seq, variantIdx)）
       - 遇到 seq == regenerateSeq 时停止（不包含被重新生成的旧 AI 消息）
    b. 代理 SSE 响应 → 客户端
    c. 流结束后，appendFragment(convDir, aiSeq, aiBytes)：
       - 读取现有 header 的 count
       - count++，lengths[count-1] = newLen
       - 文件末尾追加 payload
       - 不增加 seq 计数器（复用 seq）
    d. 不更新 state.json（messageCount 不变）
```

### Tool Call 处理（前端驱动，保持现有架构）

tool call 循环由前端 `completion-runtime.js` 编排，后端不感知多轮 tool loop：

```
前端 → stream-chat (Body: {"role":"user",...}) → AI 返回 tool_calls → SSE 透传给前端
  → 前端解析 tool_calls → POST /api/tools/execute → 获取工具结果
  → 前端再次调用 stream-chat (Body: {"role":"tool","content":"...","tool_call_id":"call_xxx"})
  → EasyChatService 写入 tool 碎片 → 流式请求 → AI 返回最终回复
```

- 助手消息带 `tool_calls` 时，payload 完整包含 `tool_calls` 数组，原样存储/读取
- 工具结果消息 `role: "tool"` 同样作为普通碎片写入，payload 包含 `tool_call_id`
- tools.bin 在每次请求时由前端携带最新配置（Header `X-Tools`），服务层覆盖写入

---

## 后端处理流程详解

### A. POST 发送消息 — `EasyChatService.handleStreamChat()`

#### A.1 同步阶段（Netty I/O 线程，持 per-conversation 锁）

```
handleStreamChat(ctx, request)
│
├─ 1. 读 Headers
│   X-Conversation-Id   → conversationId
│   X-Model-Id          → modelId
│   X-Assistant-Name    → assistantName（可选）
│   X-Tools             → toolsArr (JsonArray)
│   X-Tool-Choice       → toolChoice（默认 "auto"）
│   X-Sampling-Params   → samplingParams (JsonObject)
│   X-Regenerate-Id     → regenerateSeq（可选，有值则为重新生成模式）
│   X-Variants          → variants Map<seq, variantIdx>（可选）
│
├─ 2. 读 Body
│   byte[] bodyBytes = request.content().readBytes()
│   正常模式：不做 JSON 解析，原始字节直达磁盘
│   重新生成模式：Body 为空，后端从碎片文件读取用户消息
│
├─ 3. 校验模型 + 解析 systemPrompt
│   LlamaServerManager 查找 modelId → modelPort
│   findCharactorByTitle(assistantName) → systemPrompt
│
├─ 4. convLock.lock()
│   ├─ 初始化/校验 index.bin
│   │   !exists         → createIndex(8232B)
│   │   size ≠ 8232     → 删除重建
│   │
│   ├─ if (isRegenerate):
│   │   userSeq = -1（不写用户碎片）
│   │   aiSeq = regenerateSeq（复用已有 AI 碎片 seq）
│   │   else:
│   │   readIndex() → idx.seq → userSeq=seq, aiSeq=seq+1
│   │   writeSeq(seq+2)  ← 预分配两位
│   │   writeFragment(convDir, userSeq, timestamp, bodyBytes)
│   │       header: count=1, lengths[0]=bodyBytes.length
│   │       temp 文件 → 原子重命名
│   │
│   └─ toolsArr != null → writeTools(convDir, toolsJson)
│       toolsArr == null → readTools(convDir)  ← 复用上次的
│
├─ 5. convLock.unlock()
│
└─ 6. worker.execute(() → 异步阶段)
```

#### A.2 异步阶段（虚拟线程）

```
worker 线程
│
├─ 1. openTrackedConnection() → HttpURLConnection → llama.cpp
│
├─ 2. writeRequestBody(conn, modelId, systemPrompt, convDir, toolsBytes,
│       samplingParams, variants, regenerateSeq)
│   StringBuilder 拼 JSON:
│     {"model":"Qwen3","stream":true,"messages":[
│     {"role":"system","content":"..."},           ← systemPrompt（如有）
│     {"role":"user","content":"历史消息1"},        ← 碎片 seq=0 payload
│     {"role":"assistant","content":"回复1"},       ← 碎片 seq=1 payload（按 variants 取指定变体）
│     ...
│     （重新生成模式：遇到 seq==regenerateSeq 时停止，不包含旧 AI 消息）
│     ]
│     ,{"tools":[...],"tool_choice":"auto"}         ← tools（如有）
│     ,"temperature":0.7,...}                       ← samplingParams（如有）
│
├─ 3. connection.getResponseCode()
│   !2xx → readErrorBody → sendErrorResponse → return
│
├─ 4. 建 SSE 响应 → ctx.write(sseResp) → ctx.flush()
│
├─ 5. proxySseStream(ctx, connection, modelId, accumulator)
│   BufferedReader 逐行读:
│     "data: {...}" → JSON.parse → accumulateDelta() → 累积 content/tool_calls
│     非 data 行 → 直接 writeSseLine() 透传
│     "data: [DONE]" → 结束
│   每行: ctx.writeAndFlush(DefaultHttpContent("\r\n"))
│
├─ 6. convLock.lock()
│   ├─ accumulator.hasContent() → buildAiMessage()
│   │   构建 {"role":"assistant","content":"...","tool_calls":[...]}
│   │   if (isRegenerate):
│   │     appendFragment(convDir, aiSeq, jsonBytes)  ← 追加变体到现有碎片
│   │   else:
│   │     writeFragment(convDir, aiSeq, timestamp, jsonBytes)
│   │     writeSeq(aiSeq + 1)
│   └─ updateStateMessageCount(conversationId, 2)（仅正常模式）
│
└─ 7. ctx.writeAndFlush(LastHttpContent).addListener(CLOSE)
```

**内存特征：**
- 用户消息 Body 字节 → 不解析，直接写入 `writeFragment` 的 `ch.write(ByteBuffer.wrap(bodyBytes))`
- 历史碎片 payload → `readFragmentPayload` 暂存到 `byte[]`，然后 `new String(bytes, UTF_8)` 拼入 `StringBuilder`
- `StringBuilder` 拼接完整请求体是当前内存瓶颈（备注中有优化方向）
- SSE 行处理仅累积 `content` 和 `tool_calls`，不保存完整响应 JSON
- AI 回复写入时 `JsonUtil.toJson(aiMsg).getBytes()` 创建临时 byte[]

---

### B. GET 获取历史 — `EasyChatService.handleStreamChatHistory()`

```
handleStreamChatHistory(ctx, conversationId)
│
├─ 1. 校验 conversationId 非空
│
├─ 2. getFragmentsDir() → convDir
│
├─ 3. Phase 1 — 预扫描（读 header count/lengths，不读 payload 内容）
│   for seq=0... {
│       file = fragmentFile(convDir, seq)
│       !isRegularFile → break
│       count = readFragmentCount(convDir, seq)  ← 读 header offset 32 的 short
│       for v=0..count-1:
│           len = readFragmentLength(convDir, seq, v)  ← 读 lengths 表
│           totalSize += len
│           if (v > 0) variantCount++
│       recordCount++
│   }
│
├─ 4. Phase 2 — 写 HTTP 响应头 + JSON 前缀
│   ctx.writeAndFlush(response)       ← Content-Type: application/json, chunked
│   ctx.writeAndFlush("{\"message\":\"success\",\"totalSize\":123,\"recordCount\":2,\"variantCount\":1,\"data\":[")
│
├─ 5. Phase 3 — 零拷贝循环（writeAndFlush 保证字节顺序）
│   for seq=0... {
│       file = fragmentFile(convDir, seq)
│       !isRegularFile → break
│       count = readFragmentCount(convDir, seq)
│       role = parseRole(readFragmentPayload(convDir, seq, 0))
│       if (seq > 0) ctx.writeAndFlush(",")
│       ctx.writeAndFlush("{\"seq\":N,\"role\":\"R\",\"variants\":[")
│       variantOffset = 160
│       for v=0..count-1 {
│           vLen = readFragmentLength(convDir, seq, v)
│           if (v > 0) ctx.writeAndFlush(",")
│           ctx.writeAndFlush("{\"content\":")
│           ctx.writeAndFlush(new DefaultFileRegion(file, variantOffset, vLen))
│           ctx.writeAndFlush("}")
│           variantOffset += vLen
│       }
│       ctx.writeAndFlush("]}")  // ] close variants, } close msg
│   }
│
├─ 6. ctx.writeAndFlush("]}")  ← ] close data, } close root
│
└─ 7. ctx.writeAndFlush(LastHttpContent).addListener(CLOSE)
```

**内存特征：**
- payload 字节全程零拷贝：`DefaultFileRegion.transferTo()` → `FileChannel.transferTo()` → `sendfile()` → 内核态 DMA 直接到 socket
- 进入 JVM 堆的仅：JSON 分隔符（`,`, `{`, `}`, `[`, `]`）+ 每条消息的 role 提取（需读第 0 变体 payload 到内存解析 role）
- 每次 writeAndFlush 多一次系统调用，但历史查询为低频操作，可接受
- 错误路径走 `sendHistoryError()`：构造完整 JSON → `DefaultFullHttpResponse` 一次性返回

### C. ByteBuffer flip() 陷阱

Java NIO 的 `ByteBuffer.allocate(N)` 创建 buffer 后：
- `position=0, limit=N, capacity=N`

连续 `put*()` 操作推进 `position`。调用 `flip()` 时：
- `limit = position`（当前写入位置）
- `position = 0`

**如果 `position` 未推到 `capacity` 就 flip，只有部分数据被写出。**

修复方式：在 `flip()` 前调用 `buf.position(CAPACITY)` 将 position 强制推进到 buffer 末尾，
剩余空间由 `allocate()` 的零初始化填充。这样 `limit` 才会等于 `CAPACITY`。

```
❌ 错误                            ✅ 正确
buf.putInt(len);                  buf.putInt(len);
buf.put(nameBytes);               buf.put(nameBytes);
buf.putLong(timestamp);           buf.putLong(timestamp);
// position ≈ 40                  // position ≈ 40
buf.flip();                       buf.position(CAPACITY);  // position = 8232
// limit = 40 → 只写 40B          buf.flip();
ch.write(buf);  // BUG           // limit = 8232 → 写满 8232B
                                   ch.write(buf);  // OK
```

受影响的代码位置：
- `createIndex()`: position 推进到 `INDEX_FILE_SIZE` (8232) → `EasyChatService.java:391`
- `writeFragment()`: position 推进到 `FRAG_HEADER_SIZE` (160) → `EasyChatService.java:468`

---

## 前端处理流程详解（stream-test.html）

### 测试页面

文件：`src/main/resources/web/chat/stream-test.html`

浏览器访问：`http://host/chat/stream-test.html`

### 页面状态管理

| 机制 | 说明 |
|------|------|
| `localStorage` key `streamTestConvId` | 持久化 conversationId，刷新页面复用同一会话 |
| 页面加载时 | 读取 localStorage，不存在则 `crypto.randomUUID()` 生成并存入 |
| 「+」按钮 | 清空 localStorage + 重新生成 UUID + 清屏 → 全新对话 |

### 初始化流程

```
页面加载
│
├─ 1. 获取/生成 conversationId
│   CONVERSATION_ID = localStorage.streamTestConvId ?? crypto.randomUUID()
│
├─ 2. loadModels()
│   GET /v1/models → json.data[].id → 填入 <select>
│
└─ 3. loadHistory()
    GET /api/easy-chat/stream-chat?conversationId=xxx
    → resp.json()  ← 标准 JSON 解析（Chunked Transfer 自动聚合）
    → json.message === "success" → json.data.forEach(renderHistoryMessage)
    → json.message !== "success" → 显示错误信息
```

### 发送消息流程

```
用户输入消息 → 按 Enter 或点击发送
│
├─ 1. 立即在 UI 显示用户消息（addUserMsg）
│
├─ 2. 创建 assistant 气泡（createAssistantBubble，带闪烁光标）
│
├─ 3. fetch(POST /api/easy-chat/stream-chat)
│   Headers:
│     Content-Type: application/json
│     X-Conversation-Id: CONVERSATION_ID
│     X-Model-Id: selectedModel
│   Body: JSON.stringify({role:"user", content:text})
│
├─ 4. 读 SSE 流（ReadableStream）
│   resp.body.getReader() → 按 \r\n 分割行 → 取 data: 前缀
│   JSON.parse(data) → choices[0].delta.content → 追加到气泡
│   data: [DONE] → 结束
│
└─ 5. 移除光标，恢复输入状态
```

### 历史消息渲染

`renderHistoryMessage(msg, variantIdx)` 从 `msg.variants[variantIdx].content` 读取 payload：

| role | 样式 | 显示内容 |
|------|------|---------|
| `user` | 蓝色右对齐 | content |
| `assistant` | 绿色左对齐 | reasoning_content（灰色斜体）+ content + tool_calls + **↻ 重新生成按钮** + **v1/N 变体切换器** |
| `tool` | 紫色左对齐 | content |
| 其他 | 灰色居中 | `[role] content` |

### 重新生成流程（前端）

```
用户点击 AI 消息气泡上的 ↻ 按钮
│
├─ 1. 收集 X-Variants：遍历所有 .msg.assistant 元素
│   读取 data-seq 和 data-variant 属性
│   构建 "seq:variantIdx,seq:variantIdx" 格式字符串
│
├─ 2. fetch(POST /api/easy-chat/stream-chat)
│   Headers:
│     X-Conversation-Id: CONVERSATION_ID
│     X-Model-Id: selectedModel
│     X-Regenerate-Id: targetSeq
│     X-Variants: "1:0,3:1"
│   Body: null（空）
│
├─ 3. 读 SSE 流，临时 assistant 气泡显示新变体
│
└─ 4. 流结束 → 删除临时气泡 → loadHistory(seq) 刷新全部消息
    → 新变体追加到目标 AI 碎片的 variants 数组
    → 消息气泡上出现 v1/N 切换器
```

### 变体切换

```
用户点击 v1/N 切换器
│
├─ variantIdx = (current + 1) % variants.length
├─ variantIdx = 0 → loadHistory() 刷新（回原变体）
├─ variantIdx > 0 → 从 VARIANTS_CACHE 读取，直接更新 DOM
└─ 更新 data-variant 属性和 v1/N 显示
```

### 变体缓存

`VARIANTS_CACHE = new Map()` 缓存 seq → variants 数组
- `loadHistory()` 时填充：`VARIANTS_CACHE.set(msg.seq, msg.variants)`
- `switchVariant()` 时读取：`VARIANTS_CACHE.get(seq)[variantIdx].content`

### 单文件架构

- 无外部 JS/CSS 依赖（`fetch`、`ReadableStream`、`crypto.randomUUID` 均为浏览器原生 API）
- 同源部署，无需处理 CORS
- 不依赖 `EventSource`（不支持 POST + 自定义 Header），改用 `fetch` + `ReadableStream` 手动解析 SSE

## 实施步骤

### Phase 1: 碎片文件 + 索引文件工具类 ✅

- [x] 在 `EasyChatService.java` 中实现碎片与索引读写
  - `writeFragment(Path dir, long seq, long timestamp, byte[] payload)` — 写入碎片（临时文件 + 原子重命名，header 含 count=1 + lengths[0]）
  - `appendFragment(Path dir, long seq, byte[] payload)` — 追加变体到现有碎片（count++，更新 lengths 表，文件末尾追加 payload）
  - `readFragmentPayload(Path dir, long seq)` — 读取第 0 变体 payload
  - `readFragmentPayload(Path dir, long seq, int variantIndex)` — 按变体索引读取 payload
  - `readFragmentCount(Path dir, long seq)` — 读取消息数量（header offset 32）
  - `readFragmentLengths(Path dir, long seq)` — 读取完整长度表（10 × uint32）
  - `readFragmentLength(Path dir, long seq, int variantIndex)` — 读取单个变体长度
  - `readFragmentInfo(Path dir, long seq)` — 读取 count 对应的实际长度数组
  - `fragmentExists(Path dir, long seq)` — 检查碎片文件是否存在
  - 文件命名：`String.format("%016d.bin", seq)`
  - `createIndex(Path indexPath, String convName, long startTime, String assistantName)` — 创建索引文件（8232 字节）
  - `readIndex(Path indexPath)` — 读取索引文件，返回 Index 对象（含 convName, startTime, assistantName, seq）
  - `writeSeq(Path indexPath, long seq)` — 更新序号计数器（尾部 8 字节）
  - `writeTools(Path dir, byte[] toolsJson)` — 写入 tools.bin
  - `readTools(Path dir)` — 读取 tools.bin，不存在返回 null

### Phase 2: 流式聊天服务 ✅

- [x] 在 `EasyChatService.java` 中实现流式聊天处理
  - 单例模式（getInstance()）
  - 虚拟线程池
  - `handleStreamChat(ctx, request)` 主方法
  - **Header 解析**：从 HTTP Header 读取 conversationId、modelId、assistantName、tools、toolChoice、samplingParams、**regenerateSeq**、**variants**
  - **Body 免解析**：正常模式原始字节直接写入碎片文件；重新生成模式 Body 为空
  - 索引文件初始化/读取，序号计数器管理
  - 重新生成分支：不写用户碎片，复用 AI 碎片 seq，appendFragment 追加变体
  - tools.bin 写入/读取
  - `writeRequestBody()`：支持 variants 参数（按指定变体读取）、regenerateSeq 参数（截断点）
  - 按序号循环读取碎片（文件不存在即停止），不再目录扫描
  - 请求体追加 tools + toolChoice（如 tools.bin 存在）
  - SSE 响应透传 + 累积 AI 内容（含 tool_calls delta）
  - 流结束后写入/追加 AI 碎片文件
  - 更新 state.json 摘要（仅正常模式）
  - `handleStreamChatHistory()`：零拷贝返回所有变体，variants 数组统一格式

### Phase 3: Controller 端点 ✅

- [x] 扩展 `controller/EasyChatController.java`
  - 新增 `PATH_STREAM_CHAT = "/api/easy-chat/stream-chat"`
  - `handleRequest` 中增加路由判断
  - `handleStreamChatRequest()` 委托给 EasyChatService

### Phase 4: 前端适配

- [x] 创建 `web/chat/stream-test.html` 测试页面（单文件，零外部依赖）
  - GET `/v1/models` 加载模型列表下拉选单
  - GET `/api/easy-chat/stream-chat?conversationId=xxx` 加载历史（零拷贝 JSON + variants 数组）
  - POST `/api/easy-chat/stream-chat` + Headers 发送消息（SSE 流式接收）
  - `localStorage` 持久化 conversationId，刷新页面复用会话
  - 「+」按钮创建新对话
  - 支持渲染 user/assistant/tool 三种消息角色
  - **重新生成**：每条 AI 消息气泡右侧 `↻ 重新生成` 按钮
  - **变体切换**：`v1/N` 切换器，点击循环切换变体
  - **变体缓存**：`VARIANTS_CACHE` Map 缓存 seq → variants 数组
  - **content 格式兼容**：支持 payload 对象（`{role, content, ...}`）和字符串两种格式
- [ ] 修改 `web/chat/index.html` + `completion-runtime.js`
  - 新增端点常量 `STREAM_CHAT_ENDPOINT`
  - 发送消息改用新端点
  - 请求格式调整：
    - 设置 HTTP Headers：`X-Conversation-Id`、`X-Model-Id`、`X-Assistant-Name`、`X-Sampling-Params`（JSON.stringify）、`X-Tools`（JSON.stringify）、`X-Tool-Choice`
    - 请求 Body 只放 `JSON.stringify(message)` 结果
  - SSE 响应处理
  - Tool call 循环：检测到 tool_calls 后，执行 `/api/tools/execute`，再调用 `stream-chat` 发送 tool 结果消息
  - tool 消息 Body 格式：`{"role":"tool","content":"...","tool_call_id":"call_xxx"}`

### Phase 5: 测试与清理

- [x] 功能测试：新建对话、多轮对话（stream-test.html）
- [x] 重新生成功能测试：点击 ↻ 按钮 → SSE 流式显示 → 变体切换 v1/N
- [x] 历史返回格式验证：variants 数组结构正确，JSON 解析无误
- [x] DefaultFileRegion 顺序修复验证：writeAndFlush 保证 JSON 完整
- [ ] 性能验证：内存占用、磁盘 I/O
- [ ] 旧端点废弃标记
- [ ] 添加更多前端功能（附件图片、语音等）

## 关键设计决策

1. **文件头 16 字节**：暂时全填 0xFF，后续可扩展 magic + 版本 + role 类型
2. **序号用 long**：8 字节，足够用
3. **附件 base64 内联**：保持现状，JSON payload 中存储
4. **System prompt 动态插入**：从助手配置查找，请求 Header `X-Assistant-Name` 指定助手名，拼接时动态添加
5. **旧格式不迁移**：直接废弃，新会话使用新格式
6. **一体化服务**：所有碎片读写在 `EasyChatService.java` 中实现，不拆分为 FragmentFileUtil + StreamChatService 两个文件
7. **索引文件**：每个会话一个 index.bin，固定 8232 字节，集中管理元数据和序号计数器
8. **序号循环读取碎片**：从 seq=0 递增查找文件，不存在即停止，避免目录扫描和外部文件干扰
9. **tools.bin 纯文本**：无 header，直接 getBytes 写入，有工具配置时创建
10. **Tool call 前端驱动**：保持现有架构，前端编排 tool loop，后端仅负责存储和透传
11. **Header 传元数据**：conversationId、modelId、assistantName、tools、toolChoice、samplingParams 全部通过 HTTP Header 传递，Body 只含 message JSON 原始字节，后端不解析 Body，直接写入碎片文件。仅对体积极小的 tools/samplingParams header 做 Gson 解析
12. **历史记录零拷贝**：`GET /api/easy-chat/stream-chat?conversationId=xxx` 通过 `DefaultFileRegion` + `sendfile()` 系统调用直接 DMA 传输碎片 payload，不进入 JVM 堆。JSON 分隔符以微小 ByteBuf 输出。**每个 write 后立即 writeAndFlush**，保证 DefaultFileRegion 异步传输的字节顺序正确
13. **消息粒度为单条**：每个碎片文件 = 1 条消息（user/assistant/tool），一轮对话 = 2 个文件。全局 seq 递增，通过 seq 循环即可遍历整个对话历史
14. **碎片文件多变体**：一个碎片文件最多存储 10 条 AI 回复变体。header offset 32 存 count（short），offset 34 存长度表（10 × uint32）。用户消息 count 始终为 1
15. **重新生成前端驱动**：前端选择目标 AI 消息 seq，携带 X-Regenerate-Id + X-Variants 发起请求。后端从碎片文件读取用户消息，按 variants 指定构建请求上下文，新变体追加到现有 AI 碎片文件
16. **历史返回统一 variants 数组**：所有消息（user/assistant/tool）的内容都放在 `variants` 数组中，`variants[i].content` 存储原始 payload JSON 对象。前端统一从 `msg.variants[variantIdx].content` 读取
17. **DefaultFileRegion 顺序修复**：`ctx.write()` 混合 `ByteBuf` + `DefaultFileRegion` 时，FileRegion 的异步 sendfile 可能导致字节到达顺序错乱。修复方案：每个 write 后立即 `writeAndFlush()`，以性能换正确性（历史查询为低频操作）

## 注意事项

- **编译环境**：项目要求 Java 21，编译命令：`javac -cp "lib/*" --source-path src/main/java -d target/classes --release 21`
- **buildRequestBody**：当前实现使用 StringBuilder 拼接，对于大上下文场景可优化为真正的流式写入（直接写入 HttpURLConnection 的 OutputStream）
- **ByteBuffer flip() 陷阱**：`ByteBuffer.allocate(N)` 后连续 `put*()` 写入，`position` 未达到 `capacity(N)` 就 `flip()` 会导致只写出部分数据。务必在 `flip()` 前调用 `buf.position(CAPACITY)` 推进到末尾
- **索引文件校验**：`handleStreamChat` 的 sync 块中检查 `Files.size(indexPath) != INDEX_FILE_SIZE` 时自动删除重建，防止旧版本/破损 index.bin 引发 `Buffer.position() > limit` 异常
- **索引文件并发**：序号计数器的读写需注意并发安全，同一会话同时请求时需加锁。当前使用 per-conversation 的 `synchronized` 锁，序号一次性预分配 2 位（用户消息 + AI 回复），异步部分写入 AI 回复后回写序号
- **tools.bin 覆盖写入**：每次请求携带 tools 配置时覆盖写入，保证与前端状态一致
- **Body 免解析**：请求 Body 字节直接作为碎片 payload 写入，不创建 JsonObject。仅 Header 中的 tools/samplingParams 做 Gson 解析（体积极小，无内存风险）
- **Header 编码**：X-Tools 和 X-Sampling-Params 是单行 JSON 字符串，仅 ASCII 字符；X-Assistant-Name 如含中文需前端 URL 编码或使用 ASCII 名称
- **碎片文件清理**：旧格式碎片（header 不完整）无法被 `readFragmentPayload` 正确读取，需手动删除 `cache/easy-chat/fragments/{conversationId}/` 下旧文件后重启
- **碎片文件多变体**：旧格式碎片文件 offset 32-73 为零值，`readFragmentCount` 返回 0 时 fallback 到 `Files.size() - 160` 计算单条 payload 长度。新格式碎片 count >= 1
- **DefaultFileRegion 顺序**：`ctx.write()` + `DefaultFileRegion` 混合使用时，必须每个 write 后立即 `writeAndFlush()`，否则 JSON 字节到达顺序不可控
- **重新生成变体上限**：单碎片文件最多 10 条变体。超出时 `appendFragment` 抛 `IOException`
- **重新生成截断**：前端重新生成某条 AI 消息后，`loadHistory(seq)` 只渲染 seq <= 目标 seq 的消息，后续消息被清除（因为上下文已改变）
- **X-Variants 格式**：`"seq:variantIdx,seq:variantIdx"`，如 `"1:0,3:1"`。被重新生成的 AI 消息 seq 不包含在内（它没有变体选择，它是要被替换的目标）
