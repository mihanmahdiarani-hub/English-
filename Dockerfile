FROM node:20-bookworm-slim

ENV NODE_ENV=production
WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY . .

# Cloud Run injects PORT. server.js already reads process.env.PORT.
ENV PORT=8080
EXPOSE 8080

CMD ["node","-r","./live-setup-timeout.js","-r","./auto-live-model.js","-r","./gemini-overload-fallback.js","-r","./cors-bridge.js","-r","./client-diagnostics.js","-r","./android-apk-host.js","-r","./media-api.js","-r","./media-upload-proxy.js","-r","./media-audio-extract.js","server.js"]
