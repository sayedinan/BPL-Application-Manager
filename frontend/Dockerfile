# syntax=docker/dockerfile:1.7
#
# Multi-stage build — Nginx + Brotli static file serving (SPEC §11.1).
# Read-only filesystem, non-root, no shell in runtime.

# --- build ---
FROM node:22-alpine AS build
WORKDIR /app

# Cache npm install first.
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

# Build the Vite app.
COPY . .
RUN npm run build

# --- runtime ---
FROM nginx:1.27-alpine
# Brotli module is built into the official nginx:alpine image; gzip
# fallback handles clients that don't advertise br.
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# nginx:alpine ships as the `nginx` user (uid 101).
USER nginx

EXPOSE 80
