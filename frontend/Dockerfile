FROM node:24-alpine AS build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm install -g "$(node -p "require('./package.json').packageManager")" && npm ci

COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/flowforge/browser /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/ > /dev/null || exit 1
