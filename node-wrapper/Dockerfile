# Alpine smoke-test image for the node-wrapper.
#
# Builds an image that mirrors the runtime environment the wrapper will see
# inside Kai's proot Alpine: Node 22 on musl, git, and the wrapper installed
# at /wrapper. The default CMD runs the integration test suite.
#
# Used to answer Stage 1.2b's question: "does the wrapper actually run on
# Alpine/musl?" — independent of the Android shell that will eventually
# spawn it.

FROM node:22-alpine

# Optional escape hatch for TLS-inspection proxy environments (corporate
# proxies, this project's own dev sandbox). Drop .crt files into certs/
# before `docker build` and they'll be trusted by both apk and Node at
# runtime. The directory is committed empty (certs/.gitkeep), so this step
# is a no-op on machines that don't need it.
#
# Done up-front, before `apk add`, because apk's TLS check uses
# /etc/ssl/cert.pem and will fail otherwise.
COPY certs /tmp/extra-certs
RUN if ls /tmp/extra-certs/*.crt > /dev/null 2>&1; then \
        for f in /tmp/extra-certs/*.crt; do \
            printf "\n" >> /etc/ssl/cert.pem; \
            cat "$f" >> /etc/ssl/cert.pem; \
        done; \
    fi
# Also expose the same bundle to Node via NODE_EXTRA_CA_CERTS. We always
# create the file (empty if no certs supplied) so the env var doesn't
# trigger a "No such file or directory" warning on machines without certs.
RUN touch /etc/ssl/certs/wrapper-extra-cas.crt && \
    if ls /tmp/extra-certs/*.crt > /dev/null 2>&1; then \
        for f in /tmp/extra-certs/*.crt; do \
            printf "\n" >> /etc/ssl/certs/wrapper-extra-cas.crt; \
            cat "$f" >> /etc/ssl/certs/wrapper-extra-cas.crt; \
            printf "\n" >> /etc/ssl/certs/wrapper-extra-cas.crt; \
        done; \
    fi
ENV NODE_EXTRA_CA_CERTS=/etc/ssl/certs/wrapper-extra-cas.crt

# git is needed because the wrapper's tools include `bash` which the agent
# uses to run git commands during integration tests.
RUN apk add --no-cache git

WORKDIR /wrapper
# `npm ci` (not install) installs exactly the committed, integrity-checked lock
# tree and fails if package.json and the lockfile disagree — reproducible builds.
# --ignore-scripts blocks lifecycle scripts from the large transitive provider
# SDK tree; none of our deps need a build step.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts

COPY src ./src
COPY test ./test

# Default: run the full test suite via Node's test-runner auto-discovery.
# The hermetic smoke tests (test/smoke.mjs) always run and verify the wrapper
# boots and speaks its protocol on Alpine/musl with no API key. The integration
# tests (test/integration.mjs) additionally exercise a real Anthropic call when
# ANTHROPIC_API_KEY is forwarded via `docker run -e ANTHROPIC_API_KEY ...`, and
# self-skip when it's absent.
CMD ["node", "--test"]
