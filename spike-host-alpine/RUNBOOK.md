# Runbook — host-Alpine spike

Step-by-step instructions to reproduce the spike on a local machine. The
spike answers four empirical questions about whether `pi-agent-core` +
`pi-coding-agent` are usable from a Node wrapper running inside Alpine
Linux on musl. Results documented in [`../SPIKE_NOTES.md`](../SPIKE_NOTES.md).

## 1. Prerequisites

- Docker Desktop (or any Docker engine, version ≥ 24).
- Git.
- ~250 MB of disk for the built image.
- Optional: an Anthropic API key, for the real-LLM abort test (Q3-real).
  Get one at https://console.anthropic.com/. The test uses
  `claude-haiku-4-5` and costs well under 1¢ per run.

You don't need Node, npm, or Alpine on your host — Docker handles all of it.

> **Running from WSL?** If `docker` in your WSL shell hits permission errors
> talking to Docker Desktop's engine, call the Windows binary directly:
> `"/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe" build …`
> (and likewise for `docker run …`). Everything else in this runbook is
> unchanged.

## 2. Clone and enter

```sh
git clone https://github.com/HuginnIndustries/Pi-Filling.git
cd Pi-Filling
git checkout claude/spike-pi-agent-android-NugAe
cd spike-host-alpine
```

You should see `Dockerfile`, `package.json`, `package-lock.json`,
`driver.mjs`, `driver-extras.mjs`, `driver-e2e.mjs`, and an empty `certs/`
directory (with just a `.gitkeep`).

## 3. Build the image

```sh
docker build -t pi-filling-spike:alpine .
```

What happens:
- Pulls `node:22-alpine` (~80 MB).
- The CA-cert step is a no-op unless you've dropped `.crt` files into `certs/`
  (only relevant for corporate-proxy environments — see §8).
- Runs `npm ci --omit=dev --ignore-scripts` against the committed
  `package-lock.json`, so the dependency tree is pinned and the install is
  reproducible. Pulls `@earendil-works/pi-agent-core`, `@earendil-works/pi-ai`,
  `@earendil-works/pi-coding-agent` and dependencies.
- Copies the three driver scripts (`driver.mjs`, `driver-extras.mjs`,
  `driver-e2e.mjs`).

Total build time on a typical laptop: 30–60 s (mostly the npm install).
Image size: ~270 MB.

## 4. Run the hermetic driver — Q1, Q2, Q3 (no API key needed)

```sh
docker run --rm pi-filling-spike:alpine
```

This runs `driver.mjs` and tests:
- **Q1:** `pi-agent-core` and `pi-ai` install + import on Alpine/musl.
- **Q2:** Per-call API key injection via `getApiKey` callback flows through
  to the `streamFn`'s `options.apiKey`.
- **Q3:** `agent.abort()` cancels an in-flight stream cleanly (using a
  mock streamFn — no real LLM call).

Expected output (success — all three pass):

```json
{
  "environment": { "node": "v22.x.x", "platform": "linux", "arch": "x64", ... },
  "q1_install_and_import": { "pass": true, ... },
  "q2_custom_auth": { "getApiKey_was_called_with": "anthropic", "streamFn_observed_apiKey": "spike-fake-key-abc123", "pass": true },
  "q3_abort_signal": { "signal_arrived_in_streamFn": true, "signal_aborted_during_stream": true, "final_stop_reason": "aborted", "elapsed_ms": <100, "pass": true }
}
```

Exit code is 0 if all three pass, 1 otherwise.

## 5. Run the extras driver — Q4 (no API key needed)

```sh
docker run --rm pi-filling-spike:alpine node driver-extras.mjs
```

Tests:
- **Q4:** `pi-coding-agent` imports cleanly and `createCodingTools(cwd)`
  returns 4 working `AgentTool`s (read/write/edit/bash) on musl.
- **Q3-real:** *skipped* because no API key is in env.

Expected output:

```json
{
  "q4_create_coding_tools": {
    "pass": true,
    "import_ms": ~1000,
    "tool_count": 4,
    "tool_names": ["bash", "edit", "read", "write"],
    ...
  },
  "q3_real_anthropic_abort": { "skipped": true, "skipped_reason": "ANTHROPIC_API_KEY not set in env" }
}
```

## 6. Run the real-LLM abort test — Q3-real (API key required)

