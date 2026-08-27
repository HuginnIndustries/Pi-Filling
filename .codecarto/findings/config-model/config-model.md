# Configuration Model

Configuration is spread across four mechanisms, each with a different lifetime.

## 1. CLI flags (Layer 3, per run)

`--repo`, `--provider`, `--model`, `--system-prompt`. Chosen by Layer 1 per
session; nothing is read from a config file.

## 2. Environment (per process)

`<PROVIDER>_API_KEY` — provider-scoped by design, so an Anthropic key cannot
satisfy Ollama. `WRAPPER_LOG_LEVEL` selects stderr verbosity.

The environment is *cleared and rebuilt* by `ProotExecutor`, never inherited, and
credentials are deleted from `process.env` immediately after capture.

## 3. The provider table — duplicated across a language boundary

`AgentProvider.kt` (id, label, keyEnv, keyHint, defaultModel, prefKey) mirrors
`PROVIDERS` in `wrapper.mjs` (keyEnv, defaultModel, resolveModel). They must
agree: the wrapper rejects an unknown `--provider`, and each provider reads only
its own key variable.

Nothing enforces the mirror. This is the most likely place for silent config
drift and is recorded as an open question.

## 4. Build-time configuration

`gradle.properties` (JVM args, configuration cache, AndroidX flags) is committed
and must stay machine-agnostic — `org.gradle.java.home` belongs in the user's
`~/.gradle/gradle.properties`, never here. `local.properties` (SDK path) is
gitignored.

## Sandbox-side configuration written at provisioning

`resolv.conf`, `repositories`, and a global git identity plus `safe.directory=*`
inside the guest. The marker file's `setup=<n>` is the version that drives
backfill of steps added after an install was provisioned.
