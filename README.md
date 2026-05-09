# Pi-Filling

Working repository for a phone-first AI coding agent. No public name yet — identity emerges from building.

- **[V1_SPEC.md](./V1_SPEC.md)** — locked v1 contract. Read first. This is the artifact we push back on when scope creep arrives.
- **[SPIKE_NOTES.md](./SPIKE_NOTES.md)** — host-Alpine spike findings. Locks Track A (`pi-agent-core` + `createCodingTools`) as the v1 path.
- **[spike-host-alpine/RUNBOOK.md](./spike-host-alpine/RUNBOOK.md)** — reproducible step-by-step to build and run the spike yourself.
- **[HANDOFF.md](./HANDOFF.md)** — original brainstorming handoff. Preserved for context; superseded by V1_SPEC on conflicts.

## Status

Spike #1 complete (host Alpine, x86_64). Track A confirmed viable. Next: end-to-end use case (read README → edit → commit → push) against real Anthropic, still on host Alpine. Android port deferred.

## Working agreement

- Develop on `claude/spike-pi-agent-android-NugAe` until merged.
- Don't fork pi-mono — depend on `@mariozechner/pi-agent-core` and `@mariozechner/pi-ai` as npm packages.
- Don't create PRs unless explicitly asked.
- Don't pick a project name yet.
