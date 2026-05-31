# auto-fit：自动显存适配

运行大模型时最常见的痛点之一就是找到合适的参数，让模型适配设备显存而不出现 OOM（out of memory）。auto-fit 功能旨在自动解决这个问题。

`--fit` 默认开启，它在启动时测量每个设备的空闲显存，然后调整用户未设置的参数，使得模型总占用（权重 + KV cache + 计算缓冲区）预计能适配预留的安全余量。

---

## 工作原理

`--fit` 在启动时、模型完全加载前运行。它以 `no_alloc` 模式（仅分配元数据，不加载张量数据）加载模型，测量每个设备的内存需求，然后执行四个步骤：

| 步骤 | 做什么 |
|------|--------|
| **1. 探测** | 用当前参数加载模型，测量每个设备的预计内存使用。如果余量目标已经满足，立即返回——无需调整。 |
| **2. 缩减上下文** | 如果内存不足，缩小 `--ctx-size`（`-c`）来回收 KV cache 内存。仅当用户未显式设置 `-c` 时生效。在默认 context 和最小 context 之间用线性插值找到能容纳的最大值。 |
| **3. 分配层** | 使用 **试位法**（method of false position，一种求根算法）从后向前计算每张 GPU 能容纳多少层。设置 `--n-gpu-layers`（`-ngl`），多 GPU 时还设置 `--tensor-split`（`-ts`）。Dense 层连续分片分配给各设备；MoE 模型则先将 MoE 张量溢出到系统内存。 |
| **4. 填充剩余（仅 MoE）** | 如果 MoE 模型的 dense 层分配后还有余量，将剩余的 dense-only 层从前向后转换为完整层。然后尝试三种不同的溢出策略再塞入更多层：`UP`（仅 FFN up-projection）、`GATE`（up + gate）、`ATTN`（除 FFN gate/up 外的所有内容）。 |

详细实现见 `common/fit.cpp:153`（`common_params_fit_impl`）。

---

## auto-fit 可修改的参数

| 参数 | CLI flag | 条件 |
|------|----------|------|
| 上下文大小 | `-c` | 仅当值为 `0`（默认值）时 |
| GPU 层数 | `-ngl` | 仅当用户未显式设置 `-ngl` 时 |
| 张量分割 | `-ts` | 仅当用户未显式设置 `-ts`，且为多 GPU `layer` 分割模式时 |
| 张量缓冲类型覆盖 | `-ot` | 仅当用户未显式设置 `-ot`，且存在部分层溢出时 |

### `--fit` 何时中止（回退到用户参数）

`--fit` 不会静默覆盖用户指定的值。如果以下参数被设置，`--fit` 会跳过相应调整或完全中止：

```cpp
// fit.cpp:352 — 用户显式设置了 -ngl：
if (mparams->n_gpu_layers != default_mparams.n_gpu_layers) {
    throw common_params_fit_exception("n_gpu_layers already set by user, abort");
}

// fit.cpp:343 — 用户显式设置了 -c：
if (cparams->n_ctx == 0) {
    // 调整 context...
} else {
    // "context size set by user -> no change"
}

// fit.cpp:359-364 — 用户显式设置了 -ts：
if (mparams->tensor_split && mparams->tensor_split[id] != 0.0f) {
    throw common_params_fit_exception("tensor_split already set by user, abort");
}
```

这些异常会被优雅捕获——程序打印警告日志后继续以用户指定参数运行。

### `--fit` 不适用的场景

- **`--split-mode tensor`**（`-sm tensor`）：`--fit` 未实现张量并行模式的支持，会中止并提示 `"not implemented for SPLIT_MODE_TENSOR"`。需要手动设置 `--ctx-size` 来适配显存。
- **`--split-mode row`**（`-sm row`）：`--fit` 无法调整 row 分割模式的权重分配。
- **不报告内存的设备**：报告 0/0 空闲/总量内存的 GPU 类后端会被跳过。

---

## `llama-fit-params` —— 获取 CLI 参数形式的 fit 结果

`--fit` 在每次启动 `llama-server` 或 `llama-cli` 时都会运行。~1-2 秒的内存探测开销通常可以忽略，但在以下场景中你可能希望将结果保存为可复用的 CLI 参数：

- **可复现部署**——每次分配方案一致
- **显式配置**——检查并微调 `--fit` 的决定
- **提速**——后续启动跳过探测步骤

`llama-fit-params` 内部调用的是同一个 `common_fit_params()` 函数（`tools/fit-params/fit-params.cpp:34`），但将结果打印到 stdout 而非加载模型。

### 用法

```bash
# 第一步：计算最优配置
llama-fit-params --model model.gguf > args.txt

# 第二步：将结果作为参数启动服务器
cat args.txt | xargs llama-server --model model.gguf
```

