# Wrapper design — Stage 1.1

The node-wrapper is Layer 3 from [`../ARCHITECTURE.md`](../ARCHITECTURE.md) made
concrete: a single Node process that owns one `Agent` instance and exposes a
small RPC surface so Layer 1 (the Android app) can drive it.

This document is the spec the implementation has to match. It's also the
contract Layer 1 implementers should read before wiring up calls.

## Responsibilities

The wrapper owns:

- **One `@mariozechner/pi-agent-core` `Agent` instance** for the lifetime of
  the process.
- **The bound repository.** A path supplied at startup; the wrapper `chdir`s
  into it before instantiating the agent, so `createCodingTools(repoPath)`'s
  `bash` tool runs `git`, etc. in the right place. (See SPIKE_NOTES.md for
  the cwd quirk that motivates this.)
- **The system prompt** — composed from a configurable base plus an optional
  `memory.md` injection (see below).
- **API-key resolution** via `getApiKey: () => keyFromEnv`. Layer 1 supplies
  the key via the `ANTHROPIC_API_KEY` env var when spawning the wrapper.
- **The JSONL protocol** described below.
- **Run lifecycle.** At most one prompt is in flight at a time. Concurrent
  `prompt` calls are rejected with an error response.

The wrapper does **not** own:

- Tool implementations (those come from `createCodingTools`).
- Provider auth UI, key rotation, persistence (Layer 1).
- Memory.md merge resolution beyond loading and pre-pending — the agent
  itself updates and commits via its bash/edit tools.
- Any UI rendering.

## Process model

```
Layer 1 spawns:                  → wrapper.mjs --repo /path/to/repo
                                   ANTHROPIC_API_KEY=... in env

stdin  (Layer 1 → wrapper)       JSONL requests
stdout (wrapper → Layer 1)       JSONL responses + event pushes
stderr (wrapper → Layer 1)       Human-readable logs (free-form, ignore for protocol)
```

When stdin closes the wrapper performs a clean shutdown (aborts any
in-flight run, awaits agent settlement, exits 0).

## Protocol

Newline-delimited JSON in both directions. Every message is a single line
of UTF-8 JSON terminated by `\n`.

### Requests (Layer 1 → wrapper)

```jsonc
{"id": <number>, "method": "<name>", "params": <object>}
```

`id` is any unique number per call; the wrapper echoes it on the matching
response. `params` is method-specific.

#### `prompt`

Start a new agent run.

```jsonc
{"id": 1, "method": "prompt", "params": {"text": "summarize the last three commits"}}
```

The wrapper responds when the run *starts* (not when it finishes — finishing
is signaled via the `agent_end` event push):

```jsonc
{"id": 1, "result": {"started": true}}
```

Or if a run is already in flight:

```jsonc
{"id": 1, "error": {"code": "busy", "message": "agent already processing"}}
```

#### `abort`

Cancel the current run, if any.

```jsonc
{"id": 2, "method": "abort"}
{"id": 2, "result": {"aborted": true}}
```

If no run is active, `result.aborted` is `false`. The agent settles via
the normal `agent_end` event with `stopReason: "aborted"`.

#### `state`

Snapshot the current agent state. Useful for debug and for Layer 1
re-attaching after a UI restart.

```jsonc
{"id": 3, "method": "state"}
{"id": 3, "result": {
  "isStreaming": false,
  "messageCount": 12,
  "pendingToolCalls": [],
  "errorMessage": null,
  "model": "claude-haiku-4-5-20251001",
  "repoPath": "/root/myrepo"
}}
```

#### `shutdown`

Request a clean shutdown. Equivalent to closing stdin.

```jsonc
{"id": 4, "method": "shutdown"}
{"id": 4, "result": {"shuttingDown": true}}
```

The wrapper emits any in-flight events, then exits 0.

### Event pushes (wrapper → Layer 1)

Every agent event is forwarded with a small envelope:

```jsonc
{"event": "<type>", "data": <event-body>}
```

`type` and `data` are the `AgentEvent` types defined in
`@mariozechner/pi-agent-core/types.ts`:

- `agent_start`, `agent_end`
- `turn_start`, `turn_end`
- `message_start`, `message_update`, `message_end`
- `tool_execution_start`, `tool_execution_update`, `tool_execution_end`

Layer 1 should treat any event-type it doesn't know as forward-compatible
noise (just log + ignore).

#### Wrapper-level event additions

The wrapper also emits these synthetic events that aren't part of pi-agent-core:

```jsonc
{"event": "wrapper_ready", "data": {"protocolVersion": 1, "model": "...", "repoPath": "...", "hasMemory": false}}
```

Emitted exactly once after the agent is constructed and before any request
can be processed. Layer 1 should wait for this before sending its first
`prompt`. `hasMemory` reports whether a `memory.md` was found in the repo
and folded into the system prompt (see "memory.md handling" below).

```jsonc
{"event": "wrapper_error", "data": {"phase": "<phase>", "message": "<text>"}}
```

Emitted when the wrapper itself errors — bad config, unhandled exception,
provider auth failure that bubbled up out of the loop, etc. Doesn't
necessarily mean the wrapper is dying; check the next event.

## CLI

