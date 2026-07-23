# SUPER RIDEX backend — Railway / Docker
FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --only=production

COPY src/ ./src/
COPY admin-panel/ ./admin-panel/
COPY migrations/ ./migrations/
COPY scripts/ ./scripts/

EXPOSE 3000
ENV NODE_ENV=production

# Run migrations then start (safe: migrations are idempotent)
CMD ["sh", "-c", "node scripts/migrate.js && node src/index.js"]
