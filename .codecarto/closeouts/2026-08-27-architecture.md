# Closeout - architecture

## Summary

Pi-Filling is a three-layer stack that is *nested* at runtime rather than merely
layered: the Android app (Layer 1) ships the Node wrapper (Layer 3) as an APK
asset and installs it into an Alpine rootfs hosted by proot (Layer 2). The only
contract between layers is a small JSONL RPC over stdio - four methods, five
error codes, four wrapper exit codes, plus a forwarded pi-agent-core event
stream.

## What the map is confident about

- Dependency direction is acyclic; `wrapper/Protocol.kt` and
  `sandbox/SandboxState.kt` are the stable base.
- The agent loop lives entirely in Layer 3; Layer 1 is supervision, storage and
  UI. That is what makes the UI replaceable without touching agent behaviour.
- Durable state is small and well-bounded: per-provider encrypted credentials,
  the sandbox tree with a versioned marker, and `memory.md` inside the user's
  repository.

## What it is deliberately not confident about

Four open questions, none closable by reading: release/R8 shape, the
hand-mirrored provider table, foreground-service survival under pressure, and
the fact that the provider v1 targets has never made a live call from a device.

## Note on evidence quality

Unusually for an architecture pass, much of this is runtime-verified rather than
inferred - the sandbox, protocol and storage paths were exercised on a physical
handset in the same session, and six defects found there are recorded in
`android/VERIFICATION.md`. Where a conclusion rests only on reading, it is
marked `strong inference` rather than `observed fact`.

## Decisions Beyond Prompt

- Scoped this run to the architecture-only pipeline. The user asked for a structural read of what exists, not a port or rewrite, and codecarto_switch_pipeline can add contracts/protocols later without losing this phase.
