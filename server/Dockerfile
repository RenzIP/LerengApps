FROM node:22-alpine

WORKDIR /app
ENV NODE_ENV=production

# Dependensi dipisah supaya cache build tidak rusak saat kode berubah.
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY api ./api
COPY docker ./docker

# Jangan jalan sebagai root.
USER node

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health >/dev/null || exit 1

CMD ["node", "docker/serve.js"]
