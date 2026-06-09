# Stream-Chat 前端迁移修改记录

## 概述

将 `index.html` 从旧的 `/v1/chat/completions` 端点迁移到新的 `/api/easy-chat/stream-chat` 端点。

---

## 已完成修改

### 1. 端点常量 (index.html)

- **位置**: L504
- **改动**: 新增 `STREAM_CHAT_ENDPOINT = '/api/easy-chat/stream-chat'`

### 2. 辅助函数 (index.html)

#### `buildSamplingParamsJson(assistant)` — L5268
- 从 assistant 配置构建采样参数 JSON 字符串
- 支持 temperature, max_tokens, top_p, top_k, min_p, presence_penalty, repeat_penalty, frequency_penalty, stop

#### `buildStreamChatHeaders(conversationId, assistant, options)` — L5288
- 构建 stream-chat 请求的 HTTP Headers
- 所有 Header 值用 `encodeURIComponent()` 编码（ISO-8859-1 兼容性修复）
- 支持的 Headers:
  - `X-Conversation-Id` — 会话ID
  - `X-Model-Id` — 模型ID
  - `X-Node-Id` — 节点ID（可选）
  - `X-Assistant-Name` — 助手名称（可选）
  - `X-Sampling-Params` — 采样参数 JSON（可选）
  - `X-Tools` — 工具定义 JSON（可选）
  - `X-Tool-Choice` — 工具选择策略
  - `X-Regenerate-Id` — 重新生成目标 AI seq（可选）
  - `X-Variants` — 变体选择（可选）

#### `buildStreamUserMessage(text, images, files, audios, videos)` — L5328
- 构建单条用户消息 JSON 字符串
- 支持多部分内容（text + image_url + file）
- 单条文本直接设为 content 字符串，多条内容设为数组

#### `convertHistoryMessage(msg)` — L5344
- 将 variants 格式的历史消息转为前端消息对象
- 设置 `order: msg.seq` 作为 fragment seq 标识
- 处理 assistant 的 reasoning_content 和 tool_calls
- 处理 tool 消息的 tool_call_id 和 name

### 3. `requestAssistantResponse()` — L4874

**核心改动**：
- 改用 `STREAM_CHAT_ENDPOINT`
- 元数据走 HTTP Headers（`buildStreamChatHeaders`）
- Body 只放单条消息 JSON（`buildStreamUserMessage`）
- 重新生成模式：Body=null，发送 `X-Regenerate-Id` Header
- Tool call 循环：发送 tool 消息 JSON

**改动细节**：
- 移除 `buildPayload` 调用（不再构建完整 messages 数组）
- 新增 `isRegenerate` 标志（基于 `options.regenerateSeq`）
- 重新生成时 Body 为 null
- Tool loop 检测改为 `conversation.messages.some(m => m.role === 'tool')`

### 4. `loadConversationById()` — L3239

**核心改动**：
- 移除旧端点 fallback（`EASY_CHAT_CONVERSATION_ENDPOINT`）
- 改用 `GET /api/easy-chat/stream-chat?conversationId=xxx` 加载历史
- 解析 variants 数组格式，调用 `convertHistoryMessage`
- 初始化 `conversation.nextFragmentSeq`（从 max seq + 1）
- 空数据视为空会话（不再报错）

### 5. `pushMessage()` — L4110

**改动**：
- 新增 `fragmentSeq` 字段（从 `options.fragmentSeq` 获取）
- 存储到消息对象，用于后续重新生成时的 seq 追踪

### 6. `sendMessage()` — L5722

**改动**：
- 发送消息时分配 `fragmentSeq`（从 `conversation.nextFragmentSeq`）
- 发送后 `nextFragmentSeq += 2`（用户 + AI 各占一位）

### 7. `createConversation()` — L5785

**改动**：
- 新建会话时初始化 `conversation.nextFragmentSeq = 0`

### 8. `regenerateMessage()` — L5042

**改动**：
- 计算 `regenerateSeq = userMsg.fragmentSeq + 1`（或 `userMsg.order + 1`）
- 传递给 `requestAssistantResponse({ targetAssistantIndex, regenerateSeq })`

### 9. `flushStateSave()` — L3386

**改动**：
- 跳过 `saveConversationToServer()`（碎片已由后端自动持久化）
- 仅保留 state.json 同步

### 10. 后端 Header 解码 (EasyChatService.java)

- **新增 import**: `java.net.URLDecoder`
- **新增方法**: `decodeHeader(String value)` — UTF-8 URL 解码
- **改动**: 所有 Header 读取改用 `decodeHeader(request.headers().get("..."))`
  - `X-Conversation-Id`
  - `X-Model-Id`
  - `X-Assistant-Name`
  - `X-Tools`
  - `X-Sampling-Params`

---

## 已知待修复问题

### 高优先级
1. **Tool call 多消息** — 当前只发最后一个 tool 消息，多 tool 场景需优化
2. **重新生成变体上限** — 单碎片最多 10 条变体，超出需处理
3. **`createConversationSummary`** — 未初始化 `nextFragmentSeq`，从 state.json 恢复的会话可能丢失

### 中优先级
4. **`generateConversationTitle`** — 仍用旧 `CHAT_ENDPOINT`，需迁移
5. **`generateAutoReply`** — 仍用旧 `CHAT_ENDPOINT`，需迁移
6. **图片/文件内容格式** — `buildStreamUserMessage` 与后端 payload 格式需对齐
7. **`completion-runtime.js`** — 旧聊天页面未迁移（不在本次范围）

### 低优先级
8. **重新生成变体 UI** — 变体切换器、变体缓存等 UI 功能
9. **重新生成截断** — 重新生成后清除后续消息
10. **旧端点废弃标记** — `CHAT_ENDPOINT` 标记为废弃

---

## 数据流对照

### 旧流程
```
前端: buildPayload() → {model, messages:[...], stream, tools, ...params}
      fetch(POST /v1/chat/completions, body=JSON.stringify(payload))
      → SSE 响应 → consumeSse() → 更新 UI
```

### 新流程
```
前端: buildStreamChatHeaders(convId, assistant, options)
      buildStreamUserMessage(text, images, files, ...)
      fetch(POST /api/easy-chat/stream-chat, headers, body=userMessageJSON)
      → 后端写入用户碎片 → 流式读取碎片构建请求 → 转发 llama.cpp
      → SSE 响应 → consumeSse() → 更新 UI
      → 后端写入 AI 碎片
```

### 重新生成流程
```
前端: regenerateMessage(index)
      → regenerateSeq = userMsg.fragmentSeq + 1
      → requestAssistantResponse({ regenerateSeq })
      → buildStreamChatHeaders(convId, assistant, { regenerateSeq })
      → fetch(POST /api/easy-chat/stream-chat, headers, body=null)
      → 后端读取 X-Regenerate-Id → 复用 AI 碎片 → 追加新变体
```

### 历史加载流程
```
前端: loadConversationById(id)
      → GET /api/easy-chat/stream-chat?conversationId=xxx
      → 后端零拷贝读取碎片 → 返回 JSON {data: [{seq, role, variants: [...]}]}
      → convertHistoryMessage() → 填充 conversation.messages
      → 初始化 nextFragmentSeq
```
