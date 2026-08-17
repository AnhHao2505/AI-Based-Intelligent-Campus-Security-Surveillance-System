# ============================================
# Phase 1 Development Stage: Vite + React
# NOTE: Trước ngày bảo vệ demo cuối kỳ, cần chuyển
# sang Multi-stage build (npm run build -> Nginx static serve)
# ============================================
FROM node:20-alpine

WORKDIR /app

# Cache dependencies & cài đặt chính xác theo package-lock.json (dùng npm ci)
COPY package*.json ./
RUN npm ci

# Copy source code (node_modules đã bị loại trừ qua .dockerignore)
COPY . .

EXPOSE 5173

# Chạy Vite dev server lắng nghe trên port 5173 đối với tất cả IP
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
