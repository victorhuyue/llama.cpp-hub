# 模型加载机制详解

## 概述

模型加载流程分为 **前端发起 → Controller 路由 → 参数构建 → 异步进程启动 → 就绪检测 → 后处理** 六个阶段。支持本地加载和远程节点转发。

---

## 1. API 入口

### POST `/api/models/load`

**请求体示例：**

```json
{
  "modelId": "qwen2.5-7b",
  "cmd": "-ngl 99 --ctx-size 8192",
  "extraParams": "--no-warmup",
  "enableVision": true,
  "llamaBinPathSelect": "C:\\llama.cpp\\rocm\\bin",
  "device": ["0", "1"],
  "mg": 0,
  "nodeId": "node-01"
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `modelId` | string | 模型唯一标识 |
| `cmd` | string | 启动命令参数（不含 `-m` / `--port` / `--mmproj` 等框架自动生成的参数） |
| `extraParams` | string | 额外参数字符串，追加在 `cmd` 之后 |
| `enableVision` | bool | 是否启用视觉（默认 `true`），启用时自动拼接 `--mmproj` |
| `llamaBinPathSelect` | string | `llama-server` 所在目录 |
| `device` | string[] | GPU 设备列表，如 `["0", "1"]` |
| `mg` | int | 主 GPU 索引（用于多卡） |
| `nodeId` | string | 可空；非空且非 `"local"` 时转发到远程节点 |

---

## 2. 请求路由（Controller 层）

**类：** `ModelActionController.java`（`controller/ModelActionController.java:615`）

```
POST /api/models/load
       │
       ▼
  handleLoadModelRequest()
       │
       ├── nodeId 非空且非 "local" ──► loadRemoteModel()
       │                                  │
       │                                  ▼
       │                          NodeManager.callRemoteApi()
       │                           POST {node}/api/models/load
       │
       └── nodeId 为空或 "local"  ──► loadLocalModel()
                                          │
                                          ▼
                                  LlamaServerManager.loadModelAsyncFromCmd()
```

**远程转发逻辑（`loadRemoteModel`，line 732）：**  
移除 `nodeId` 字段防止回环，通过 HTTP POST 携带原 body 调用远程节点的 `/api/models/load`。

**远程查找逻辑（`findAndLoadOnRemoteNode`，line 659）：**  
如果本地模型列表中找不到该 `modelId`，遍历所有启用的远程节点查询 `api/models/list`，找到后转发。

---

## 3. 参数校验与提取

**类：** `ModelActionController.loadLocalModel()`（line 673）

```java
cmd         = obj.get("cmd")             // 启动命令
extraParams = obj.get("extraParams")     // 额外参数
enableVision = obj.get("enableVision")   // 是否启用视觉（默认 true）
modelId     = obj.get("modelId")         // 模型 ID
llamaBinPath = obj.get("llamaBinPathSelect") ?? obj.get("llamaBinPath")
device      = obj.get("device")          // GPU 设备列表
mg          = obj.get("mg")              // 主 GPU 索引
```

校验顺序：
1. `cmd` 和 `extraParams` 不能同时为空
2. `modelId` 不能为空
3. `llamaBinPath` 不能为空
4. 不能重复加载（`loadedProcesses` / `loadingModels` 中已存在）

校验通过后加载聊天模板缓存路径并调用 `loadModelAsyncFromCmd`。

---

## 4. 异步模型加载（核心）

**类：** `LlamaServerManager.java`

### 4.1 `loadModelAsyncFromCmd()`（line 1601）

1. **保存启动配置** — 将 `{llamaBinPath, device, mg, cmd, extraParams, enableVision}` 写入配置文件并重建自动加载缓存
2. **查重** — `loadedProcesses` 和 `loadingModels` 双重检查
3. **提交异步任务** — 通过 `ExecutorService`（单个虚拟线程 `llama-loader`）执行 `loadModelInBackgroundFromCmd`
4. **返回** — 立即返回 `{async: true}`（不等待进程就绪）

### 4.2 `loadModelInBackgroundFromCmd()`（line 1681）

这是加载的核心方法，按顺序执行以下步骤：

```
loadModelInBackgroundFromCmd()
  │
  ├── 1. 分配端口 ──► getNextAvailablePort()
  │                     从 8081 起递增，PortChecker 检测端口是否可用
  │
  ├── 2. 构建完整命令 ──► buildCommandStr()
  │                     （详见第 5 节）
  │
  ├── 3. 创建进程对象 ──► new LlamaCppProcess(name, commandStr, llamaBinPath, modelId)
  │
  ├── 4. 设置输出回调 ──► setOutputHandler()
  │     │                 监听 "all slots are idle" 判定成功
  │     │                 监听错误关键词判定失败
  │     └── setOnProcessExited()
  │                          进程意外退出时清理 loadedProcesses
  │
  ├── 5. 启动进程 ──► process.start()
  │     │             ProcessBuilder 设置 LD_LIBRARY_PATH / PATH
  │     │             启动后等待 500ms 获取 PID
  │     └── 注册到 loadingProcesses，发送加载中事件
  │
  ├── 6. 等待就绪 ──► latch.await(10, TimeUnit.MINUTES)
  │     │             监听 stdout 中的信号字符串
  │     │             超时 → 停止进程 → 发送失败事件
  │     └── 出错 → 停止进程 → 发送失败事件
  │
  └── 7. 加载成功 ──► 注册到 loadedProcesses / modelPorts
                      发送加载成功事件
                      查询 /slots 获取 n_ctx / slot 数量
                      同步 /v1/models 元数据
