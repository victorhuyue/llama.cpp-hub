# Stream-Chat 删除与优化修改记录

## 概述

本次修改涵盖了 stream-chat 的删除功能、时序信息保存、请求参数优化、前端清理等多个方面。

---

## 一、删除功能（DELETE /api/easy-chat/delete）

### API 设计

```
DELETE /api/easy-chat/delete
Body: {
  "type": "conversation" | "message",
  "conversationId": "xxx",
  "seq": 1,              // message 类型时指定
  "variantIndex": 0       // AI 变体删除时指定（可选）
}
```

### 后端实现（EasyChatService.java / EasyChatController.java）

#### 删除会话（type: "conversation"）
- `deleteConversation(conversationId)`: 使用 `Files.walkFileTree` 删除 `fragments/{conversationId}/` 整个目录
- `removeFromStateConversations`: 从 `state.json` 移除会话摘要，更新 messageCount

#### 删除消息（type: "message"）
- **用户消息**（variantIndex 为空）：`clearFragmentPayload` — 清空 payload 内容为 `{"role":"user","content":""}`，保留文件结构
- **AI 变体**（variantIndex 指定）：`clearFragmentVariant` — 删除目标变体，count 减 1，后续变体前移压缩

### 前端实现（index.html）

- 新增 `EASY_CHAT_DELETE_ENDPOINT = '/api/easy-chat/delete'`
- `deleteConversation`: 先调用后端删除碎片文件，再更新本地状态
- `deleteMessage`: 改为 async，根据消息类型调用后端删除对应碎片

### 空消息判断（后端历史记录加载）

`handleStreamChatHistory` Phase 3 中：
- 空消息 payload 长度固定为 **28 bytes**（文件大小 188 = 160 header + 28 payload）
- 判断 `firstPayload.length == 28` 时跳过该消息，不返回前端
- 跳过时必须 `seq++` 再 `continue`，否则死循环

---

## 二、Timings 持久化

### 问题
后端保存 AI 响应碎片时没有保存 timings 信息，导致刷新页面后性能指标丢失。

### 修改

**后端（EasyChatService.java）：**
- `StreamAccumulator`: 新增 `JsonObject timings = null` 字段
- `proxySseStream`: SSE 解析时捕获 `timings` → `accumulator.timings`
- `buildAiMessage`: AI 消息 JSON 包含 `timings` 字段

**前端（index.html）：**
- `convertHistoryMessage`: 从 payload 提取 `timings` → `out.timings`
- `normalizeConversationMessage`: 透传 `timings` 字段（已在 base 中）
- `normalizeTimings`: 已有，解析 timings 对象中的各项指标

### 数据流

```
llama.cpp SSE → {"timings": {prompt_ms: 12.3, ...}}
  → proxySseStream → accumulator.timings
  → buildAiMessage → {"role": "assistant", "content": "...", "timings": {...}}
  → writeFragment → 碎片持久化
  → 加载历史 → convertHistoryMessage → out.timings = payload.timings
  → normalizeTimings → 前端渲染性能指标
```

---

## 三、请求参数优化

### 新增参数（writeRequestBody）

转发到 llama.cpp 的请求体新增：
- `timings_per_token: true` — 每个 token 返回 timings
- `return_progress: true` — 返回进度信息

### Tools JSON 修复

**问题**：`toolsBytes` 直接拼接导致 JSON 语法错误，`{"tools":[...]}` 成为独立对象。

**修复**：解析 `toolsBytes` 为 JsonObject，字段展开后拼接到主对象内，key 用双引号包裹。

```
修复前: {"model":"...","messages":[...]
        ,{"tools":[...],"tool_choice":"auto"}}  ← 语法错误！

修复后: {"model":"...","messages":[...],"tools":[...],"tool_choice":"auto"}
```

### Sampling Params 修复

同样的 key 引号问题，`samplingParams` 展开时 key 需要用 `"key"` 包裹。

---

## 四、ByteBuffer 写入修复

### 问题

`clearFragmentPayload` 和 `clearFragmentVariant` 中，`ByteBuffer` 写入 `FileChannel` 时没有正确处理 position/limit，导致只写入 74 字节而非完整的 160 字节 header。

### 根因

