# Pi-Filling

> A phone-first AI coding agent. Working title; the real name will emerge as the product does.

Pi-Filling runs a Node-based coding agent inside an Alpine Linux sandbox on
your Android phone, talks to Anthropic, edits files in a git repo you control,
and commits the changes. No server, no cloud broker, no thin-client
relationship to a desktop. The agent runs where you're holding it.

## Status

Pre-1.0. **Not packaged for end users yet.** Building blocks are validated;
the Android shell is not yet built.

| Stage | What | Status |
|---|---|---|
| 0 | Reading + host-Alpine spike | ✅ done |
| 1.1 | Node wrapper (Layer 3) with stdio RPC + memory.md round-trip | ✅ done |
| 1.2a | Vendor `build-proot.sh` from Kai (Layer 2 build pipeline) | ✅ done |
| 1.2b | Alpine container Dockerfile for the wrapper + tests | ✅ done (builds + runs on Alpine/musl) |
| 1.2c | Android app shell scaffold (Compose + foreground service + proot + key storage + wrapper client) | 🟡 scaffolded — see [`android/`](./android/); not yet built on device |
| 1.3–1.5 | Key storage hardening, UI, GitHub auth + push, observability | ⏳ next |
| 1.5+ | Polish, memory indexing | future |
| 2 | Phone+server pair with session resume | future |

The full plan is in [`ROADMAP.md`](./ROADMAP.md).

## Why this exists

Two bets, both spelled out in [`ARCHITECTURE.md`](./ARCHITECTURE.md):

