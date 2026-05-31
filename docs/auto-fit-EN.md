# Auto-fit: automatic memory fitting

A common source of friction when running large models is finding the right arguments so that the model fits within available device memory without GPU out-of-memory (OOM) errors. The auto-fit feature attempts to solve this automatically.

When enabled (on by default), `--fit` measures the free memory on each device and adjusts unset parameters so that the entire model — weights, KV cache, and compute buffers — is projected to fit with a configurable safety margin.

---

## How it works

`--fit` runs at startup, *before* the model is fully loaded. It loads the model with `no_alloc` (allocate metadata only, no tensor data) to measure per-device memory requirements, then runs through four steps:

| Step | What it does |
|------|--------------|
| **1. Probe** | Loads the model with the current parameters and measures projected memory use per device. If the margin target is already met, it returns immediately — no changes needed. |
| **2. Reduce context** | If memory is insufficient, reduces `--ctx-size` (`-c`) to reclaim KV cache memory. Only applies when `-c` was not explicitly set by the user. Uses linear interpolation between the default and minimum context size to find the largest possible value that fits. |
| **3. Distribute layers** | Uses the **method of false position** (a root-finding algorithm) to determine how many layers each GPU can hold, back to front. Sets `--n-gpu-layers` (`-ngl`) and, for multi-GPU, `--tensor-split` (`-ts`). Dense layers are assigned to devices in contiguous slices; for MoE models, MoE tensors spill to system memory first. |
| **4. Fill remainder (MoE only)** | If the dense layers of a MoE model fit with room to spare, converts dense-only layers back to full layers front to back. Then attempts to squeeze in one additional partial layer using different overflow strategies: `UP` (only the FFN up-projection), `GATE` (up + gate), or `ATTN` (everything but FFN gate/up). |

For the detailed implementation, see `common/fit.cpp:153` (`common_params_fit_impl`).

---

## Parameters auto-fit can modify

| Parameter | CLI flag | Condition |
|-----------|----------|-----------|
| Context size | `-c` | Only when set to `0` (default) |
| GPU layers | `-ngl` | Only when `-ngl` is not explicitly set by user |
| Tensor split | `-ts` | Only when `-ts` is not explicitly set, and only for multi-GPU `layer` split mode |
| Tensor buft overrides | `-ot` | Only when `-ot` is not explicitly set, and only for partial-layer overflow |

### When `--fit` aborts (falls through to user parameters)

`--fit` does **not** silently override user-specified values. If any of the following are set, `--fit` skips adjusting that parameter (or aborts entirely):

```cpp
// fit.cpp:352 — user set -ngl explicitly:
if (mparams->n_gpu_layers != default_mparams.n_gpu_layers) {
    throw common_params_fit_exception("n_gpu_layers already set by user, abort");
}

// fit.cpp:343 — user set -c explicitly:
if (cparams->n_ctx == 0) {
    // adjust context...
} else {
    // "context size set by user -> no change"
}

// fit.cpp:359-364 — user set -ts explicitly:
if (mparams->tensor_split && mparams->tensor_split[id] != 0.0f) {
    throw common_params_fit_exception("tensor_split already set by user, abort");
}
```

These exceptions are caught gracefully — the program logs a warning and continues with the user-specified parameters.

### When `--fit` is unavailable

- **`--split-mode tensor`** (`-sm tensor`): `--fit` is not implemented for tensor parallelism and will abort with `"llama_params_fit is not implemented for SPLIT_MODE_TENSOR"`. You need to manually set `--ctx-size` to make the model fit.
- **`--split-mode row`** (`-sm row`): `--fit` cannot change weight allocation for row split mode.
- **Devices that don't report memory**: GPU-like backends that report 0/0 free/total memory are skipped.

---

## `llama-fit-params` — get the fit result as CLI arguments

`--fit` runs every time you start `llama-server` or `llama-cli`. The ~1-2 second overhead of probing memory is usually negligible, but there are cases where you want the result as reusable CLI arguments instead:

- **Reproducible deployments** — you want the same allocation every time
- **Explicit configuration** — you want to inspect and optionally tweak what `--fit` decided
- **Speed** — skip the probing step on subsequent launches

`llama-fit-params` calls the same `common_fit_params()` function internally (`tools/fit-params/fit-params.cpp:34`) but prints the resulting CLI arguments to stdout instead of loading the model.

### Usage

```bash
# Step 1: compute the optimal configuration
llama-fit-params --model model.gguf > args.txt

# Step 2: use those arguments in a subsequent launch
cat args.txt | xargs llama-server --model model.gguf
```

