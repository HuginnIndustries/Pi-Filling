# Spike: prove @mariozechner/pi-agent-core + pi-coding-agent run on Alpine/musl.
# node:22-alpine ships Alpine + Node 22 + npm pre-installed, so we don't have to
# fight apk to install Node. The musl libc + Alpine userland — the actual things
# we're testing — are unchanged.
FROM node:22-alpine

# Optional escape hatch for environments that intercept TLS with a custom CA
# (corporate proxies, this project's own dev sandbox, etc). Drop .crt files
# into certs/ before `docker build` and they'll be trusted by both apk and
# Node at runtime. The directory is committed empty (certs/.gitkeep), so this
# step is a no-op on machines that don't need it.
#
# Done up-front, before `apk add`, because apk's TLS check uses
# /etc/ssl/cert.pem and would fail on intercepted networks otherwise.
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
RUN touch /etc/ssl/certs/spike-extra-cas.crt && \
    if ls /tmp/extra-certs/*.crt > /dev/null 2>&1; then \
        for f in /tmp/extra-certs/*.crt; do \
            printf "\n" >> /etc/ssl/certs/spike-extra-cas.crt; \
            cat "$f" >> /etc/ssl/certs/spike-extra-cas.crt; \
            printf "\n" >> /etc/ssl/certs/spike-extra-cas.crt; \
        done; \
    fi
ENV NODE_EXTRA_CA_CERTS=/etc/ssl/certs/spike-extra-cas.crt

# git is needed for driver-e2e.mjs (the agent's bash tool runs git commands).
RUN apk add --no-cache git

WORKDIR /spike
# npm ci for a reproducible, integrity-checked install from the committed lock.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts

COPY driver.mjs driver-extras.mjs driver-e2e.mjs ./
CMD ["node", "driver.mjs"]