`ByteBuffer.wrap()` / `allocate()` 后，`putInt()` 等相对操作会推进 position。`flip()` 设置 limit 为当前 position（74），`ch.write()` 只写 0~74 字节。

### 修复

`flip()` 之前先 `position(FRAG_HEADER_SIZE)` 将 position 推到 160：

```java
outBuf.putInt(lengths[i]);  // position 推进到 74
outBuf.position(FRAG_HEADER_SIZE);  // position → 160
outBuf.flip();  // position=0, limit=160
ch.write(outBuf);  // 写入完整的 160 字节
```

---

## 五、前端旧逻辑清理

### 移除的死代码

| 代码 | 说明 |
|------|------|
| `EASY_CHAT_CONVERSATION_SAVE_ENDPOINT` | 不再需要单独的会话保存端点 |
| `buildConversationPayload()` | 构建含完整 messages 的 payload，已废弃 |
| `saveConversationToServer()` | 调用已废弃端点 |
| `currentConversationDirty` | 8 处引用全部移除，原用于触发 saveConversationToServer |

### flushStateSave 简化

移除了 dead code 分支，只保留 `buildSyncPayload() → writeStateToServer()` 的轻量级同步。

---

## 六、No-Model 重新生成 Bug 修复

### 问题

`regenerateMessage` 在没有选择模型时，先推入空响应、渲染空白消息、保存状态，然后才调用 `requestAssistantResponse` 发现没有模型并返回。空响应已经留在消息数组中，导致后续 bin 文件损坏。

### 修复

`regenerateMessage` 开始时就检查模型：

```javascript
syncRuntimeStateFromCurrentAssistant();
if (!state.model) {
    updateStatus('error', '请先选择模型', '未选择模型');
    return;  // 在任何状态修改之前返回
}
```

---

## 七、order/fragmentSeq 透传修复

### 问题

`normalizeConversationMessage` 创建 `base` 对象时只提取固定字段（role, content, model 等），不传递 `order`、`id`、`fragmentSeq`。

### 影响

`regenerateMessage` 依赖 `userMsg.order` 计算 `regenerateSeq`。`order` 被丢弃后 `regenerateSeq = null`，`isRegenerate = false`，后端当成新消息处理。

### 修复

`normalizeConversationMessage` 的 `base` 对象新增：

```javascript
id: message?.id,
order: message?.order,
fragmentSeq: message?.fragmentSeq
```

---

## 八、Variants 多分支渲染修复

### 问题

`convertHistoryMessage` 只取 `msg.variants[0]`，丢弃其他变体。

### 修复

Assistant 消息且 `variants.length > 1` 时，将所有变体转换为 `responses` 数组：

```javascript
if (msg.variants.length > 1) {
    out.responses = msg.variants.map(v => ({
        content: p.content || '',
        reasoning: p.reasoning_content || ''
    }));
    out.activeResponseIndex = 0;
}
```

---

## 九、后端 writeJsonFile 加固

### 问题

Windows 上 `Files.move` 使用 `ATOMIC_MOVE` 可能因文件锁定失败，fallback 也可能失败。

### 修复

增加最多 3 次重试，间隔 50ms → 150ms 递增：

```java
for (int attempt = 0; attempt < 3; attempt++) {
    try {
        Files.move(tempFile, file, REPLACE_EXISTING, ATOMIC_MOVE);
        return;
    } catch (Exception e) {
        try {
            Files.move(tempFile, file, REPLACE_EXISTING);
            return;
        } catch (Exception fallbackE) {
            if (attempt < 2) {
                Thread.sleep(50 + attempt * 100);
            } else {
                throw fallbackE;
            }
        }
    }
}
```

---

## 文件清单

| 文件 | 修改内容 |
|------|---------|
| `EasyChatController.java` | 新增 DELETE 端点、removeFromStateConversations、updateStateMessageCount、writeJsonFile 重试 |
| `EasyChatService.java` | 新增 delete/deleteMessage/clearFragmentPayload/clearFragmentVariant、timings 捕获、请求参数优化、tools/sampling JSON 修复、ByteBuffer 修复、空消息跳过 |
| `index.html` | 新增删除端点调用、清理死代码、order/fragmentSeq 透传、variants 渲染、no-model 检查 |
