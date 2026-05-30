# n-gram Mod 投机解码

## 简介

n-gram Mod 是 llama.cpp 内置的一种无草稿模型的投机解码方式，通过分析已生成文本中的重复模式来预测后续 token。不需要额外的模型文件，启用即可使用。

## 资源占用

- **内存**：约 16 MB，恒定占用，不随上下文增长
- **计算开销**：极低，基于滚动哈希计算
- **共享机制**：所有 server slot 共享同一个哈希池，不同请求可以互相受益

## 适用场景

n-gram Mod 在**重复内容较多**的场景下效果显著：

- **文档修改与重写**：要求 AI 生成一份文档后，发现部分内容不符合预期，要求修改并重新输出。此时大部分内容已经生成过，n-gram Mod 可以直接复用历史模式实现加速。
- **代码重构**：LLM 对已有代码进行改写，代码结构和模式大量重复。
- **推理模型**：模型需要在最终答案中重复推理过程时。
- **摘要生成**：摘要内容与原文存在大量重合。

## 参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--spec-ngram-mod-n-match` | 24 | 查找长度，不建议设置过小 |
| `--spec-ngram-mod-n-min` | 48 | 投机解码的最小 token 数量 |
| `--spec-ngram-mod-n-max` | 64 | 投机解码的最大 token 数量 |

## 配置建议

### 稠密模型（Dense Model）

稠密模型对短草稿的接受率较高，可以适当减小参数值以降低开销：

```bash
llama-server ... --spec-type ngram-mod --spec-ngram-mod-n-match 24 --spec-ngram-mod-n-min 24 --spec-ngram-mod-n-max 48
```

### MoE 模型

MoE 模型需要较长的草稿才能发挥投机解码的加速效果，建议保持默认值或增大：

```bash
llama-server ... --spec-type ngram-mod --spec-ngram-mod-n-match 24 --spec-ngram-mod-n-min 48 --spec-ngram-mod-n-max 64
```

### 混合使用

n-gram Mod 可以与其他投机解码方式混合使用，无草稿解码优先级更高：

```bash
llama-server ... --spec-type ngram-mod,ngram-map-k4v
```

## 效果验证

服务器运行结束后会输出统计信息，关注以下指标：

- `draft acceptance rate`：草稿接受率，越高说明加速效果越好
- `#acc tokens / #gen tokens`：实际接受 token 占生成 token 的比例
- `dur(b,g,a)`：各阶段耗时，用于评估开销

如果接受率偏低（低于 30%），说明当前场景重复内容较少，可以考虑关闭投机解码。