```

#### 就绪判定规则（`setOutputHandler`，line 1704）

| 输出包含 | 判定 |
|----------|------|
| `"srv  update_slots: all slots are idle"` | **成功** — 模型就绪 |
| `"exiting due to model loading error"` | 失败 |
| `"failed to load model"` | 失败 |
| `"cuda error"` / `"hip error"` | 失败 |
| `"out of memory"` | 失败 |
| `"segfault"` / `"signal 11"` | 失败 |
| 超时 10 分钟 | 失败 |

> 完整错误关键词列表见 `isModelErrorLine()`（line 1871）

---

## 5. 命令行构建 `buildCommandStr()`

**类：** `LlamaServerManager.java`（line 1921）

最终生成的命令格式（Windows）：

```
{llamaBinPath}\llama-server.exe \
  -m {模型GGUF路径} \
  --port {自动分配端口} \
  [--mmproj {mmproj路径}] \
  [--device {GPU列表}] \
  [--main-gpu {索引}] \
  {cmd} \
  {extraParams} \
  [--chat-template-file {模板路径}] \
  --metrics \
  --alias {模型别名} \
  --timeout 36000 \
  --host 0.0.0.0
```

### 参数拼接规则

| 参数 | 来源 | 条件 |
|------|------|------|
| `-m {模型GGUF}` | 自动从模型元数据获取 | 必有 |
| `--port {端口}` | 自动分配 | 如果 `cmd` 中未指定 `--port` |
| `--mmproj {路径}` | 模型 mmproj 文件 | `enableVision=true` 且模型有 mmproj，且 `cmd` 中未指定 `--mmproj` / `--no-mmproj` |
| `--device {列表}` | 请求参数 `device` | 非空 |
| `--main-gpu {n}` | 请求参数 `mg` | `device` 非空且 `mg >= 0` |
| `{cmd}` | 请求参数 `cmd` | 非空，剥离 `--alias` 标志 |
| `{extraParams}` | 请求参数 `extraParams` | 非空，剥离 `--alias` 标志 |
| `--chat-template-file` | 自动缓存 | 聊天模板文件存在且 `cmd`/`extraParams` 中未指定 |
| `--metrics` | 自动添加 | `cmd` 中未指定 `--metrics` |
| `--alias {名称}` | 模型别名或 modelId | 必有 |
| `--timeout 36000` | 固定值 10h | 必有 |
| `--host 0.0.0.0` | 固定值 | 必有 |

### 特殊处理

- **多个 `--spec-type` 合并** — `splitSpecType()` 将 `--spec-type_model=xxx`、`--spec-type_ngram-mod`、`--spec-type xxx` 合并为 `--spec-type xxx,ngram-mod,...`
- **设备单元素与多元素差异** — 单设备使用 `-sm none --device X`；多设备使用 `--device X,Y,Z`
- **`--no-webui`** 已被注释禁用

---

## 6. LlamaCppProcess 进程管理

**类：** `LlamaCppProcess.java`

### 启动 `start()`（line 115）

```java
1. splitCommandLineArgs(cmd)    // 解析命令行到 List<String>
2. new ProcessBuilder(args)     // 创建进程
3. 设置环境变量：
   - Linux: LD_LIBRARY_PATH（追加 ROCm 库路径）
   - Windows: PATH（追加 ROCm / CUDA DLL 目录）
4. pb.start()                  // 启动子进程
5. sleep(500ms)                // 等待 PID 可用
6. 获取 PID
7. 启动虚拟线程读取 stdout/stderr
8. 注册 process.onExit() 回调
```

### 环境变量注入

| 平台 | 变量 | 内容 |
|------|------|------|
| Linux | `LD_LIBRARY_PATH` | `{llamaBinPath}` + ROCm 库路径 + 原值 |
| Windows | `PATH`（前缀） | `{llamaBinPath}` + 扫描 `C:\Program Files\AMD\ROCm\*\bin` + `AI_Bundle` 路径 + CUDA DLL 路径 |

### 输出读取

使用两个虚拟线程分别读取 `stdout` 和 `stderr`，内容：
- 转发到 `WebSocket` 控制台事件
- 记录到 `LLAMA_CPP_RAW` 日志
- 过滤 `update_slots` / `log_server_r` 避免日志过多

### 停止 `stop()`（line 209）

```
1. close(stdin writer)
2. process.destroy()           // 优雅停止
3. waitFor(5s)
4. 超时则 destroyForcibly()    // 强制杀死
5. close(stdin / stderr 流)
6. join 输出线程 (2s 超时)
```

---

## 7. 加载成功后处理

**类：** `LlamaServerManager.java`（line 1782-1836）

```
加载成功
  │
  ├── 1. 注册到 loadedProcesses ──► this.loadedProcesses.put(modelId, process)
  │     └── 记录端口 ──► this.modelPorts.put(modelId, actualPort)
  │
  ├── 2. 发送加载成功事件 ──► LlamaServer.sendModelLoadEvent(true, port)
  │     └── WebSocket 通知前端 "模型加载成功"
  │
  ├── 3. 查询 /slots ──► GET http://localhost:{port}/slots
  │     ├── 解析 n_ctx（上下文窗口大小）
  │     └── 解析 slot 数量
  │     └── 写入 LlamaCppProcess.ctxSize / LlamaCppProcess.slotNum
  │
  ├── 4. 拉取模型信息 ──► GET http://localhost:{port}/v1/models
  │     └── handleModelInfo() 解析并缓存模型名称等元数据
  │
  ├── 5. 清理 loading 状态
  │     ├── loadingProcesses.remove(modelId)
  │     ├── loadingTasks.remove(modelId)
  │     └── loadingModels.remove(modelId)
  │
  └── 6. 重建自动加载缓存
