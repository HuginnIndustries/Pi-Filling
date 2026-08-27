# Architecture

This document describes how Pi-Filling is structured and why. It's the
"someone walked into the room" doc — read this before reading code, and
re-read it before proposing structural changes.

For the binding scope contract, see [`V1_SPEC.md`](./V1_SPEC.md). This
document is the *how*; that one is the *what*.

## The two architectural bets

Everything else flows from these.

**Bet 1 — Inherit Kai's Android sandbox.** [Kai](https://github.com/TheAmericanMaker/Kai)
is an open-source Android app that runs Alpine Linux on the phone via
[termux/proot](https://github.com/termux/proot), with a foreground service
keeping the sandbox alive. This is the hard part of running a real Linux
agent on Android, and Kai already solved it: rootfs provisioning, native
binary cross-compile, foreground-service lifecycle, app-private storage
layout, OEM-kill recovery. We reuse Kai's `build-proot.sh` and the
patterns from its `LinuxSandboxManager` / `DaemonService` instead of
reinventing them.

**Bet 2 — Memory as a tracked file in a git-synced repo.** Multi-device
agent memory usually means a sync server, CRDTs, or a vector store. v1
ducks all of that: memory lives in a sectioned markdown file inside the
same git repo as the user's code. Git is the sync protocol. Concurrent
edits become merge conflicts that the LLM resolves on the next run.
Crossing devices stops being a "transfer the conversation thread" problem
and becomes "spin up a fresh agent loop wherever". This is intentionally
the dumb solution; we'll evolve it when it actually breaks.

## The three layers

```
┌─────────────────────────────────────────────────┐
│  Android app shell                              │   Layer 1
│  - Compose UI (TBD: TUI / WebView / native)     │   ours, post-spike
│  - Foreground service (Kai's pattern)           │
│  - API key storage (app-private)                │
│  - Runs the wrapper, surfaces events            │
├─────────────────────────────────────────────────┤
│  Alpine sandbox (proot)                         │   Layer 2
│  - Alpine 3.21 minirootfs (Kai's)               │   inherited from Kai
│  - termux/proot, cross-built (build-proot.sh)   │
│  - Node 22 + git, installed via apk             │
│  - User's git clones live in /root              │
├─────────────────────────────────────────────────┤
│  Node agent runtime                             │   Layer 3
│  - @earendil-works/pi-agent-core (Agent class)  │   depend, don't fork
│  - @earendil-works/pi-ai (Anthropic SDK)        │
│  - @earendil-works/pi-coding-agent              │
│      → createCodingTools(cwd)                   │
│        → read, write, edit, bash                │
│  - Our wrapper: socket/HTTP listener,           │   ours
│    memory.md round-trip, system prompt,         │
│    abort wiring                                 │
└─────────────────────────────────────────────────┘
```

Each layer has a narrow contract with the one above and below.

### Layer 1 — Android shell

Owns: device permissions, key storage, foreground service, UI surface, the
process that launches the wrapper.

Talks to Layer 2 via: `proot` invocation that hands the wrapper a working
directory, an API key (from app storage → env or stdin), and a starting
prompt.

What it doesn't own: the agent loop, tool implementations, memory format.

### Layer 2 — Alpine sandbox

Owns: a Linux-shaped userland on the phone where Node and git can run
without Android-isolation surprises, with predictable paths under `/root`
and a mount of the user's chosen project directory.

Talks to Layer 1 via: standard process I/O. The sandbox doesn't care that
it's on Android; it just runs Linux processes and writes Linux files.

What it doesn't own: anything LLM- or memory-related. It's purely a
runtime container.

### Layer 3 — Node agent runtime

Owns: the agent loop itself, the tool implementations, the LLM client, the
event stream, the abort signal plumbing. Almost all of this is inherited
from `pi-mono` packages. Our additions are thin:

- A wrapper `Agent` instance configured with `getApiKey` (resolves from
  Layer 1's storage), tools (`createCodingTools(repoPath)`), and a system
  prompt.
- `memory.md` read/write at boundaries: load before `prompt()`, write
  after `agent_end`, commit via the bash tool the agent already has.
- A small RPC surface (socket or HTTP) so Layer 1 can `prompt` /
  `subscribe` / `abort` without spawning a new Node process per turn.

What it doesn't own: anything device-specific.

## The host-capability channel (designed, not built)

Today the stdio protocol is effectively one-directional. Layer 1 issues requests
(`prompt`, `abort`, `state`, `shutdown`); Layer 3 answers them and pushes events.
Layer 3 can *tell* Layer 1 things, but it cannot *ask* Layer 1 for anything.

That asymmetry is the thing standing between this app and every capability a
phone has and a desktop does not. The agent runs inside a proot rootfs with no
bridge to Android: it cannot speak, notify, read the clipboard, open the share
sheet, take a photo, or vibrate — not because those are hard, but because
nothing carries the request outward.

**The design:** a reverse RPC. Layer 3 emits a `host_request` naming a
capability and its parameters; Layer 1 executes it natively and replies with a
`host_response` correlated by id. Same JSONL framing, same stdio, opposite
direction.

```jsonc
// Layer 3 → Layer 1
{"host_request": {"id": 7, "capability": "tts.speak", "params": {"text": "…", "rate": 1.0}}}
// Layer 1 → Layer 3
{"host_response": {"id": 7, "ok": true, "result": {"utteranceId": "…"}}}
```

Design constraints that fall out of the trust model:

- **Capabilities are allow-listed by Layer 1, never by name from the guest.** The
  agent runs model-chosen code; an open-ended "run this Android intent" channel
  would hand it the phone. Layer 1 exposes a fixed, reviewed set.
- **Every capability is refusable.** An unknown or disabled capability returns a
  structured error, and the agent must degrade rather than fail.
- **Anything with a real-world side effect is opt-in and persisted.** Speaking
  aloud is the worked example: harmless on a desktop, consequential when the
  phone is in a pocket in a meeting.

**Why this matters more than protocol parity with desktop pi.** Widening the
protocol to expose pi's sessions, model switching and usage totals is worth
doing, but it is catching up. The host-capability channel is the part with no
desktop equivalent, and it is what makes the answer to "why run an agent on a
phone" something other than "because you can".

### First capability: text to speech

Prior art exists and is ours:
[`pi-termux-android-voice`](https://github.com/TheAmericanMaker/pi-termux-android-voice)
already solves agent speech for Termux as a pi **extension**, registering
assistant-callable tools (`android_tts_speak`, `android_tts_config`) and slash
commands (`/say`, `/voice-auto on|off|status`, `/voice-doctor`, `/voice-stop`),
with persisted rate and pitch.

The extension pattern ports unchanged — pi-coding-agent supports extensions and
Layer 3 bundles it. Only the bottom layer changes: `execFile("termux-tts-speak")`
becomes a `host_request`, because our guest has no Termux:API and never will.

That substitution is an upgrade rather than a workaround, and that project's own
architecture notes say so — its "future improvement" list names *"a small Android
companion app/service that calls Android `TextToSpeech.stop()`"* as the fix for
problems it could not solve. Layer 1 is that app:

| Unsolved in the Termux version | Why Layer 1 solves it |
|---|---|
| No reliable stop; an empty utterance is a best-effort flush | `TextToSpeech.stop()` is a real API |
| Long replies cannot be chunked safely | `UtteranceProgressListener` gives real completion callbacks |
| Requires the Termux:API app plus `pkg install termux-api` | No dependency; Layer 1 *is* the Android app |
| A subprocess per utterance | One long-lived `TextToSpeech` instance |

### Keeping the upstream door open

The channel is Pi-Filling's, not pi's — for now. pi's `ExtensionAPI` offers
lifecycle hooks, commands and tools but **no host surface**, so an extension can
only reach the world through Node itself. That is precisely why the Termux
implementation shells out to a Termux:API binary, and precisely why that approach
cannot survive inside proot.

The gap is not ours alone: any pi embedded in a native app — Electron, VS Code, a
mobile shell — has the same wall and works around it with packaging-specific
hacks. That makes this a plausible upstream proposal later, but a proposal is
stronger after something has shipped than before.

So the channel is built here under three constraints that cost nothing now and
keep that option alive:

- capability names are **namespaced and host-neutral** (`tts.speak`, never
  `pifillingSpeak`);
- **no Android specifics leak into the wire format** — no intents, no Android
  types, no `TextToSpeech` fields;
- the extension's **transport is isolated behind a single module**, so pointing
  it at a future `pi.host.request(...)` is a one-file change rather than a
  rewrite.

The voice extension itself is **vendored into this repo** rather than shared with
`pi-termux-android-voice`. We are replacing its transport outright and will grow
phone-specific behaviour Termux has no use for, so a shared upstream would be a
fork wearing a disguise. That project stays canonical for Termux; this is a
sibling sharing its design and command vocabulary, with attribution.

### Voice input is not a capability we build

Any Android dictation keyboard — Gboard voice typing, Samsung voice input — types
into an ordinary text field as normal text. The prompt box already accepts it, so
**voice input works today with no code**, which is the same conclusion
`pi-termux-android-voice` reached for Termux.

A dedicated mic button is worth roughly one tap over tapping the field and then
the keyboard's mic. It is an optimisation, not a missing capability, and should
not be confused for one.

Offline on-device recognition — [Vosk](https://github.com/alphacep/vosk-android-demo)
is the intended direction — is a genuine future capability, because it removes the
keyboard round-trip and works without network. It is deliberately *not* v1: the
platform already covers the case, and shipping a bundled acoustic model is a real
size and lifecycle commitment.

## Memory model

`memory.md` is a markdown file in the user's project repo with named
sections. The agent reads it before each prompt, may edit it during a
turn, and commits any changes alongside its code edits. v1 keeps it
human-readable and merge-friendly.

```markdown
## decisions
- 2026-05-08: chose pi-coding-agent's createCodingTools over rolling our own
- 2026-05-09: locked Track A after spike

## todo
- wire up memory.md round-trip
- start Android port

## context
The user is solo, time-boxed at 6-8 weeks for v1...
```

The agent system prompt instructs it to consult `memory.md` and update it
when meaningful state changes happen. Section names aren't fixed — the
agent picks and evolves them based on what's useful.

**Why not JSONL or SQLite?** Sectioned markdown is merge-friendlier than
JSONL when humans hand-edit it, and avoids needing a separate query
language for v1. v1.5 may add a SQLite-derived index for vector recall;
the index would be derived from `memory.md`, not the source of truth.

## What we depend on, what we own

| Concern | Source | Why |
|---|---|---|
| Android sandbox bootstrap | Kai (port the patterns) | Already solved; Apache-2.0 |
| Linux userland | Alpine 3.21 + Node 22 + git via apk | Lightweight; matches Kai |
| Agent loop | `@earendil-works/pi-agent-core` | npm dep; turn-by-turn drivable; clean abort plumbing. (Maintained successor to the deprecated `@mariozechner/*`.) |
| LLM client | `@earendil-works/pi-ai` | npm dep; bundled Anthropic SDK; eager-loads other providers (footprint cost we accept for v1) |
| Tools (read/write/edit/bash) | `@earendil-works/pi-coding-agent` `createCodingTools` | npm dep; saves us implementing 4 tools; clean `AgentTool[]` return |
| Wrapper, memory.md, RPC, key storage, UI | **Ours** | The actual product surface |

We **do not fork** any of the above — we depend via npm or port patterns.
Forking creates maintenance debt we can't afford pre-v1.

## Concurrency and cancellation

The `Agent` class owns one `AbortController` per active run, exposed via
`agent.abort()` and `agent.signal`. The signal threads through:

- `transformContext` (memory mutation hooks)
- `streamFn` (LLM HTTP/SSE call) — verified end-to-end against Anthropic's
  bundled SDK in [`SPIKE_NOTES.md`](./SPIKE_NOTES.md) Q3-real.
- `beforeToolCall` / `afterToolCall`
- Each tool's `execute` (so a long `bash` honors abort)

A user-facing cancel button must call `agent.abort()` and accept that
cancellation may land mid-stream (partial response in transcript) or
between turns (clean stop). Both are valid terminal states.

## Authentication

API keys never live on disk in env files or in environment variables that
persist. The pattern:

1. User enters their Anthropic key once via the Android app's UI.
2. App stores it in app-private storage (Keystore-backed when available).
3. When starting an agent run, the app passes the key to the wrapper via
   stdin or a socket message — never via process env or command args.
4. The wrapper registers `getApiKey: () => storedKey` on the `Agent`. The
   loop calls back per LLM request, so a rotated key takes effect on the
   next turn without restarting the run.

The spike uses `process.env.ANTHROPIC_API_KEY` only because there's no
Layer 1 yet. That's a temporary expedient and will move to the
stdin/socket path when Android lands.

## What's deliberately not here in v1

- **No agent-to-agent communication.** A single phone runs a single
  agent at a time. Multi-agent coordination is post-v2.
- **No background autonomy.** The agent only runs while the user is
  prompting. Heartbeat-driven autonomy à la
  [Hermes](https://github.com/TheAmericanMaker/hermes-agent) is post-v2.
- **No skill loader.** SKILL.md and dynamic skill installation happen
  later; v1's tool set is fixed at startup.
- **No vector recall.** `memory.md` is enough until it isn't.
- **No transport layer.** No Tailscale, no GitHub-issue inbox, no chat
  bridge. Phone-only in v1.

See [`V1_SPEC.md`](./V1_SPEC.md) for the full anti-scope list and
[`ROADMAP.md`](./ROADMAP.md) for when each of these comes back.

## Where to read code

```
node-wrapper/               Layer 3: Node wrapper around pi-agent-core
  src/wrapper.mjs           One-process agent + JSONL RPC over stdio
  test/smoke.mjs            Hermetic protocol tests (no key / Docker / git)
  test/integration.mjs      Real-Anthropic spawn tests (self-skip without a key)
  Dockerfile                Alpine/musl image; `docker run` runs the suite
  DESIGN.md                 Protocol + responsibilities (read first)
spike-host-alpine/          Stage-0 building-block proofs
  driver*.mjs               Preserved as regression tests
  RUNBOOK.md                Step-by-step reproduction
android/
  proot-bootstrap/          Layer 2 build pipeline (vendored from Kai)
    build-proot.sh          Cross-compile proot + talloc for 3 ABIs
    README.md               Prerequisites, quickstart, troubleshooting
    VENDORED.md             What was copied, what was changed
  app/                      Layer 1: Android app (not yet — Stage 1.2c)
```

Layer 2 isn't a directory — it's a runtime artifact built by Layer 1's
proot bootstrap and consumed by Layer 3's Node code at runtime.

## How to evaluate a proposed change

A change is well-scoped if it answers all four:

1. Which layer does it touch?
2. Does it cross a layer boundary, and if so, is the new contract
   explicit?
3. Does it commit us to something we said was post-v1 in
   [`V1_SPEC.md`](./V1_SPEC.md)?
4. If the change went away tomorrow, would the v1 acceptance test still
   pass?

If you can't answer #1 or #2 cleanly, the change is probably too big.
If #3 is yes, the change is out of v1 scope by definition.
If #4 is no, the change is on the critical path and deserves more care.
