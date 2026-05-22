# Roadmap

Stages, not dates. Dates depend on a solo developer and an unbounded number
of distractions; stages depend only on what's been validated.

The bar for moving from one stage to the next is **the previous stage's
acceptance test passes end-to-end on the target hardware, not just in a
proof-of-concept**. We optimize for "really works" over "ships fast."

## Stage 0 — Reading and spikes ✅ done

- Read Kai, pi-mono, Hermes via CodeCartographer.
- Lock the v1 contract in [`V1_SPEC.md`](./V1_SPEC.md).
- Host-Alpine spike: prove `pi-agent-core` + `createCodingTools` install
  and run on musl, that `getApiKey` and `AbortSignal` work end-to-end,
  and that the agent can read a README, edit it, and commit. Recorded
  in [`SPIKE_NOTES.md`](./SPIKE_NOTES.md) and reproducible via
  [`spike-host-alpine/RUNBOOK.md`](./spike-host-alpine/RUNBOOK.md).

**Outcome:** Track A locked. Building blocks verified.

## Stage 1 — v1 ⏳ in progress

The minimum useful product. One person, one phone, one Anthropic key, one
git repo at a time.

### Acceptance test

> User opens the app on their Android phone, points it at a GitHub
> repository, types `"summarize the last three commits in
> CHANGELOG.md and push the result"`, and within a minute the change is
> in the remote.

### Work breakdown

- **1.1 Wrapper.** ✅ done. See [`node-wrapper/`](./node-wrapper/) — one
  `Agent` instance per process, JSONL RPC (`prompt` / `abort` / `state`
  / `shutdown`) over stdio, `memory.md` injection at startup. Tested two
  ways: hermetic protocol smoke tests (`test/smoke.mjs` — no API key,
  Docker, or git) and real-Anthropic spawn tests (`test/integration.mjs`
  — edit + commit, mid-stream abort, `memory.md` injection) that self-skip
  when `ANTHROPIC_API_KEY` is absent. `npm test` (`node --test`) reports
  8/8 with no key. Protocol contract:
  [`node-wrapper/DESIGN.md`](./node-wrapper/DESIGN.md).
- **1.2 Android sandbox port.** Three sub-steps:
  - **1.2a** ✅ vendor `build-proot.sh` from Kai into
    [`android/proot-bootstrap/`](./android/proot-bootstrap/). Syntax
    + NDK-detection + git-clone-of-proot verified; full cross-compile
    needs a dev machine with reach to `dl.google.com` and `samba.org`
    (this dev sandbox blocks both).
  - **1.2b** ✅ Alpine container for the wrapper. The
    [`node-wrapper/Dockerfile`](./node-wrapper/Dockerfile) builds
    `node:22-alpine` + `apk add git` + the wrapper + tests; its default
    `CMD` is `node --test`. `docker build` + `docker run` executes the
    suite inside the same Alpine/musl userland the wrapper will see under
    Kai's proot, and passes **8/8** with no API key (the hermetic smoke
    tests; the integration suite self-skips). Forward a key with
    `docker run -e ANTHROPIC_API_KEY …` to exercise the real-Anthropic
    integration tests there too.
  - **1.2c** build a Compose-only minimal Android app with Kai's
    `DaemonService` pattern and `LinuxSandboxManager` equivalent.
    Bundle Node 22 + git apk installation in the bootstrap. Mount the
    user's selected directory as the workspace.
- **1.3 Key storage + auth.** App-private storage for the Anthropic API
  key (Keystore-backed where available). Pass to the wrapper at
  start-of-run via a non-env channel.
- **1.4 UI surface.** Decide between embedded TUI (run `pi-coding-agent`
  TUI in a terminal view) and Compose-native chat. Whichever ships
  first acceptably.
- **1.5 GitHub auth + push.** Personal access token or OAuth-device flow.
  HTTPS push from inside Alpine. SSH deferred until we have a place to
  store private keys safely.
- **1.6 First-run UX.** Pick a repo, paste a key, paste a token, smoke
  test that the agent can read and respond.

### Deliberately out of v1

