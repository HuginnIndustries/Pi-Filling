# Security policy

Pi-Filling is in pre-1.0 development. No releases yet. The notes below are
provisional and will firm up as the project matures.

## Reporting a vulnerability

If you find a security issue, please **do not** open a public GitHub issue.

Until we publish a contact address, please use GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository.

We aim to acknowledge reports within 7 days. Coordinated-disclosure timelines
will be agreed case-by-case until a formal policy is published.

## Scope

Reports relevant to this repository:

- Code we ship from this repo (the eventual Android app, any Node wrappers,
  the spike drivers).
- Configuration we recommend in our docs (e.g., the Dockerfile, the API-key
  handling patterns in `RUNBOOK.md`).
- Build pipelines and release artifacts (once they exist).

Out of scope (please report upstream):

- `@mariozechner/pi-agent-core`, `@mariozechner/pi-ai`,
  `@mariozechner/pi-coding-agent` — report at https://github.com/badlogic/pi-mono.
- Anthropic SDK / API — report at https://www.anthropic.com/security.
- Kai Android sandbox — report at https://github.com/TheAmericanMaker/Kai.
- The Anthropic models themselves — report via Anthropic's responsible
  disclosure channels.

## Cryptographic material in the repo

There is none and there should be none. The repo's gitignore excludes
`spike-host-alpine/certs/*.crt` and any `.anthropic-key` style files. If you
spot a key, secret, or private cert in a commit, treat it as a security
report per the section above.