输出为单行 CLI 参数，例如：

```
-c 4096 -ngl 48 -ts 48 -ot "blk\.14\.ffn_.*=CPU,blk\.15\.ffn_.*=CPU,..."
```

### 输出参数

| 输出 | 含义 | 何时打印 |
|------|------|---------|
| `-c` | 最优上下文大小 | 始终 |
| `-ngl` | 卸载到 GPU 的层数 | 始终 |
| `-ts` | 每设备的张量分割 | 仅多 GPU（`nd > 1`） |
| `-ot` | 张量缓冲类型覆盖（正则匹配模式） | 仅当部分层溢出到 CPU/下个设备时 |

### 相关参数

| 参数 | 作用 |
|------|------|
| `-fit off` | 禁用 auto-fit（除调试外不建议使用） |
| `-fitt` / `--fit-target` | 每设备的显存余量（MiB）。多设备用逗号分隔。默认值：`1024`。示例：`-fitt 2048` 或 `-fitt 2048,512`。 |
| `-fitc` / `--fit-ctx` | `--fit` 允许设置的最小上下文大小。默认值：`4096`。设为大值（如 `999999`）可有效禁用 context 缩减。 |
| `-fitp` / `--fit-print` | 仅打印内存分布，不执行 fitting。输出格式为制表符分隔的：`<设备> <模型 MiB> <上下文 MiB> <计算 MiB>` |

---

## 典型工作流

### 1. 全自动（默认）

```bash
llama-server -m model.gguf
```

`--fit` 默认开启。无需其他参数。

### 2. 固定 context 大小，其他自动适配

```bash
llama-server -m model.gguf -c 8192
```

`--fit` 跳过 context 缩减，但仍会调整 `-ngl`、`-ts` 和 `-ot`。

### 3. 计算一次，重复使用

```bash
# 只运行一次 fit
llama-fit-params --model model.gguf > fitted-args.txt

# 每次都使用相同的配置
cat fitted-args.txt | xargs llama-server --model model.gguf
```

### 4. 调整安全余量

```bash
# 留 2 GiB 余量而非默认的 1 GiB
llama-server -m model.gguf -fitt 2048

# 两张 GPU 使用不同余量
llama-server -m model.gguf -fitt 2048,512
```

### 5. 预览——查看内存使用

```bash
llama-fit-params --model model.gguf -fitp on
```

输出示例：
```
CUDA0 17904 384 898
Host  58259 0    12
```

### 6. 在脚本中捕获 CLI 参数

```bash
ARGS=$(llama-fit-params --model model.gguf 2>/dev/null)
llama-server --model model.gguf $ARGS
```

---

## 故障排查

| 现象 | 可能原因 |
|------|---------|
| *"n_gpu_layers already set by user, abort"* | 传入了 `-ngl`。移除它让 `--fit` 决定，或用 `llama-fit-params` 查看它会选择什么值。 |
| *"not implemented for SPLIT_MODE_TENSOR, abort"* | `--split-mode tensor` 与 `--fit` 不兼容。需手动设置 `-c`。 |
| *"was unable to fit model into system memory... abort"* | 无 GPU 可用，且即使将 context 降到 `-fitc` 也无法容纳在主机内存中。尝试增加主机内存或使用更小的模型。 |
| *device did not report memory; --fit will not use it* | 后端未报告空闲/总内存。`--fit` 跳过该设备。 |
| *运行时意外 OOM* | Fit 的是预估结果；实际内存使用受运行时因素影响。增大 `--fit-target` 余量。 |

---

## 内部实现说明

### 调用位置

```
common/common.cpp:1189       llama-server, llama-cli 等
tools/fit-params/fit-params.cpp:34   llama-fit-params（独立工具）
tools/llama-bench/llama-bench.cpp:973  llama-bench
```

### 内存探测机制

`common_get_device_memory_data`（`fit.cpp:29`）的内存探测流程：

1. 以 `no_alloc=true`、`use_mmap=false`、`use_mlock=false` 加载模型
2. 创建临时 `llama_context`
3. 调用 `llama_get_memory_breakdown()` 获取每个缓冲类型的模型/上下文/计算大小
4. 调用 `ggml_backend_dev_memory()` 获取每设备的空闲/总内存
5. 释放临时模型和上下文

这样可以在**不实际分配张量数据**的前提下精确预估内存使用。

### 层分配算法

第 3 步的层分配使用**试位法**（regula falsi）来确定每张 GPU 能容纳多少层：

```
for each device (从后向前):
    low  = 0 层
    high = 所有剩余未分配层
    插值: step = delta * (target - mem_low) / (mem_high - mem_low)
    测试中点，替换 low 或 high 边界
    重复直到 delta <= 1
```

详见 `fit.cpp:550-556` 的注释。
