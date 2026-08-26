# Spike Notes — host-Alpine, Track A

**Date:** 2026-05-09
**Branch:** `claude/spike-pi-agent-android-NugAe` (historical; this work now lives on `main`)
**Workdir:** `spike-host-alpine/`
**Status:** all three empirical questions answered. Track A is viable.

## Setup

- Base image: `node:22-alpine` (Node 22.22.2, Alpine 3.23, musl libc, x86_64).
  - We attempted `alpine:3.21` (matching Kai's `RootfsDownloader.kt:17`) but the sandbox egress proxy's TLS-inspection CA blocks `apk add` even after appending the host CAs to `/etc/ssl/cert.pem`. `node:22-alpine` ships Node + npm pre-installed, so we sidestep `apk` entirely and still test musl. Alpine 3.23 vs 3.21 is a minor version delta — not material for this spike.
- Packages installed: `@mariozechner/pi-agent-core@0.73.1`, `@mariozechner/pi-ai@0.73.1`.
- `node_modules` size: **121 MB**, 195 packages, **zero native (`.node`) addons** — pure JS + WASM where used. The bulk comes from `pi-ai`'s bundled provider SDKs: `@anthropic-ai/sdk`, `@aws-sdk/client-bedrock-runtime`, `@google/genai`, `@mistralai/mistralai`, `openai`, `proxy-agent`, `undici`. Anthropic-only consumers pay full freight. Worth a follow-up: ask upstream whether providers can be peer-deps or split into subpath imports.
- npm install through the egress proxy required `NODE_EXTRA_CA_CERTS` pointing at the host's `swp-ca-production.crt` + `egress-gateway-ca-production.crt`. **Real devices won't need this**; it's a sandbox-only nuisance.

Reproduce: `docker build -t pi-filling-spike:alpine spike-host-alpine && docker run --rm pi-filling-spike:alpine`.

## Q1 — install + import on Alpine/musl

**Pass.** `npm install` completed in ~14s with no native rebuilds; `import { Agent } from "@mariozechner/pi-agent-core"` and `import { createAssistantMessageEventStream } from "@mariozechner/pi-ai"` resolved cleanly on musl. Driver ran to completion.

The original V1_SPEC concern about optional native deps (`@silvia-odwyer/photon-node`, `@mariozechner/clipboard`) does not apply to `pi-agent-core` + `pi-ai`. Those live under `pi-coding-agent`, which we deliberately did NOT depend on. **If we later add `pi-coding-agent`'s `createAgentSession()` to get the bundled tools, this question re-opens** for whatever it pulls in.

## Q2 — per-call API key injection via `getApiKey`

**Pass.** Driver registered `getApiKey: async (provider) => "spike-fake-key-abc123"`. Observed:

- Loop called the callback with `provider === "anthropic"` (matches the model's `provider` field, not `api`).
- The resolved key landed in the streamFn's `options.apiKey` field exactly as supplied.

The wiring lives in `agent-loop.ts:278-285` of pi-mono — `resolvedApiKey = (config.getApiKey ? await config.getApiKey(model.provider) : undefined) || config.apiKey`. So a per-call override beats env-var auth, and a missing override falls back to whatever the provider's normal auth resolution would do.

**Implication for v1:** we can hold the user's Anthropic key in app-private storage, hand it to `Agent` via `getApiKey`, and skip writing the key to env vars or to `~/.config/pi/`. Clean separation.

## Q3 — `agent.abort()` propagates to in-flight stream

**Pass.** Driver started a prompt against a streamFn that holds the response open and only emits an `error/aborted` event when its received `AbortSignal` fires. After 50ms the driver called `agent.abort()`. Observed:

- The streamFn received a non-null `options.signal` from the loop.
- That signal fired its `abort` event when `Agent.abort()` was called.
- The agent's transcript ended with an assistant message whose `stopReason === "aborted"` and `errorMessage === "user requested abort"`.
- Total elapsed time from prompt-start to settled abort: **51ms** (basically the timer plus event-loop ticks).

The signal lives on `Agent.activeRun.abortController` (`agent.ts:288-290`) and is plumbed through `runAgentLoop` into `streamFn(model, ctx, { ...config, apiKey, signal })` (`agent-loop.ts:281-285`). Tools, `beforeToolCall`, `afterToolCall`, and `transformContext` all receive the same signal — so a single `agent.abort()` cancels the LLM call AND any in-flight tool execution, provided each callee honors the signal.

**Caveat closed by Q3-real (below):** verified that the Anthropic SDK bundled by `pi-ai` does cancel an open SSE stream when the loop's `AbortSignal` fires — at least for the Anthropic provider. Other providers not tested.

## Q3-real — abort during a real Anthropic stream

**Pass.** Driver constructs an `Agent` with `getModel("anthropic", "claude-haiku-4-5-20251001")` and `getApiKey: () => process.env.ANTHROPIC_API_KEY`, prompts "Count from 1 to 200, one per line", then waits until 5 `text_delta` events have been observed before calling `agent.abort()`. Observed:

- `text_deltas_seen: 5` — the SSE stream was demonstrably open and feeding tokens.
- `text_chars_received_before_abort: 419` — we'd partially streamed the count.
- `final_stop_reason: "aborted"` — agent loop settled in the expected terminal state.
- `elapsed_ms: 1569` — total time from `prompt()` to `await` resolution was ~1.6s, after which no further deltas arrived. If the SDK had ignored the signal we'd have seen elapsed_ms grow to the full ~10–15s the model would have taken to count to 200.

This strongly suggests the bundled Anthropic SDK (`@anthropic-ai/sdk` ^0.91.1) honors `AbortSignal` end-to-end and closes the SSE stream on cancel. We did not verify at the TCP level — packet capture would close the question completely — but the JS-level evidence is sufficient for v1.

**One operational note:** the first abort attempt used a 600 ms fixed-delay timer and aborted *before* the first token arrived (Anthropic's first-byte latency is typically 500–1500 ms). The agent loop still settled with `stopReason: "aborted"`, but no streaming had occurred yet. **Practical implication:** UI cancel logic that fires within the first second of a request will mostly cancel the connection rather than mid-stream content. Both behaviors are correct, just different observed user experiences.

## Q4 — `pi-coding-agent` `createCodingTools` on musl

**Pass.** Adding `@mariozechner/pi-coding-agent@^0.73.0` to the install:

- `node_modules` grew from 121 MB → **186 MB** (+65 MB), 195 → **300 packages**.
- Native modules in the tree: `@mariozechner/clipboard-linux-x64-musl/clipboard.linux-x64-musl.node` (correct musl prebuild — npm picked it via the package's prebuild matrix). `koffi` ships ~20 architecture-prebuilds in the package, all unpacked.
- WASM modules: `@silvia-odwyer/photon-node/photon_rs_bg.wasm`, plus an example doom build (irrelevant).
- `import("@mariozechner/pi-coding-agent")` cold-load took **~1.0 s** the first run, ~0.8 s on warm cache. `createCodingTools(process.cwd())` runs in <2 ms.
- Returns 4 tools: `["bash", "edit", "read", "write"]`. `createReadOnlyTools` would return `["find", "grep", "ls", "read"]`; `createAllTools` exposes all 7 keyed by name.

**Implication for v1:** Track A + `createCodingTools` is the path. We get the four tools we need (read/write/edit/bash) without owning their implementations or pulling in `createAgentSession()`'s opinions. `bash` is the v1 git-shell-out target — no need for a separate GitSync dep.

**Cold-start cost on phone:** 1 s import time on x86_64 desktop translates to ~3-5 s on a phone CPU inside proot. Not fatal for v1, but worth profiling. The biggest contributor is likely `pi-ai`'s eager-loading of every provider SDK at module init.

## E2E — agent loop end-to-end (Anthropic + createCodingTools)

**Pass.** `driver-e2e.mjs` creates a throwaway git repo, instantiates an `Agent` with `claude-haiku-4-5-20251001` and `createCodingTools(repoPath)`, then prompts:

> Append a new line to README.md that reads exactly: "Spike pass: end-to-end agent loop verified." Then commit the change with the message 'spike: e2e verification'.

Observed run:

```
[tool 1] read   {"path":"README.md"}
[tool 2] edit   {"path":"README.md","edits":[{"oldText":"…","newText":"…"}]}
[tool 3] bash   {"command":"cd /tmp/pi-e2e-xP7hvM && git add README.md && git commit -m \"spike: e2e verification\""}
{
  "pass": true, "elapsed_ms": 5163, "assistant_turns": 4, "tool_calls": 3,
  "readme_updated": true, "new_commit_made": true,
  "head_commit_message": "spike: e2e verification",
  "final_stop_reason": "stop"
}
```

The agent took the obvious shape: `read` → `edit` → `bash`. 4 assistant turns, 3 tool calls, 5.2 s wall, stopped naturally. Cost on `claude-haiku-4-5`: under 1¢.

**Validation note:** the e2e run was executed on the Ubuntu host (Node 22, glibc, host's git) rather than inside the Alpine container, because this dev sandbox's egress proxy blocks Alpine's package CDN so `apk add git` fails here. The agent-loop logic is identical regardless of libc; Q1 and Q4 already proved the Node packages work on musl. Users running on a normal machine will exercise the full Alpine path via `docker run … node driver-e2e.mjs`.

**One observation worth recording:** the agent's bash command prepended `cd /tmp/pi-e2e-xP7hvM &&` defensively, even though `createCodingTools(cwd)` is supposed to bind tools to that cwd. Suggests the bash tool's cwd is NOT the `cwd` arg — likely `process.cwd()`. Doesn't break anything (the agent compensated), but worth either passing `--cwd` to bash explicitly or running our wrapper from inside the repo dir.

## Verdict — Track A with `createCodingTools`

**Locked.** v1 will use:

- `@mariozechner/pi-agent-core` for the `Agent` class and loop.
- `@mariozechner/pi-ai` for LLM access (transitive via pi-agent-core).
- `@mariozechner/pi-coding-agent`'s `createCodingTools(cwd)` to get `read`, `write`, `edit`, `bash` as plain `AgentTool[]`.
- Our own thin code on top: API-key storage, system prompt, memory.md handling, abort wiring, eventual UI.

**Rejected paths:**
- Track B (subprocess `pi-coding-agent` CLI): RPC mode underspecified, TUI-first design, no scriptable single-prompt mode.
- `createAgentSession()` (full pi-coding-agent SDK): drags in `SessionManager`, `AuthStorage`, `ModelRegistry` opinions we'd rather own ourselves.
- Reimplementing tools: unnecessary; `createCodingTools` gives us what we need.

## Unknowns the spike did NOT close

1. **Other provider SDKs vs `AbortSignal`.** Anthropic confirmed; OpenAI / Google / Bedrock / etc. not tested. Out of scope for v1 (Anthropic-only).
2. **Cold-start cost on a phone CPU inside proot.** 186 MB of node_modules loaded ~1 s on x86_64 desktop. Realistic phone estimate: 3-5 s. Measure during Android port.
3. **`pi-ai`'s `proxy-agent` and `undici` deps on Alpine in real production.** Likely fine; not exercised through real network paths beyond the egress proxy here.
4. **TCP-level proof that aborted SSE connections actually close.** Strong JS-level evidence in Q3-real, no packet capture. Acceptable for v1.
5. **Single-provider footprint.** `pi-ai` eagerly imports every provider SDK at module init. 121 MB just for Anthropic feels heavy. Consider an upstream issue / lazy-loaded providers if phone footprint matters.

## Files in this spike

- `spike-host-alpine/Dockerfile` — Alpine + Node 22 + sandbox CA workaround.
- `spike-host-alpine/package.json` — pins `pi-agent-core`, `pi-ai`, `pi-coding-agent` `^0.73.0`.
- `spike-host-alpine/package-lock.json` — locks the resolved dependency tree so `npm install` in the image is reproducible.
- `spike-host-alpine/driver.mjs` — Q1, Q2, Q3 (mock streamFn). Hermetic, no API key needed.
- `spike-host-alpine/driver-extras.mjs` — Q4 (`createCodingTools` on musl) and Q3-real (Anthropic streaming + abort). Skips Q3-real if `ANTHROPIC_API_KEY` not in env.
- `spike-host-alpine/driver-e2e.mjs` — E2E: real agent reads a README, edits it, and `git commit`s. Requires `ANTHROPIC_API_KEY`.
- `spike-host-alpine/certs/` — gitignored. To reproduce inside this dev sandbox, run `cp /usr/local/share/ca-certificates/*.crt spike-host-alpine/certs/` before `docker build`. On a real Android device or any environment without an egress TLS-inspection proxy, this directory is unnecessary — delete the `COPY certs/*.crt …` block from the Dockerfile.

## Reproduce

Step-by-step instructions: [`spike-host-alpine/RUNBOOK.md`](./spike-host-alpine/RUNBOOK.md).

Quick version:

```sh
cd spike-host-alpine
docker build -t pi-filling-spike:alpine .
docker run --rm pi-filling-spike:alpine                          # Q1, Q2, Q3 (no API key)
docker run --rm pi-filling-spike:alpine node driver-extras.mjs   # Q4 only (no API key)
docker run --rm -e ANTHROPIC_API_KEY="$(cat ~/.anthropic-key)" \
    pi-filling-spike:alpine node driver-extras.mjs               # Q4 + Q3-real
docker run --rm -e ANTHROPIC_API_KEY="$(cat ~/.anthropic-key)" \
    pi-filling-spike:alpine node driver-e2e.mjs                  # E2E read+edit+commit
```
