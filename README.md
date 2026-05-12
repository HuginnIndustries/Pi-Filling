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
| 1.2b | Alpine container Dockerfile for the wrapper + tests | ✅ done (end-user `docker run` to verify) |
| 1.2c+ | Android app shell, key storage, GitHub auth, push | ⏳ next |
| 1.5 | Polish, observability, memory indexing | future |
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
        └── Wrapper around @mariozechner/pi-agent-core       Layer 3
            ├── createCodingTools(repoPath)
            │   → read, write, edit, bash
            └── memory.md round-trip via the same git repo
```

Each layer has a narrow contract with the next. If you want the full
walkthrough — what each layer owns, what it doesn't, how cancellation and
auth flow — read [`ARCHITECTURE.md`](./ARCHITECTURE.md).

## Quickstart — try the spike

The agent loop, the tool stack, and the abort-mid-stream behavior are
already verified by a self-contained Docker spike. You can reproduce all
five checks plus a real end-to-end run on your own machine.

```sh
git clone https://github.com/HuginnIndustries/Pi-Filling.git
cd Pi-Filling
git checkout claude/spike-pi-agent-android-NugAe   # main isn't open yet
cd spike-host-alpine
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
LICENSE                     MIT.

node-wrapper/               Layer 3 — one-process agent + JSONL RPC over stdio.
├── DESIGN.md                Protocol contract (read before writing a Layer 1 client).
├── src/wrapper.mjs          The wrapper itself (~270 LOC).
├── test/integration.mjs     Spawn-the-wrapper integration tests (7 tests).
└── package.json

android/
└── proot-bootstrap/         Layer 2 — proot + talloc cross-compile (vendored from Kai).
    ├── build-proot.sh
    ├── README.md            Prerequisites, quickstart, troubleshooting.
    └── VENDORED.md          What we copied, what we changed, when we re-vendor.

spike-host-alpine/          Stage-0 building-block proofs (regression tests).
├── Dockerfile
├── package.json
├── driver.mjs               Q1 install + Q2 auth + Q3 abort (mock streamFn)
├── driver-extras.mjs        Q4 createCodingTools + Q3-real Anthropic abort
├── driver-e2e.mjs           Real agent: read README → edit → git commit
└── RUNBOOK.md               Step-by-step setup / run / interpret
```

## What we depend on

| | |
|---|---|
| [Kai](https://github.com/TheAmericanMaker/Kai) | Android sandbox patterns we'll port. Apache-2.0. |
| [pi-mono](https://github.com/badlogic/pi-mono) (`pi-agent-core`, `pi-ai`, `pi-coding-agent`) | Agent runtime, LLM client, tools. npm dep, MIT. **Not forked.** |
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
