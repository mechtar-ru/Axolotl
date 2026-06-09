# Axolotl Plugin Bridge — standalone image
# Bundles Bun runtime with plugin dependencies for sidecar deployment.
FROM oven/bun:1.3 AS base

WORKDIR /plugins

# Install dependencies first (layer caching)
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production

# Copy bridge script
COPY plugin-bridge.js ./

# Non-root user for security
USER bun
ENV NODE_ENV=production

EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD bun -e "console.log('healthy')" || exit 1

ENTRYPOINT ["bun", "plugin-bridge.js"]