Output is a single line of CLI arguments, for example:

```
-c 4096 -ngl 48 -ts 48 -ot "blk\.14\.ffn_.*=CPU,blk\.15\.ffn_.*=CPU,..."
```

### Output parameters

| Output | Meaning | When printed |
|--------|---------|-------------|
| `-c` | Optimal context size | Always |
| `-ngl` | Number of layers offloaded to GPU | Always |
| `-ts` | Per-device tensor split | Only for multi-GPU (`nd > 1`) |
| `-ot` | Tensor buffer type overrides (patterns in regex) | Only when partial layers overflow to CPU/next device |

### Related flags

| Flag | Effect |
|------|--------|
| `-fit off` | Disable auto-fit entirely (not recommended unless debugging) |
| `-fitt` / `--fit-target` | Safety margin per device in MiB. Comma-separated for multi-device. Default: `1024`. Example: `-fitt 2048` or `-fitt 2048,512`. |
| `-fitc` / `--fit-ctx` | Minimum context size that `--fit` is allowed to set. Default: `4096`. Setting to a large value like `999999` effectively disables context reduction. |
| `-fitp` / `--fit-print` | Print memory breakdown without performing any fitting. Output is tab-separated: `<device> <model MiB> <context MiB> <compute MiB>` |

---

## Typical workflows

### 1. Fully automatic (default)

```bash
llama-server -m model.gguf
```

`--fit` is on by default. No other arguments needed.

### 2. Keep context, auto-fit everything else

```bash
llama-server -m model.gguf -c 8192
```

`--fit` will skip context reduction but still adjust `-ngl`, `-ts`, and `-ot`.

### 3. Compute once, reuse everywhere

```bash
# Only runs the fit once
llama-fit-params --model model.gguf > fitted-args.txt

# Reuse the same configuration on every launch
cat fitted-args.txt | xargs llama-server --model model.gguf
```

### 4. Tweak the margin

```bash
# Leave 2 GiB margin instead of the default 1 GiB
llama-server -m model.gguf -fitt 2048

# Or for two GPUs with different margins
llama-server -m model.gguf -fitt 2048,512
```

### 5. Dry run — inspect memory usage

```bash
llama-fit-params --model model.gguf -fitp on
```

Output example:
```
CUDA0 17904 384 898
Host  58259 0    12
```

### 6. Capture CLI arguments for a script

```bash
ARGS=$(llama-fit-params --model model.gguf 2>/dev/null)
llama-server --model model.gguf $ARGS
```

---

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| *"n_gpu_layers already set by user, abort"* | You passed `-ngl` explicitly. Remove it to let `--fit` decide, or use `llama-fit-params` to see what it would have chosen. |
| *"not implemented for SPLIT_MODE_TENSOR, abort"* | `--split-mode tensor` is incompatible with `--fit`. Set `-c` manually. |
| *"was unable to fit model into system memory... abort"* | No GPU available and even reducing context to `-fitc` doesn't fit in host RAM. Try increasing host RAM or using a smaller model. |
| *device did not report memory; --fit will not use it* | The backend doesn't report free/total memory. `--fit` skips that device. |
| *Unexpected OOM at runtime* | The fit is a projection; actual memory use depends on runtime factors. Increase `--fit-target` margin. |

---

## Internal implementation notes

### Where it's called

```
common/common.cpp:1189       llama-server, llama-cli, etc.
tools/fit-params/fit-params.cpp:34   llama-fit-params (standalone)
tools/llama-bench/llama-bench.cpp:973  llama-bench
```

### Probe mechanism

The memory probe (`common_get_device_memory_data` in `fit.cpp:29`) works by:

1. Loading the model with `no_alloc=true`, `use_mmap=false`, `use_mlock=false`
2. Creating a temporary `llama_context` from it
3. Querying `llama_get_memory_breakdown()` for per-buffer-type model/context/compute sizes
4. Querying `ggml_backend_dev_memory()` for free/total per device
5. Freeing the temporary model and context

This gives a precise projection of memory use without allocating the actual tensor data.

### Algorithm for layer distribution

The layer distribution (Step 3) uses the **method of false position** (regula falsi) to find how many layers fit on each device:

```
for each device (back to front):
    low  = 0 layers
    high = all remaining unassigned layers
    interpolate: step = delta * (target - mem_low) / (mem_high - mem_low)
    test mid-point, replace low or high bound
    repeat until delta <= 1
```

For more detail, see comments at `fit.cpp:550-556`.
