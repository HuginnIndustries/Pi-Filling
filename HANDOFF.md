# Project Context: Phone-First AI Coding Agent

> Handoff document from brainstorming thread, 2026-05-06. Preserved for posterity. The authoritative current contract is [V1_SPEC.md](./V1_SPEC.md), which supersedes any conflict with this document (notably the SAF and battery-whitelist inheritance claims, corrected after the CodeCarto read of Kai).

## Current status addendum — 2026-05-21

This file is historical context, not the live plan. Since the handoff:

- Track A won: v1 imports `@mariozechner/pi-agent-core` directly and uses `createCodingTools` from `@mariozechner/pi-coding-agent`; Track B (`pi-coding-agent` CLI subprocess) is closed.
- The Layer 3 Node wrapper exists in `node-wrapper/` and exposes JSONL RPC over stdio (`prompt`, `abort`, `state`, `shutdown`).
- `node-wrapper/test/smoke.mjs` provides hermetic protocol coverage; `npm test` runs `node --test` and passes 8/8 with no API key because real-provider integration self-skips when `ANTHROPIC_API_KEY` is absent.
- `node-wrapper/Dockerfile` builds and runs the wrapper suite on Alpine/musl; Docker verification passes 8/8.
- `spike-host-alpine/` remains as lower-level regression proof: Docker run verifies install/import, `getApiKey` plumbing, and abort behavior on musl; real Anthropic/e2e checks are available when a key is supplied.
- Still not done: Android app shell, proot bootstrap integration on-device, key storage, GitHub auth/push, and UI/control layer.

The original text below is kept to show where the project started; do not treat its branch names or next-step list as current.

## What we're building

An Android-first AI coding agent that runs **on the phone**, not as a thin client to a server. The agent edits files, runs git operations, and uses an LLM to do real software work. Long-term vision is a multi-device "agent fabric" with shared persistent memory, but **v1 is intentionally phone-only**.

The wedge / pitch is held private for now. Build first, market later. Privately, the direction is somewhere between "take your coding agent off your laptop" and "self-hosted shared brain for AI tools," but the actual one-liner emerges from building.

## Architectural foundation

The whole project rests on a single insight: **Kai already solved Android's hardest sandbox problems** (Alpine Linux via proot, foreground service to keep it alive, Storage Access Framework binds, battery optimization whitelist). We inherit Kai's bootstrap and run a Node-based agent (pi-mono) inside that Alpine sandbox. This collapses an enormous amount of "how do I run a real Linux agent on a phone" complexity into "reuse Kai's sandbox layer."

The second insight: **memory.md as a tracked file in a git-synced repo replaces the need for OB1, vector stores, CRDTs, or sync servers in v1.** Git is the sync protocol. Conflicts become an LLM problem, not a distributed-systems problem. This also re-frames the "push thread between devices" problem out of existence — there's no thread to migrate, just memory.md + repo state which is already git-synced. You spin up a fresh agent loop wherever you want to run.

## v1 Scope (LOCKED)

Tightest phone-only proof of concept. ~6-8 weeks solo.

