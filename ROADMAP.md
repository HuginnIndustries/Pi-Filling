# Roadmap

Stages, not dates. Dates depend on a solo developer and an unbounded number
of distractions; stages depend only on what's been validated.

The bar for moving from one stage to the next is **the previous stage's
acceptance test passes end-to-end on the target hardware, not just in a
proof-of-concept**. We optimize for "really works" over "ships fast."

## Stage 0 — Reading and spikes ✅ done

- Read Kai, pi-mono, Hermes via CodeCartographer. The tool is now also run
  against this repository — see [`.codecarto/`](./.codecarto/) for the
  architecture map, defect report, conventions and decision log.
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

### What to build next, in order

Sequenced by dependency and by what the evidence says is actually fragile, not by
feature appeal. Sources: the CodeCartographer
[defect report](./.codecarto/findings/defect-scan/defect-report.md) (13 findings,
2 high), the [architecture map](./.codecarto/findings/architecture/architecture-map.md),
and on-device verification.

**A. Harden the wrapper-death path.** Small, and it comes first because it is on
the path users hit most. Three findings compound into one weakness: a request can
be orphaned when the process exits during registration (3.1, high), nothing times
out to break the resulting hang (2.2), and the adjacent call site crashes the app
instead of failing the session (2.1, high). Convention **C04** now makes "guarded
and bounded" a rule; this is the work of making the code obey it.

**B. Stand up an Android test harness.** The defect report's severity
distribution is not a coincidence: the Node wrapper handles serialization
failure, backpressure, EPIPE and malformed input, while both high findings are in
Kotlin. The wrapper has 25 tests; `android/` has none and never has. Fixing A
without this leaves the mechanism that produced A intact. JVM/Robolectric first —
it needs no emulator and would have caught most of what the defect scan found by
reading.

**C. GitHub auth and push (1.5).** With A and B in place, this is the feature
work that completes Stage 1. Nothing else in the backlog moves the acceptance
test.

**D. First-run UX (1.6), then a release build.** The release build matters beyond
polish: no minified build has ever been produced, so R8's effect on the
serialization models is unknown, and that unknown grows more expensive the later
it is discovered.

**E. Then the medium defects and the claims that outrun the code.** Pin
`distributionSha256Sum`; add a cross-language check that the provider table and
the wrapper's `PROVIDERS` agree; either request StrongBox or soften
`SECURITY.md`; stop forwarding wrapper stderr to logcat in release; make the
`memory.md` delimiter guard case-insensitive; persist the provider selection.

**Deliberately not next:** consuming pi-mono's 31 unreleased commits. Several
touch the `openai-completions` path this project uses, but taking them means
building from source instead of the registry, which trades away `npm ci`
reproducibility and complicates F-Droid. Revisit when a specific fix is needed.

### Work breakdown

Status here is only ever what has been *verified*. On-device claims are backed
by [`android/VERIFICATION.md`](./android/VERIFICATION.md), which separates what a
run proved from what it did not.

- **1.1 Wrapper.** ✅ done. [`node-wrapper/`](./node-wrapper/) — one `Agent` per
  process, JSONL RPC (`prompt` / `abort` / `state` / `shutdown`) over stdio,
  `memory.md` injection, and a `--provider` flag (`anthropic` default, `ollama`
  for development). **25/25** hermetic tests with no API key; the real-provider
  integration tests self-skip without one. Contract:
  [`node-wrapper/DESIGN.md`](./node-wrapper/DESIGN.md).
- **1.2 Android sandbox port.** ✅ done, and verified on hardware.
  - **1.2a** ✅ `build-proot.sh` vendored from Kai and **actually run**: proot,
    its loaders and talloc cross-compiled for `arm64-v8a`, `armeabi-v7a` and
    `x86_64` against NDK r29.
  - **1.2b** ✅ Alpine container for the wrapper; the suite runs inside the same
    musl userland the wrapper sees under proot.
  - **1.2c** ✅ **built, installed and run on a Galaxy Z Fold 5 (Android 14).**
    The sandbox provisions end to end — Alpine 3.21.3, Node v22.23.2, npm 10.9.1,
    git 2.47.3, `uid=0` under proot — and an agent driven from the app UI edited
    a file and committed it. Six defects were found and fixed doing this; none
    were reachable from CI.
- **1.3 Key storage.** 🟡 mostly done.
  [`SecureKeyStore`](./android/app/src/main/kotlin/industries/huginn/pifilling/storage/SecureKeyStore.kt)
  stores one credential per provider, AES-256-GCM under an AndroidKeyStore key.
  Encryption-at-rest is *measured* on device (12-byte IV, plaintext + 16-byte
  tag, plaintext absent from storage). Two gaps remain: the key is passed to the
  wrapper via env at spawn rather than the stdin/socket handshake V1_SPEC wants,
  and nothing requests StrongBox or verifies hardware-binding, so `SECURITY.md`
  currently claims more than the code guarantees.
- **1.4 UI surface.** 🟡 decided and scaffolded. Compose-native chat, not an
  embedded TUI. Three screens: key entry with provider chooser, provisioning,
  session. Deliberately minimal — no tool-call rendering, diffs, cost meter or
  run history yet.
- **1.5 GitHub auth + push.** ❌ **not started, and the only thing standing
  between the current build and Stage 1's acceptance test.** The agent commits
  locally; nothing pushes. Needs a token flow, credential storage, and HTTPS push
  from inside Alpine.
- **1.6 First-run UX.** ❌ not started.

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
