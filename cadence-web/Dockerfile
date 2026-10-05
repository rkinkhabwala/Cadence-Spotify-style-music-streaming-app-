# syntax=docker/dockerfile:1.7
# Cadence web client: static build served by nginx, which also proxies the API (one origin for the browser).
# Build from the repo root: docker build -f cadence-web/Dockerfile .
FROM node:24.19.0-alpine AS build
WORKDIR /web
COPY cadence-web/package.json cadence-web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY cadence-web/ ./
RUN npm run build

FROM nginx:1.30-alpine
COPY cadence-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