```
node src/wrapper.mjs --repo <repoPath> [--model <id>] [--system-prompt <path>]
```

| Flag | Meaning | Default |
|---|---|---|
| `--repo` | Absolute path to the bound git repository. Must exist; will not be created. | required |
| `--model` | Anthropic model id known to `pi-ai`'s registry. | `claude-haiku-4-5-20251001` |
| `--system-prompt` | Path to a file whose contents replace the default base prompt. The file's contents are used verbatim; memory.md injection (if any) is appended after. | none (use built-in default) |

### Environment

| Var | Meaning | Required |
|---|---|---|
| `ANTHROPIC_API_KEY` | API key used by `getApiKey` for every LLM call | yes |
| `WRAPPER_LOG_LEVEL` | `silent` / `error` / `info` / `debug` for stderr verbosity | `info` |

## memory.md handling

On wrapper start, after `chdir(repoPath)`:

1. If `${repoPath}/memory.md` exists, its contents are pre-pended to the
   system prompt under a delimiter:
   ```
   <prior_memory>
   …contents…
   </prior_memory>
   ```
2. The agent is told (via the base prompt) that it owns `memory.md` —
   should consult it, update it as the user's goals or context shift,
   and commit it alongside other changes.
3. The wrapper does **not** re-read memory.md mid-run. If the agent
   edits it via the `edit` tool, those edits aren't reflected in *this*
   run's context — they show up next run when the wrapper reloads.
   This is intentional: keeps the wrapper logic trivial, lets the agent
   own its own memory updates within a run via tool calls.

## Concurrency rules

- One prompt at a time. The wrapper rejects `prompt` requests while a
  run is active.
- `abort` is idempotent and safe to call when no run is active.
- `state` is always safe.
- `shutdown` is sticky — once requested, further requests are rejected
  with `{"code": "shutting_down"}`.

## Process exit codes

| Code | Meaning |
|---|---|
| 0 | Clean shutdown (stdin closed or `shutdown` called) |
| 1 | Bad CLI args or missing `ANTHROPIC_API_KEY` |
| 2 | Bad `--repo` path (doesn't exist or isn't a directory) |
| 3 | Failed to construct agent (model not in registry, etc) |
| 4 | Unrecoverable error during run lifecycle |

## What's deliberately not in the wrapper

- **Multi-prompt queueing.** Layer 1 can use `Agent.steer` / `Agent.followUp`
  semantics if it needs them; for v1 we just say "one prompt at a time."
- **Persistent transcript storage.** The agent's `state.messages` lives in
  RAM only. Layer 1 owns persistence if it wants it (using `state` to
  snapshot).
- **Reconnection across wrapper restarts.** Stage 2 territory.
- **Multiple repos in one wrapper.** One wrapper, one repo. If Layer 1
  wants to switch repos it spawns a new wrapper.
- **Custom tool registration over RPC.** Tools are fixed at startup
  (`createCodingTools`). Skill/tool dynamism is post-v1.

## Tests

Two suites live under `test/`, both auto-discovered by `node --test` —
which is all `npm test` runs.

- **`test/smoke.mjs` — hermetic, seven tests.** Spawns the wrapper with a
  deliberately fake `ANTHROPIC_API_KEY` and exercises only paths that never
  touch the network: the `wrapper_ready` handshake (protocol version, repo
  path, `hasMemory`), the `state` snapshot, `memory.md` detection, rejection
  of empty-`text` prompts (`bad_params`) and unknown methods
  (`unknown_method`), an idle `abort` (`aborted: false`), and clean
  `shutdown` (exit 0). No API key, Docker, or git required, so it runs
  anywhere.
- **`test/integration.mjs` — real Anthropic.** Spawns the wrapper and drives
  a real `claude-haiku-4-5` run: edit + commit, abort mid-stream, `memory.md`
  injection, and the `busy` rejection. It reads `ANTHROPIC_API_KEY` from the
  env and **self-skips (exits 0) when it's absent**, so the default
  `npm test` stays free and offline.

With no key set, `node --test` reports **8/8 passing** — the seven smoke
tests plus the integration file, which self-skips. Supply a key
(`ANTHROPIC_API_KEY=… npm test`) to additionally run the integration suite
(~$0.02–$0.05 on `claude-haiku-4-5`).

### On Alpine/musl

[`Dockerfile`](./Dockerfile) builds a `node:22-alpine` image (`apk add git`,
`npm install --omit=dev` against the committed `package-lock.json`, then the
wrapper and tests) whose default `CMD` is `node --test`. `docker build` +
`docker run` executes the suite inside the same musl userland the wrapper
will see under Kai's proot, and passes **8/8** with no key. Forward a key
with `docker run -e ANTHROPIC_API_KEY …` to exercise the integration suite
there too.

## Reference

- pi-agent-core `Agent` class:
  `theamericanmaker/pi-mono/packages/agent/src/agent.ts`
- Event types:
  `theamericanmaker/pi-mono/packages/agent/src/types.ts` (`AgentEvent`)
- Tools used: `createCodingTools(cwd)` from
  `@mariozechner/pi-coding-agent`, see
  `theamericanmaker/pi-mono/packages/coding-agent/src/core/tools/index.ts`