```

### 失败处理

| 场景 | 处理 |
|------|------|
| 进程启动失败 | 发送 `loadEvent(false, "启动模型进程失败")` |
| 加载超时 (10min) | `process.stop()` + `loadEvent(false, "模型加载超时")` |
| 输出检测到错误 | `process.stop()` + `loadEvent(false, "模型加载失败")` |
| 加载被取消 | `process.stop()` 静默退出 |
| 进程意外崩溃 | `loadedProcesses.remove(modelId)` + `sendModelStopEvent()` |

---

## 8. 远程节点加载

**类：** `NodeManager.java`

当 `nodeId` 非空时，整个请求通过 HTTP 转发到远程节点：

```
本地节点                             远程节点
  │                                     │
  │── POST /api/models/load ──────────► │
  │   {modelId, cmd, extraParams, ...}  │
  │                                     │── loadLocalModel() (同上)
  │                                     │── ...
  │◄── {async: true} ────────────────── │
```

`NodeManager.callRemoteApi()` 负责：
- 从节点注册表查找目标 URL
- 构造 `HttpURLConnection` 发起请求
- 设置连接超时和读取超时
- 返回 `HttpResult(statusCode, body)`

---

## 9. 前端触发流程

**文件：** `web/js/model-action-modal.js`（line 1466）

```
点击 "加载" 按钮
  │
  ├── 先保存配置 ──► POST /api/models/config/set
  │
  └── 再发起加载 ──► POST /api/models/load
                     │
                     ▼
              收到 {async: true}
                     │
                     ▼
              window.pendingModelLoad = { modelId }
                     │
                     ▼
              关闭弹窗，等待 WebSocket 事件
```

---

## 关键类与文件索引

| 类/文件 | 路径 | 核心方法 |
|---------|------|----------|
| `ModelActionController` | `controller/ModelActionController.java` | `handleLoadModelRequest()` line 615, `loadLocalModel()` line 673 |
| `LlamaServerManager` | `LlamaServerManager.java` | `loadModelAsyncFromCmd()` line 1601, `loadModelInBackgroundFromCmd()` line 1681, `buildCommandStr()` line 1921 |
| `LlamaCppProcess` | `LlamaCppProcess.java` | `start()` line 115, `stop()` line 209 |
| `NodeManager` | `NodeManager.java` | `callRemoteApi()` |
| 前端 | `web/js/model-action-modal.js` | 请求组装 line 1466 |
| API 文档 | `docs/API.md` | `/api/models/load` line 120 |

---

## 时序图

```
前端                        Controller              LlamaServerManager          LlamaCppProcess
 │                              │                          │                         │
 │── POST /api/models/load ──►  │                          │                         │
 │                              │── loadModelAsyncFromCmd()│                         │
 │                              │     │                    │                         │
 │                              │     │── 保存配置         │                         │
 │                              │     │── executor.submit()│                         │
 │◄── {async: true} ────────────│     │                    │                         │
 │                              │     │                    │                         │
 │                              │     │── loadInBackground()                        │
 │                              │     │     │              │                         │
 │                              │     │     ├── getNextAvailablePort()              │
 │                              │     │     ├── buildCommandStr()                   │
 │                              │     │     ├── new LlamaCppProcess() ────────────► │
 │                              │     │     ├── setOutputHandler()                  │
 │                              │     │     ├── start() ──────────────────────────► │
 │                              │     │     │                                       │── ProcessBuilder.start()
 │                              │     │     │                                       │── 读取 stdout/stderr
 │                              │     │     │                                       │
 │                              │     │     │── latch.await(10min)                  │
 │                              │     │     │     │                                │
 │                              │     │     │     │── "all slots are idle" ────────►│
 │                              │     │     │     │                                │
 │                              │     │     │── 注册到 loadedProcesses              │
 │                              │     │     │── sendModelLoadEvent(true)            │
 │◄──── WebSocket: 加载成功 ─────│─────│─────│                                      │
 │                              │     │     │── GET /slots                          │
 │                              │     │     │── GET /v1/models                      │
```
