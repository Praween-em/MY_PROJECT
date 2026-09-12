# Railway: run the Express API from the monorepo root.
# Do not start Expo here — the phone app talks to this service over HTTP.
FROM node:20-alpine

WORKDIR /app

COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev

COPY backend/src ./src
COPY backend/admin-panel ./admin-panel
COPY backend/migrations ./migrations
COPY backend/scripts ./scripts

EXPOSE 3000
ENV NODE_ENV=production

CMD ["sh", "-c", "node scripts/migrate.js && node src/index.js"]