Server-side companion, multi-device sync beyond what git gives, vector
recall, skill loader, multi-LLM, iOS, generative UI, agent autonomy
(heartbeats / cron). See [`V1_SPEC.md`](./V1_SPEC.md) §"Scope — Out".

## Stage 1.5 — Polish and observability

Stuff that v1 can ship without but that becomes painful within a week of
real use.

- **memory.md indexing.** When `memory.md` crosses ~10k lines, derive a
  SQLite + sqlite-vec index. The markdown stays the source of truth; the
  index is regenerable.
- **Cost meter.** Per-run token / dollar accounting surfaced in the UI.
- **Run history.** Lightweight transcript log on disk, browsable from the
  app.
- **Better error UX.** Today the agent emits `stopReason: "error"` with
  a string; we should surface it actionably (auth failure vs network vs
  rate limit vs tool failure).
- **OEM-kill warning.** Detect when the foreground service has been
  killed by an aggressive OEM ROM and surface that to the user (the
  silence is one of Kai's known footguns).

## Stage 2 — Phone-plus-server pair

When the phone is no longer enough — typically because the user wants
long-running tasks, agent autonomy, or shared work across multiple
devices.

### Acceptance test

> Agent runs on a server. The phone wakes up the agent with a prompt;
> the agent works for hours; the phone reconnects later and resumes
> mid-conversation.

### What that requires

- **Server-side agent.** Same `pi-agent-core` loop, deployed somewhere
  reachable.
- **Session resume contract.** Phone stores `session_id`; server
  persists state to SQLite (Hermes's pattern). Recovery from the phone
  is `/session/<id>/resume`.
- **Two-guard message dispatch.** Per Hermes's hard-won lesson: a queue
  + a command interceptor, both checking, to prevent double-send under
  retry. See `theamericanmaker/hermes-agent` `DECISIONS.md` D3.1.
- **Default-deny auth.** Server never trusts a phone-claimed user_id
  without re-checking. Hermes's D10.
- **Sandboxed execution.** Server-side tool calls need their own
  sandbox; this is where
  [Sandcastle](https://github.com/TheAmericanMaker/sandcastle) becomes
  relevant.
- **Heartbeat / autonomy.** Cron-driven background work + an explicit
  check-in endpoint the phone can poll.

This is roughly Hermes's territory, ported to our agent stack.

## Stage 2.5 — Multi-device shared brain

Once two devices can drive the same server, the question is what they
*share* and what they *don't*.

- **Shared `memory.md` already works** — it's just git. Devices push
  and pull through their normal workflow.
- **Cross-project / global memory.** Per-user memory that spans
  projects. Probably a separate file in a separate repo, but the model
  is unchanged.
- **Skill registry.** Skills (`SKILL.md` files from
  `theamericanmaker/skills-anthropic`) loaded at agent start. Server
  becomes the canonical store; phone fetches.

## Stage 3 — Multi-LLM, multi-platform, marketplace

Optional and almost certainly differently named by the time we get here.

- **Multi-provider routing** via
  [LMRouter](https://github.com/TheAmericanMaker/lmrouter). Anthropic
  remains default; Bedrock / OpenAI / Google as alternatives.
- **iOS thin client.** No proot on iOS, so iOS becomes a Layer 1 client
  to a Layer 2/3 hosted on the user's phone (Stage 2 server) or on a
  Stage 2-style cloud agent.
- **Skills marketplace.** Discover and install skills.
- **Generative UI.** Kai-style schema-driven UI generation, if we have
  the engineering bandwidth and a real use case.

## Things that will probably never happen

Listed so future-us doesn't keep relitigating them:

- **Building our own LLM provider.** Use the upstream SDKs, file issues,
  keep moving.
- **Forking pi-mono.** If something blocks us, we contribute upstream.
- **Building our own Android sandbox layer.** Kai's is good. If it
  diverges from our needs, port more or contribute upstream.
- **Becoming a general agent platform.** Pi-Filling is a coding agent.
  Other agent shapes can fork the runtime if they want.

## How this roadmap evolves

Stages 0 and 1 are concrete. Stage 1.5+ is directional and will be
re-scoped as we learn from v1 use. If you're reading this from the
future and the structure looks naive, that's expected — replace it with
what you now know.
