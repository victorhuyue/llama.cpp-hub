# 大文件请求体流式处理

## 背景

`POST /api/chat/stream-chat` 上传大文件时出现 413 Payload Too Large 错误。原因是 Netty 管线中的 `HttpObjectAggregator(16MB)` 在请求到达 `EasyChatController` 之前就拒绝了超过 16MB 的请求体。

## Netty 管线

```
HttpServerCodec
  → OpenAIChatStreamingHandler      (拦截 /v1/chat/completions)
  → FileUploadRouterHandler         (拦截 /api/uploads)
  → EasyChatStreamingHandler        ← 新增：拦截 POST /api/chat/stream-chat
  → HttpObjectAggregator(16MB)      ← 不再处理 stream-chat 请求
  → ChunkedWriteHandler
  → WebSocketServerProtocolHandler
  → WebSocketServerHandler
  → BasicRouterHandler               (EasyChatController)
```

## 方案：临时文件 + channel attr

在 `HttpObjectAggregator` 之前插入 `EasyChatStreamingHandler`，将请求体分片直接写入磁盘临时文件，避免进入内存聚合。

### 数据流

```
客户端 → EasyChatStreamingHandler → cache/temp/easychat-{uuid}.bin (临时文件)
  → 空body FullHttpRequest + channel attr(文件路径) → EasyChatService
  → storage.writeFragment(file) → cache/easy-chat/fragments/{convId}/{seq}.bin (持久化)
  → 删除临时文件
  → EasyChatRequestWriter → 从 fragment 文件流式读取 → llama.cpp
```

整个过程 **不进 JVM 堆内存**（除了读取临时文件到 fragment 文件的一次性缓冲拷贝）。

### 涉及文件

| 文件 | 说明 |
|------|------|
| `EasyChatStreamingHandler.java` | 拦截 POST，分片写入临时文件，传递文件路径 via channel attr |
| `EasyChatService.java` | 检测 `STREAMING_BODY_FILE` channel attr，使用文件式 fragment 写入 |
| `EasyChatStorage.java` | 新增 `writeFragment(dir, seq, timestamp, sourceFile)` |

### 代码入口

- `EasyChatStreamingHandler.java:77` — 创建临时文件
- `EasyChatStreamingHandler.java:134` — 通过 `channel.attr(STREAMING_BODY_FILE)` 传递路径
- `EasyChatService.java:152` — 读取 `STREAMING_BODY_FILE`
- `EasyChatService.java:330` — 使用文件式 `storage.writeFragment(file)`
- `EasyChatStorage.java:152` — `writeFragment(dir, seq, timestamp, sourceFile)` 实现
