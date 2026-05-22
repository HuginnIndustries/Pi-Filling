# Contributing

Pi-Filling is in early development. The architecture and scope are being
locked before we open the door to wider contributions. **External pull
requests are not being accepted yet.** We'll update this file when that
changes.

In the meantime, the most useful contributions are:

- **Issues.** Bug reports, design questions, scope challenges, comparisons
  to prior art. File freely.
- **Reproductions.** If you run the wrapper suite from `node-wrapper/` or
  the spike from [`spike-host-alpine/RUNBOOK.md`](./spike-host-alpine/RUNBOOK.md)
  on a device or environment we haven't tested, the test output is genuinely
  useful data. For the wrapper, include `npm test`, `docker build`, and
  `docker run` output. For the spike, include `driver.mjs`,
  `driver-extras.mjs`, and `driver-e2e.mjs` output where applicable.
- **Discussion of the v1 scope.** Read [`V1_SPEC.md`](./V1_SPEC.md) and
  push back. Items in "Scope — Out" are deliberately deferred; if you
  think one is mis-categorized, say so.

## How development works today

- All work happens on `claude/spike-pi-agent-android-NugAe` until the
  spike phase wraps and a `main` branch is opened.
- Authoritative documents in priority order: `V1_SPEC.md`,
  `ARCHITECTURE.md`, `ROADMAP.md`, `SPIKE_NOTES.md`. `HANDOFF.md` is
  preserved historical context, superseded by `V1_SPEC.md` on conflicts.
- Commits use a short imperative subject, blank line, then a body that
  explains *why*. Feature/bug-fix labels in the subject are not required.
- We do not skip git hooks (no `--no-verify`, no `--no-gpg-sign`) unless a
  human has explicitly authorized it for a specific commit.

## Code style (when contributions reopen)

- Match the existing style of any file you're editing.
- Don't add comments that restate what well-named code already says. Add
  comments only when a non-obvious *why* would surprise a reader.
- Don't introduce abstractions for hypothetical future requirements.
- When in doubt, smaller and more concrete beats clever and reusable.

## Working with secrets

- Never commit API keys, certificates with private material, or anything
  that looks like a credential.
- The repo's `.gitignore` excludes `spike-host-alpine/certs/*.crt` and the
  pattern is enforced by audit at commit time. If you find yourself
  fighting the gitignore, you're probably about to commit a secret.

## License of contributions

By contributing (when contributions reopen), you agree that your work is
licensed under the project's [MIT License](./LICENSE).
