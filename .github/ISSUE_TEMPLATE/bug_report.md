---
name: Bug report
about: Something behaves differently than documented
labels: bug
---

## What happened

<!-- What you observed, and what you expected instead. -->

## Reproduction

<!--
Exact commands, in order. If it involves the wrapper, the most useful
report includes the output of:

    cd node-wrapper && npm ci && npm test

and, if the failure is Alpine/musl-specific:

    docker build -t pi-filling-node-wrapper . && docker run --rm pi-filling-node-wrapper
-->

## Environment

- Host OS / arch:
- Node version (`node --version`):
- Docker version, if relevant:
- Android device + OS version, if relevant:

## Logs

<!--
Run with WRAPPER_LOG_LEVEL=debug where applicable and paste the output.
Redact your API key — the wrapper scrubs it from the environment, but not
from anything you paste here.
-->
