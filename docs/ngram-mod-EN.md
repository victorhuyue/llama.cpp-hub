# n-gram Mod Speculative Decoding

## Overview

n-gram Mod is a built-in, draft-model-free speculative decoding method in llama.cpp. It predicts subsequent tokens by analyzing repeating patterns in already-generated text. No additional model files are required — simply enable it to use.

## Resource Usage

- **Memory**: ~16 MB, constant footprint, does not grow with context size
- **Compute overhead**: Minimal, based on rolling hash computation
- **Sharing mechanism**: All server slots share a single hash pool, allowing different requests to benefit from each other

## Use Cases

n-gram Mod delivers significant speedups when **repetitive content is abundant**:

- **Document revision and rewriting**: After asking the AI to generate a document, you find some sections don't meet expectations and request a modified version. Most of the content has already been generated, and n-gram Mod can reuse historical patterns to accelerate output.
- **Code refactoring**: The LLM rewrites existing code, where code structure and patterns repeat heavily.
- **Reasoning models**: When the model needs to repeat its reasoning process in the final answer.
- **Summarization**: The summary overlaps significantly with the source text.

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `--spec-ngram-mod-n-match` | 24 | Lookup length; small values are not recommended |
| `--spec-ngram-mod-n-min` | 48 | Minimum number of tokens for speculative decoding |
| `--spec-ngram-mod-n-max` | 64 | Maximum number of tokens for speculative decoding |

## Configuration Guide

### Dense Models

Dense models tend to accept shorter drafts at higher rates. Consider reducing the parameters to lower overhead:

```bash
llama-server ... --spec-type ngram-mod --spec-ngram-mod-n-match 24 --spec-ngram-mod-n-min 24 --spec-ngram-mod-n-max 48
```

### MoE Models

MoE models require longer drafts to benefit from speculative decoding. Keep the defaults or increase them:

```bash
llama-server ... --spec-type ngram-mod --spec-ngram-mod-n-match 24 --spec-ngram-mod-n-min 48 --spec-ngram-mod-n-max 64
```

### Combined Usage

n-gram Mod can be combined with other speculative decoding methods. Draft-free methods take higher precedence:

```bash
llama-server ... --spec-type ngram-mod,ngram-map-k4v
```

## Verifying Effectiveness

The server prints statistics after each run. Key metrics to watch:

- `draft acceptance rate`: Higher means better speedup
- `#acc tokens / #gen tokens`: Ratio of accepted tokens to generated tokens
- `dur(b,g,a)`: Duration of begin, generation, and accumulation phases

If the acceptance rate is consistently low (below 30%), the current workload has little repetition and speculative decoding may be worth disabling.
