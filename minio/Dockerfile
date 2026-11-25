# Stage 1: build MinIO từ mã nguồn tại tag cố định
FROM golang:1.24.6-alpine AS build

RUN apk add --no-cache git

WORKDIR /src
RUN git clone --depth 1 --branch RELEASE.2025-09-07T16-13-09Z https://github.com/minio/minio .

RUN CGO_ENABLED=0 go build -trimpath -o /out/minio .

# Stage 2: image chạy
FROM alpine:3

COPY --from=build /out/minio /usr/bin/minio

EXPOSE 9000

ENTRYPOINT ["/usr/bin/minio"]