1. **Coding agents on your phone are useful, and are mostly a packaging
   problem.** Once you have Linux + Node + git running in a sandbox the OS
   doesn't kill, the agent loop itself is straightforward. The
   [Kai](https://github.com/TheAmericanMaker/Kai) project already solved
   the sandboxing; we inherit it.
2. **Agent memory is mostly a sync problem, and git is already a sync
   protocol.** v1 stores agent memory as a markdown file in the user's
   own git repo. Concurrent edits become merge conflicts the LLM resolves
   on the next run. We can replace this with something fancier when it
   actually breaks.

## How it works

```
Your phone
├── Android app (foreground service, key storage, UI)        Layer 1
└── Alpine Linux sandbox via proot                           Layer 2
    └── Node 22 + git                                          
        └── Wrapper around @earendil-works/pi-agent-core     Layer 3
            ├── createCodingTools(repoPath)
            │   → read, write, edit, bash
            └── memory.md round-trip via the same git repo
```

Each layer has a narrow contract with the next. If you want the full
walkthrough — what each layer owns, what it doesn't, how cancellation and
auth flow — read [`ARCHITECTURE.md`](./ARCHITECTURE.md).

## Quickstart — try the verified checks

The agent loop, the tool stack, and the abort-mid-stream behavior are
verified by self-contained Docker checks. Start with the wrapper
suite (the actual Layer 3 process the Android app will drive), then run the
older spike regressions if you want the lower-level pi-mono proofs.

### Wrapper smoke tests

```sh
git clone https://github.com/HuginnIndustries/Pi-Filling.git
cd Pi-Filling
git checkout claude/spike-pi-agent-android-NugAe   # main isn't open yet

cd node-wrapper
npm ci                                              # locked, reproducible install
npm test                                            # 23 passing, offline when no key is set
docker build -t pi-filling-node-wrapper .
docker run --rm pi-filling-node-wrapper             # same suite on Alpine/musl
```

`npm test` and the Docker image both run `node --test`. With no
`ANTHROPIC_API_KEY`, the hermetic smoke + hardening suites run (23 tests) and the
real-provider integration file self-skips. Forward a key with `docker run -e
ANTHROPIC_API_KEY …` to exercise the real Anthropic tests too. Lint and format
checks are `npm run lint` / `npm run format`. All of this runs in CI
([`.github/workflows/ci.yml`](./.github/workflows/ci.yml)) on every push and PR.

If you're running from WSL and Docker Desktop is up but `/var/run/docker.sock`
is permission-denied, call Docker Desktop's Windows CLI directly:
`"/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe" build …`.

### Host-Alpine spike regressions

```sh
cd ../spike-host-alpine
docker build -t pi-filling-spike:alpine .

# Mock-streamFn checks (no API key, no cost):
docker run --rm pi-filling-spike:alpine
docker run --rm pi-filling-spike:alpine node driver-extras.mjs

# Real Anthropic checks (~1¢ per run on claude-haiku-4-5):
$env:ANTHROPIC_API_KEY = "sk-ant-..."   # PowerShell — see RUNBOOK §6 for safer patterns
docker run --rm -e ANTHROPIC_API_KEY pi-filling-spike:alpine node driver-extras.mjs
docker run --rm -e ANTHROPIC_API_KEY pi-filling-spike:alpine node driver-e2e.mjs
```

Step-by-step with explanations and pass criteria:
[`spike-host-alpine/RUNBOOK.md`](./spike-host-alpine/RUNBOOK.md).

What each check answers:
[`SPIKE_NOTES.md`](./SPIKE_NOTES.md).

## Repo layout

```
ARCHITECTURE.md             How the layers fit together. Read first.
V1_SPEC.md                  What v1 ships. The scope contract.
ROADMAP.md                  v1 → v2+ stages.
SPIKE_NOTES.md              Spike findings (what we learned, what's locked).
HANDOFF.md                  Original brainstorming context. Preserved; superseded by V1_SPEC.
CONTRIBUTING.md             How development works today.
SECURITY.md                 How to report vulnerabilities.
CHANGELOG.md                Notable changes per version.
LICENSE                     MIT.
.editorconfig               Shared editor settings.
.github/                    CI workflow + Dependabot config.

node-wrapper/               Layer 3 — one-process agent + JSONL RPC over stdio.
├── DESIGN.md                Protocol contract (read before writing a Layer 1 client).
├── src/wrapper.mjs          The wrapper itself.
├── test/harness.mjs         Shared promise-based test driver.
├── test/smoke.mjs           Hermetic protocol tests (no API key / Docker / git).
├── test/hardening.mjs       Hermetic exit-code + malformed-input tests.
├── test/integration.mjs     Real-Anthropic spawn tests (self-skip without a key).
├── Dockerfile               Alpine/musl image; `docker run` executes the suite.
├── eslint.config.js         Lint config.    .prettierrc.json  Format config.
├── certs/                   Drop CA .crt here for TLS-inspection proxies (see Dockerfile).
├── package.json
└── package-lock.json        Pinned deps for reproducible installs.

android/                    Layer 1 + Layer 2.
├── app/                     Layer 1 — Android app scaffold (Compose, FGS, sandbox, wrapper client).
│   └── src/main/...         See android/README.md and android/KAI_PATTERNS.md.
└── proot-bootstrap/         Layer 2 — proot + talloc cross-compile (vendored from Kai).
    ├── build-proot.sh
    ├── README.md            Prerequisites, quickstart, troubleshooting.
    └── VENDORED.md          What we copied, what we changed, when we re-vendor.

spike-host-alpine/          Stage-0 building-block proofs (regression tests).
├── Dockerfile
├── package.json
├── package-lock.json        Pinned deps for reproducible installs.
├── driver.mjs               Q1 install + Q2 auth + Q3 abort (mock streamFn)
├── driver-extras.mjs        Q4 createCodingTools + Q3-real Anthropic abort
├── driver-e2e.mjs           Real agent: read README → edit → git commit
└── RUNBOOK.md               Step-by-step setup / run / interpret
```

## What we depend on

| | |
|---|---|
| [Kai](https://github.com/TheAmericanMaker/Kai) | Android sandbox patterns we'll port. Apache-2.0. |
| pi-mono (`@earendil-works/pi-agent-core`, `pi-ai`, `pi-coding-agent`) | Agent runtime, LLM client, tools. npm dep, MIT. **Not forked.** (Migrated from the now-deprecated `@mariozechner/*` namespace — see [`CHANGELOG.md`](./CHANGELOG.md).) |
| [Anthropic API](https://www.anthropic.com/) | The model. v1 is Anthropic-only. |

We do not fork either upstream. If something blocks us, we contribute
upstream. The full philosophy is in [`ARCHITECTURE.md`](./ARCHITECTURE.md)
under "What we depend on, what we own".

## Contributing

Pi-Filling isn't open for external pull requests yet — we're locking the
v1 architecture before opening the door. Issue reports, spike
reproductions on new platforms, and scope challenges are welcome.

[`CONTRIBUTING.md`](./CONTRIBUTING.md) has the details, including the
working-branch convention and what to expect when contributions reopen.

## License

[MIT](./LICENSE). Choice may be revisited before the first public
release; see [`V1_SPEC.md`](./V1_SPEC.md) decision log.

## Acknowledgments

This project would not be tractable for one person without
[Mario Zechner](https://github.com/badlogic)'s pi-mono and the
[Kai](https://github.com/TheAmericanMaker/Kai) project's open-source
Android sandbox work. Both are inspirations and dependencies; neither is
forked. The faster you ship, the more of the engineering you didn't have
to do.
