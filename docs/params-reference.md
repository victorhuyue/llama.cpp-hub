# llama.cpp `/v1/chat/completions` 实用参数参考

## reasoning_control — 实时打断模型思考

**作用**：允许在模型生成过程中，通过独立的 API 调用强行结束推理块（`<think>...</think>`），让模型立即输出最终答案。

**用法**：
1. 初始请求设置 `"reasoning_control": true`，同时配置推理预算采样器（`reasoning_budget_start_tag` / `reasoning_budget_end_tag`）。
2. 生成过程中并行调用 `POST /v1/chat/completions/control`，传入 completion 的 `id` 和 `action: "reasoning_end"`。

```
POST /v1/chat/completions
{
  "reasoning_control": true,
  "reasoning_budget_start_tag": "<think>",
  "reasoning_budget_end_tag": "</think>",
  "stream": true
}

→ SSE 中收到 "id": "chatcmpl-xxx"

POST /v1/chat/completions/control
{
  "id": "chatcmpl-xxx",
  "action": "reasoning_end"
}
→ 模型跳出推理，输出最终答案
```

**注意**：如果没有开启 `reasoning_control`，control 调用会返回 `"reasoning control not enabled for this completion"`。

---

## return_progress — 提示词处理进度

**作用**：在 stream 模式下，提示词（prompt）还没处理完时，提前向客户端发送进度事件，让前端可以展示实时进度条，避免白屏等待。

**触发时机**：prompt 处理期间定时发送。每个进度事件**不含 `content`**，只含 `prompt_progress` 字段。

**返回字段**：

| 字段 | 含义 |
|---|---|
| `total` | 提示总 token 数 |
| `cache` | 已缓存的 token 数 |
| `processed` | 已处理的 token 数 |
| `time_ms` | 已耗时（毫秒） |

**进度计算**：
- 整体进度：`processed / total`
- 实际计时进度：`(processed - cache) / (total - cache)`（剔除缓存命中部分）

**默认**：`false`

---

## timings_per_token — 逐响应性能统计

**作用**：在**每条响应**（包括 stream 的每个 chunk）中附带 `timings` 字段，包含提示处理和文本生成两个阶段的详细耗时数据。方便逐 token 监控推理性能。

**返回字段**：

| 字段 | 含义 |
|---|---|
| `prompt_n` | 提示处理的 token 数 |
| `prompt_ms` | 提示处理耗时（毫秒） |
| `prompt_per_token_ms` | 提示处理平均每 token 耗时 |
| `prompt_per_second` | 提示处理速度（token/秒） |
| `predicted_n` | 生成的 token 数 |
| `predicted_ms` | 生成阶段耗时（毫秒） |
| `predicted_per_token_ms` | 生成平均每 token 耗时 |
| `predicted_per_second` | 生成速度（token/秒） |
| `cache_n` | 缓存命中的 token 数 |
| `draft_n` | （推测解码时）草稿 token 数 |
| `draft_n_accepted` | （推测解码时）被接受的草稿数 |

**默认行为**：`false` — 此时 `timings` 只出现在**最后一次响应**中。设为 `true` 后每条响应都会带上。

---

## backend_sampling — GPU 后端采样

**作用**：将采样链从 CPU 卸载到 GPU 后端执行（通过 `llama_set_sampler()`），利用 GPU 并行能力加速采样过程。

**实验性功能**，当前有以下限制（不兼容时会自动降级）：
- 推测解码（speculative decoding）
- `n_probs` 预采样 logits
- grammar 采样器
- 推理预算采样器（reasoning budget）

**默认**：
- 普通请求：`false`（CPU 采样）
- 推测草稿模型（draft model）：`true`

**设为 `true` 后后端会自动判断兼容性，不满足条件时静默禁用，无需用户手动干预。**

---

> 所有参数均为 llama.cpp 扩展参数，非 OpenAI 标准 API 的一部分。
