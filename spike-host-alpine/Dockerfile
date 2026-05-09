# Spike: prove @mariozechner/pi-agent-core + pi-ai run on Alpine/musl.
# node:22-alpine ships Alpine 3.x with Node 22 + npm pre-installed, so we don't
# have to fight apk + the sandbox egress proxy to install Node. The musl libc +
# Alpine userland — the actual things we're testing — are unchanged.
FROM node:22-alpine

# Trust the host MITM proxy CAs so npm install can reach registry.npmjs.org.
# On a real device this step is unnecessary.
COPY certs/*.crt /usr/local/share/ca-certificates/
RUN for f in /usr/local/share/ca-certificates/*.crt; do \
        printf "\n" >> /etc/ssl/certs/spike-extra-cas.crt; \
        cat "$f" >> /etc/ssl/certs/spike-extra-cas.crt; \
        printf "\n" >> /etc/ssl/certs/spike-extra-cas.crt; \
    done
ENV NODE_EXTRA_CA_CERTS=/etc/ssl/certs/spike-extra-cas.crt

WORKDIR /spike
COPY package.json package-lock.json* ./
RUN npm install --omit=dev

COPY driver.mjs ./
CMD ["node", "driver.mjs"]
