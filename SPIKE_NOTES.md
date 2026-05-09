# Spike Notes — host-Alpine, Track A

**Date:** 2026-05-09
**Branch:** `claude/spike-pi-agent-android-NugAe`
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

**Caveat we did not verify:** real provider streamFns in `pi-ai` (Anthropic, OpenAI, etc.) bundle their own SDKs. We confirmed the loop hands the signal to them. We have NOT confirmed each bundled SDK actually aborts an open HTTP/SSE connection on signal. That's a separate verification, recommended before shipping a UI cancel button.

## Verdict — Track A vs `createAgentSession()`

**Track A wins for the spike-and-build phase.** `pi-agent-core`'s `Agent` class is a clean library surface: construct, register `getApiKey` + `streamFn` + tools, subscribe to events, call `.prompt()`, call `.abort()`. No subprocess, no TUI assumptions, no JSONL framing. This is the path the v1 spec wanted to confirm and the contract is solid.

**`createAgentSession()` (from `pi-coding-agent`) remains an option for v1 if and only if we want its bundled file/bash/edit tools out-of-the-box.** Trade-offs to evaluate when the time comes:

- ✅ Saves implementing `read`, `bash`, `edit`, `write` tools ourselves.
- ⚠️ Pulls in ~50–100 MB more dependencies and re-opens Q1 for `photon-node` (WASM, should be fine) and `clipboard` (native; will fail headless on Alpine but is optional).
- ⚠️ Imposes its own `SessionManager`, `AuthStorage`, `ModelRegistry` — adds opinionated infrastructure we'd otherwise own.

**Recommendation for v1 next step:** stay on Track A (`pi-agent-core` direct). Implement our own tiny `read` + `bash` + `edit` + `git` tools, sized for the v1 use case. Evaluate `createAgentSession` in a separate spike if/when we want skill loading or auth storage.

## Unknowns the spike did NOT close

1. **Real provider SDKs vs `AbortSignal`.** Verified the loop hands signal to streamFn; not verified each provider's HTTP layer aborts cleanly.
2. **Performance of `pi-ai` cold-start.** 121 MB of node_modules and a tree of provider SDKs likely costs hundreds of ms at first import. On a phone inside proot this matters. Measure during Android port.
3. **`pi-ai`'s `proxy-agent` dep behavior on Alpine.** Likely fine, not tested.
4. **Single-provider footprint.** `pi-ai` pulls every provider SDK regardless of which model you use. If footprint matters on phone, file an upstream issue or fork-strip `pi-ai`.

## Files in this spike

- `spike-host-alpine/Dockerfile` — Alpine + Node 22 + sandbox CA workaround.
- `spike-host-alpine/package.json` — pins `pi-agent-core` and `pi-ai` `^0.73.0`.
- `spike-host-alpine/driver.mjs` — three-question test driver, hermetic (no real LLM call).
- `spike-host-alpine/certs/` — gitignored. To reproduce inside this dev sandbox, run `cp /usr/local/share/ca-certificates/*.crt spike-host-alpine/certs/` before `docker build`. On a real Android device or any environment without an egress TLS-inspection proxy, this directory is unnecessary — delete the `COPY certs/*.crt …` block from the Dockerfile.