This issues one real call to `claude-haiku-4-5` and aborts mid-stream after
seeing 5 text deltas. Total cost: well under 1¢.

**Get your key into env without it leaking into shell history.** Two safe
options:

**Option A — file (recommended):**

```sh
# Use your editor or:
read -s -p "Anthropic API key: " ANTHROPIC_API_KEY && echo
echo "$ANTHROPIC_API_KEY" > ~/.anthropic-key
chmod 600 ~/.anthropic-key
unset ANTHROPIC_API_KEY
```

Then run:

```sh
docker run --rm \
  -e ANTHROPIC_API_KEY="$(cat ~/.anthropic-key)" \
  pi-filling-spike:alpine \
  node driver-extras.mjs
```

**Option B — direct env, single shell session:**

```sh
read -s ANTHROPIC_API_KEY && echo && export ANTHROPIC_API_KEY
docker run --rm -e ANTHROPIC_API_KEY pi-filling-spike:alpine node driver-extras.mjs
```

(`-e ANTHROPIC_API_KEY` with no value tells Docker to forward the host env var.)

Expected output (real-LLM pass):

```json
{
  "q4_create_coding_tools": { "pass": true, ... },
  "q3_real_anthropic_abort": {
    "skipped": false,
    "pass": true,
    "elapsed_ms": ~1500,
    "final_stop_reason": "aborted",
    "text_chars_received_before_abort": >0,
    "text_deltas_seen": >=5
  }
}
```

The interesting fields:
- `final_stop_reason: "aborted"` — agent loop terminated cleanly.
- `text_deltas_seen >= 5` — driver observed at least 5 streaming chunks
  before triggering the abort, proving the SSE connection was actually open.
- `elapsed_ms < 5000` — the SDK closed the connection promptly. If the SDK
  ignored `AbortSignal` we'd see this grow to ~10–15 s (full count to 200).

## 7. End-to-end run — agent reads, edits, commits (API key required)

This is the v1 acceptance test from `V1_SPEC.md` minus the push step. The
driver creates a throwaway git repo inside the container, lets a real
Anthropic agent edit a README and `git commit` via `createCodingTools`,
then verifies the commit happened.

```sh
# Reuse the env-var pattern from §6.
docker run --rm -e ANTHROPIC_API_KEY pi-filling-spike:alpine node driver-e2e.mjs
```

Expected output:

```json
{
  "pass": true,
  "elapsed_ms": ~5000,
  "assistant_turns": ~4,
  "tool_calls": ~3,
  "readme_updated": true,
  "new_commit_made": true,
  "head_commit_message": "spike: e2e verification",
  "final_stop_reason": "stop"
}
```

A live tool log (read → edit → bash) is printed to stderr. The agent
should naturally stop after committing; `final_stop_reason: "stop"` (not
"aborted" or "error") is the success signal. Cost: under 1¢ on
`claude-haiku-4-5`.

If the agent runs more turns or fails verification, capture the JSON
output and paste it back — the per-turn / per-tool counts and the
`final_stop_reason` together usually tell you what went wrong.

## 8. Optional: corporate proxy / MITM environments

If `npm install` fails during the build with errors like
`SELF_SIGNED_CERT_IN_CHAIN` or `unable to verify the first certificate`,
your network has a TLS-inspection proxy and Node doesn't trust its CA.

Drop the proxy CA(s) into `spike-host-alpine/certs/` as `.crt` files
(PEM format) and rebuild:

```sh
# Linux example: copy your org's trusted CAs
cp /usr/local/share/ca-certificates/your-corp-ca.crt spike-host-alpine/certs/
docker build -t pi-filling-spike:alpine spike-host-alpine
```

The Dockerfile concatenates everything in `certs/*.crt` into a bundle and
sets `NODE_EXTRA_CA_CERTS` so npm and the Anthropic SDK both trust it.
`.crt` files are gitignored (see `.gitignore`).

## 9. Cleanup

```sh
docker rmi pi-filling-spike:alpine
rm -f ~/.anthropic-key   # if you used Option A in §6
```

## 10. What to look for

If any of Q1, Q2, Q3 (mock), Q4, Q3-real, or the §7 e2e run fail unexpectedly, that's a
signal something has shifted upstream in `pi-mono` since the spike was
locked. Check the corresponding section of [`../SPIKE_NOTES.md`](../SPIKE_NOTES.md)
for what was originally observed and where the contract lives in
`pi-mono`'s source.
