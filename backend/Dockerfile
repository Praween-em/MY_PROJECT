# ─── Build stage ─────────────────────────────────────────────────────────────
FROM node:20-alpine AS base

WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production

COPY src/ ./src/

# ─── Runtime ──────────────────────────────────────────────────────────────────
EXPOSE 3000

ENV NODE_ENV=production

CMD ["node", "src/index.js"]
