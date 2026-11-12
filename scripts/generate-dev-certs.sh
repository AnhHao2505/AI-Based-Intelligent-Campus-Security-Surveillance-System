#!/usr/bin/env bash
# ==============================================================================
# Script sinh Certificate Authority (CA) và Server Certificate tự ký cho dev/test
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CERT_DIR="${ROOT_DIR}/mosquitto/certs"

mkdir -p "${CERT_DIR}"

echo "======================================================================"
echo "Khởi tạo chứng chỉ tự ký cho môi trường Dev: ${CERT_DIR}"
echo "======================================================================"

# 1. Tạo Root CA
echo "1. Tạo Certificate Authority (CA)..."
openssl genrsa -out "${CERT_DIR}/ca.key" 4096
openssl req -x509 -new -nodes \
    -key "${CERT_DIR}/ca.key" \
    -sha256 -days 3650 \
    -out "${CERT_DIR}/ca.crt" \
    -subj "/C=VN/ST=DaNang/O=CampusSecurity/CN=CampusSecurity-Dev-CA"

# 2. Tạo Server Private Key
echo "2. Tạo Server Private Key..."
openssl genrsa -out "${CERT_DIR}/server.key" 2048

# 3. Tạo CSR và cấu hình Subject Alternative Names (SAN)
echo "3. Tạo CSR và cấu hình SAN cho localhost, 127.0.0.1..."
cat > "${CERT_DIR}/server.ext" << EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = host.docker.internal
IP.1 = 127.0.0.1
IP.2 = 0.0.0.0
EOF

openssl req -new \
    -key "${CERT_DIR}/server.key" \
    -out "${CERT_DIR}/server.csr" \
    -subj "/C=VN/ST=DaNang/O=CampusSecurity/CN=localhost"

# 4. Ký Server Certificate bằng Root CA
echo "4. Ký Server Certificate bằng CA..."
openssl x509 -req \
    -in "${CERT_DIR}/server.csr" \
    -CA "${CERT_DIR}/ca.crt" \
    -CAkey "${CERT_DIR}/ca.key" \
    -CAcreateserial \
    -out "${CERT_DIR}/server.crt" \
    -days 365 -sha256 \
    -extfile "${CERT_DIR}/server.ext"

rm -f "${CERT_DIR}/server.csr" "${CERT_DIR}/server.ext"

# 5. Tạo Java Truststore (.jks) cho Backend Spring Boot
echo "5. Tạo Java Truststore cho Backend (truststore.jks)..."
if command -v keytool >/dev/null 2>&1; then
    rm -f "${CERT_DIR}/truststore.jks"
    keytool -importcert -noprompt \
        -alias campus-security-ca \
        -file "${CERT_DIR}/ca.crt" \
        -keystore "${CERT_DIR}/truststore.jks" \
        -storepass changeit
    echo "   -> Đã tạo ${CERT_DIR}/truststore.jks (pass: changeit)"
else
    echo "   [Bỏ qua] 'keytool' không tìm thấy trong PATH. Có thể tạo thủ công sau."
fi

# 6. Thiết lập quyền an toàn cho private key
chmod 600 "${CERT_DIR}"/*.key 2>/dev/null || true
chmod 644 "${CERT_DIR}"/*.crt "${CERT_DIR}"/*.jks 2>/dev/null || true

echo "======================================================================"
echo "Hoàn tất! Các file đã sinh trong mosquitto/certs/:"
echo " - ca.crt           : Root CA dùng cho client kiểm tra broker"
echo " - ca.key           : Khoá bí mật Root CA (giữ kín, không chia sẻ)"
echo " - server.crt       : Chứng chỉ SSL của Mosquitto broker"
echo " - server.key       : Khoá bí mật của Mosquitto broker"
echo " - truststore.jks   : Java Keystore chứa ca.crt cho Backend Spring Boot"
echo ""
echo "Hướng dẫn sử dụng:"
echo " 1. Mosquitto: Đã mount tự động vào /mosquitto/certs trong docker-compose.yml."
echo " 2. AI Service: Đặt trong .env:"
echo "      MQTT_BROKER_PORT=8883"
echo "      MQTT_TLS_ENABLED=true"
echo "      MQTT_TLS_CA_PATH=../mosquitto/certs/ca.crt"
echo " 3. Backend: Đặt trong .env hoặc application.yml:"
echo "      MQTT_BROKER_URL=ssl://localhost:8883"
echo "      MQTT_TRUSTSTORE_PATH=../mosquitto/certs/truststore.jks"
echo "      MQTT_TRUSTSTORE_PASSWORD=changeit"
echo "======================================================================"
