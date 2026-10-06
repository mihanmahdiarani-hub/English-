FROM node:20-bookworm-slim

ENV NODE_ENV=production
ENV PORT=7860
WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY . .

# Hugging Face Docker Spaces exposes the port configured by README.md.
EXPOSE 7860

CMD ["node","-r","./live-setup-timeout.js","-r","./auto-live-model.js","-r","./gemini-overload-fallback.js","-r","./cors-bridge.js","-r","./client-diagnostics.js","-r","./android-apk-host.js","-r","./media-api.js","-r","./media-upload-proxy.js","-r","./media-audio-extract.js","server.js"]
