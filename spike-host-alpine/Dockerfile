# Spike: prove @mariozechner/pi-agent-core + pi-coding-agent run on Alpine/musl.
# node:22-alpine ships Alpine + Node 22 + npm pre-installed, so we don't have to
# fight apk to install Node. The musl libc + Alpine userland — the actual things
# we're testing — are unchanged.
FROM node:22-alpine

# Optional escape hatch for environments that intercept TLS with a custom CA
# (corporate proxies, this project's own dev sandbox, etc). Drop .crt files
# into certs/ before `docker build` and they'll be trusted by Node at runtime.
# The directory is committed empty (certs/.gitkeep), so this step is a no-op
# on machines that don't need it. We always create the bundle file (empty if
# no certs supplied) so NODE_EXTRA_CA_CERTS doesn't trigger a startup warning.
COPY certs /tmp/extra-certs
RUN touch /etc/ssl/certs/spike-extra-cas.crt && \
    if ls /tmp/extra-certs/*.crt > /dev/null 2>&1; then \
        for f in /tmp/extra-certs/*.crt; do \
            printf "\n" >> /etc/ssl/certs/spike-extra-cas.crt; \
            cat "$f" >> /etc/ssl/certs/spike-extra-cas.crt; \
            printf "\n" >> /etc/ssl/certs/spike-extra-cas.crt; \
        done; \
    fi
ENV NODE_EXTRA_CA_CERTS=/etc/ssl/certs/spike-extra-cas.crt

WORKDIR /spike
COPY package.json package-lock.json* ./
RUN npm install --omit=dev

COPY driver.mjs driver-extras.mjs ./
CMD ["node", "driver.mjs"]