**In:**
- Android app only. No iOS.
- Alpine Linux via proot (Kai's pattern), Node + npm installed inside Alpine, pi runs there
- File operations restricted to `/root` of the Alpine sandbox
- Git clone/pull/push to GitHub (single provider)
- Single LLM provider: Anthropic
- Memory as sectioned markdown file in the same git repo as code, manually round-tripped
- UI: pi-coding-agent's TUI via terminal OR pi-web-ui in a WebView, whichever is faster

**Out (explicitly named so we can say no when scope creep arrives):**
- Server-side agent (deferred to v2 phone+server pair)
- Tailscale transport
- GitHub-issue or chat-based async transport
- agentskills.io / SKILL.md skills loader
- Sandcastle integration for server sandboxing
- OB1 sync layer
- Hermes-style heartbeats
- Kai-style generative interactive UI screens
- iOS port or thin client
- MarkText editor integration
- Multi-LLM via LMRouter
- Vector search / sqlite-vec derived index (flagged for v1.5)

## Decisions locked

| Decision | Choice | Rationale |
|---|---|---|
| Platform | Android only | iOS has no proot path; v1 must inherit Kai |
| Runtime sandbox | Alpine + proot via Kai's bootstrap | Already solved, don't reinvent |
| Memory format (v1) | Sectioned markdown | Prototype-friendly, decide for real later |
| Memory index (v1.5) | SQLite + sqlite-vec derived from memory.md | Has merit, build when memory size demands |
| Pi dependency strategy | Spike both, then decide | One week building same toy use case as library AND as CLI subprocess |
| Skills loader | Out of v1 | ~1-2 weeks of work, defer until agent loop is proven |
| UI generation (Kai-style) | Out of v1 | Schema-driven UI engine before proving agent loop = wrong order |
| Pitch / branding | Defer | Emerges from building |

## Open / deferred decisions

- **Memory format final pick.** Sectioned markdown is v1 pragmatic. Real options for multi-device sync: JSONL append-only, timestamp-prefixed bullet list, or sqlite-derived index. Decide when phone+server pair work begins.
- **Pi consumption.** Spike Track A (`pi-agent-core` as npm library + thin socket wrapper) vs Track B (`pi-coding-agent` CLI as subprocess). Pick after 5 days based on which felt less painful. Document why.
- **UI surface.** Native Android Compose UI vs pi-web-ui in WebView vs terminal. Defer until agent loop runs end-to-end.
- **Naming and identity.** No project name yet.
- **Storage Access Framework binds.** Will inherit Kai's complexity here. Acknowledged risk.
- **License posture.** Pi-mono is MIT. Strong recommendation: depend on `@mariozechner/pi-agent-core` and `@mariozechner/pi-ai` as npm dependencies, **do not fork**.

## The Spike (first week of real work)

Build the same trivial use case two ways: "agent reads README.md, makes a one-line edit, commits, pushes."

- **Track A:** Import `pi-agent-core` as a library inside a Node wrapper. Expose a socket or HTTP endpoint that takes a prompt and runs the agent loop.
- **Track B:** Spawn `pi-coding-agent` CLI as a subprocess, feed prompt via stdin or file, parse stdout.

Run both inside Alpine on a real Android device. Pick the path that hurt less. Write a one-page memo on why.

## Concrete next steps (in order)

1. **Read Kai's source** for the Alpine/proot bootstrap, foreground service implementation, SAF binds, and battery whitelist code. This is v1's biggest dependency. Roughly half a day.
2. **Run CodeCartographer** on Kai, Hermes, and pi-mono to produce architecture maps and behavioral contracts. Validates CodeCarto on first-party repos AND gives concrete porting specs for Kai's Alpine layer. ~$5-10 in tokens.
3. **Read pi-agent-core's public API** to assess feasibility of Track A in the pi spike.
4. **Write a one-page v1 spec** capturing this contract in the project repo. This is the artifact you push back on when scope creep arrives.
5. **Begin the spike** described above.
6. **Initialize the project repo** with a wrapper Android app skeleton + Alpine bootstrap + npm install of pi-agent-core inside Alpine.

## Anti-scope reminders

These came up during brainstorming and are explicitly NOT v1:

- Server-side companion agent (v2)
- Multi-device shared memory beyond what git gives you for free (v2+)
- Cross-project / global memory (v2)
- Vector recall (v1.5)
- Skill marketplace integration (v2)
- iOS thin client (v2+, may never)
- LMRouter integration for multi-provider (v2)
- Generative UI a la Kai (v2 at earliest, possibly never)
- Sandcastle integration for sandboxed server execution (v2 with server pair)
- Hermes heartbeat-style autonomy (v2 with server pair)

## Known pain points to watch for

- **Android Storage Access Framework + proot binds** are painful. Kai handles them; we inherit them. Will affect Play Store policy decisions later.
- **Battery optimization whitelist** scares some F-Droid-aligned users. Acknowledged tradeoff.
- **Memory.md merge conflicts** between concurrent agents will happen even in v1 if user runs the agent on phone while editing memory.md elsewhere. Plan: format choice in v1.5 should be merge-friendly (JSONL append-only or timestamp-prefixed bullets).
- **memory.md doesn't scale past ~10k entries** without vector search. Acceptable for v1.
- **pi-coding-agent assumes interactive TUI flow.** If you go Track A (library), you're building your own loop. If Track B (subprocess), you're hacking around its interactivity model.

## Repos to include in the new thread

These are the repositories the new thread should have read/write access to:

| Repo | Why it matters for this project |
|---|---|
| `theamericanmaker/kai` | **Critical.** Source of the Alpine/proot Android sandbox pattern. v1 inherits its bootstrap, foreground service, SAF binds, battery whitelist. Read first. |
| `theamericanmaker/pi-mono` | **Critical.** The agent runtime. v1 depends on `@mariozechner/pi-agent-core` and `@mariozechner/pi-ai`. Don't fork; depend. |
| `theamericanmaker/gitsync` | **Important.** Git operations on Android. v1 needs clone/pull/push. May reuse or replace with simple shell-out to git inside Alpine. |
| `huginnindustries/codecartographer` | **Important.** Use it to map Kai, Hermes, and pi-mono architectures before porting decisions. Token spend is small. |
| `theamericanmaker/hermes-agent` | **Reference only for v1.** Server gateway, heartbeat, skills patterns. Becomes critical in v2 phone+server pair. Read after Kai. |
| `theamericanmaker/sandcastle` | **Reference only for v1.** Sandbox provider abstraction. Becomes relevant in v2 if server-side agent needs sandboxing. |
| `theamericanmaker/skills-anthropic` | **Reference only.** SKILL.md library. Becomes relevant when skills loader is built (post-v1). |
| `theamericanmaker/ob1` | **Reference only.** Shared-brain prior art. v1 explicitly does NOT use it; memory.md + git replaces it. Read for design context. |
| `theamericanmaker/lmrouter` | **Reference only.** Multi-LLM routing. v1 is Anthropic-only. Relevant in v2+. |
| `theamericanmaker/marktext` | **Optional.** Markdown editor. Possibly relevant for memory.md viewing/editing UI. Low priority. |
| `theamericanmaker/claude-plugins-official` | **Optional.** Claude Code plugin patterns. Relevant only if the project ever ships a Claude Code plugin. |

**Minimum repo set for the new thread to be productive:** Kai, pi-mono, GitSync, CodeCartographer.

**Recommended full set:** add Hermes, Sandcastle, OB1 for design context.

## Open promises from prior thread

- I (the assistant) committed to: read Kai's Alpine/proot internals, run CodeCarto on Kai/Hermes/pi-mono, read pi-agent-core's API surface. None of this was started before the handoff. The new thread should pick these up as steps 1-3 above.
- The user offered to provide a list of what Kai uses to support Alpine Linux if the assistant struggles to find it. New thread can take them up on that offer if needed.

## Working agreement carried over

- **Develop on branch:** `claude/ai-agent-app-brainstorm-9gIob` across all relevant repos (per environment instructions).
- **Don't fork pi-mono.** Depend as npm package.
- **Don't create a project named anything yet.** Identity emerges from building.
- **Don't create PRs unless explicitly asked.**
- **Don't push to branches other than the designated one.**
