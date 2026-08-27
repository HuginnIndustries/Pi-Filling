# Public Surfaces

Extracted during the architecture phase. Evidence: source inspection plus
on-device verification.

## Layer 3 CLI — `node src/wrapper.mjs`

| Flag | Meaning | Default |
|---|---|---|
| `--repo` | Absolute path to the bound git repo; must exist | required |
| `--provider` | `anthropic` \| `ollama` | `anthropic` |
| `--model` | Model id; validated against the catalog for `anthropic` only | provider default |
| `--system-prompt` | File replacing the base prompt | built-in |

**Exit codes are a contract** Layer 1 branches on: `1` config/usage (bad args,
missing `<PROVIDER>_API_KEY`, unknown provider, unreadable prompt file, Node < 22),
`2` `--repo` missing or not a directory, `3` model not in the pi-ai registry,
`4` uncaught exception / unhandled rejection.

Environment: `<PROVIDER>_API_KEY` (`ANTHROPIC_API_KEY` or `OLLAMA_API_KEY`),
`WRAPPER_LOG_LEVEL` (`silent|error|info|debug`).

## Wire protocol — JSONL over stdio

stdout carries structured JSONL; stderr carries free-form logs. They are
deliberately not merged.

**Requests** `{id:<number>, method:<string>, params?:{}}`

| Method | Purpose |
|---|---|
| `prompt` | Run a turn; rejects with `busy` if one is in flight |
| `abort` | Abort the in-flight run; returns `aborted:false` when idle |
| `state` | Snapshot of the agent |
| `shutdown` | Ack, then clean exit 0 |

**Responses** `{id, result}` or `{id, error:{code, message}}` with codes
`bad_params`, `busy`, `unknown_method`, `shutting_down`, `handler_error`.

**Events** — synthetic: `wrapper_ready` (exactly once, before any request; carries
`protocolVersion`, `provider`, `model`, `repoPath`, `hasMemory`), `wrapper_error`
(`phase`, `message`). Forwarded from pi-agent-core: `agent_start`, `turn_start`,
`message_start`, `message_update`, `tool_execution_start`, `tool_execution_end`,
`message_end`, `turn_end`, `agent_end`.

The streamed assistant chunk lives at
`data.assistantMessageEvent.{type,delta}` — `type` distinguishes `text_delta`
from `thinking_delta`. Reading `delta` without checking `type` splices the
model's reasoning into user-visible output.

## User-facing screens

A single `when` selects one of three: API-key entry (provider chooser, masked
field), sandbox provisioning (progress/retry), session (repo path, prompt box,
transcript, abort, end session).

## Persistent artifacts

`shared_prefs/pifilling_secure_prefs.xml`; `files/linux-sandbox/**`;
`.setup-complete`; `memory.md` written into the user's repository.

## Not a public surface

`spike-host-alpine/` drivers are dev-machine regression evidence and are not
reachable from the app.
