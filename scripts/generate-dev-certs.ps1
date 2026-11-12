# ==============================================================================
# Script sinh Certificate Authority (CA) và Server Certificate tự ký cho dev/test (PowerShell)
# ==============================================================================
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
$CertDir = Join-Path $RootDir "mosquitto\certs"

if (-not (Test-Path $CertDir)) {
    New-Item -ItemType Directory -Path $CertDir -Force | Out-Null
}

# Tìm openssl
$openssl = "openssl"
if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\Program Files\Git\usr\bin\openssl.exe") {
        $openssl = "C:\Program Files\Git\usr\bin\openssl.exe"
    } else {
        Write-Error "Không tìm thấy 'openssl'. Vui lòng cài đặt OpenSSL hoặc Git for Windows."
    }
}

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "Khởi tạo chứng chỉ tự ký cho môi trường Dev: $CertDir" -ForegroundColor Cyan
Write-Host "======================================================================"

# 1. Root CA
Write-Host "1. Tạo Certificate Authority (CA)..."
& $openssl genrsa -out (Join-Path $CertDir "ca.key") 4096
& $openssl req -x509 -new -nodes `
    -key (Join-Path $CertDir "ca.key") `
    -sha256 -days 3650 `
    -out (Join-Path $CertDir "ca.crt") `
    -subj "/C=VN/ST=DaNang/O=CampusSecurity/CN=CampusSecurity-Dev-CA"

# 2. Server Key
Write-Host "2. Tạo Server Private Key..."
& $openssl genrsa -out (Join-Path $CertDir "server.key") 2048

# 3. Server CSR & SAN
Write-Host "3. Tạo CSR và cấu hình SAN cho localhost, 127.0.0.1..."
$extContent = @"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = host.docker.internal
IP.1 = 127.0.0.1
IP.2 = 0.0.0.0
"@
$extFile = Join-Path $CertDir "server.ext"
Set-Content -Path $extFile -Value $extContent -Encoding ASCII

$csrFile = Join-Path $CertDir "server.csr"
& $openssl req -new `
    -key (Join-Path $CertDir "server.key") `
    -out $csrFile `
    -subj "/C=VN/ST=DaNang/O=CampusSecurity/CN=localhost"

# 4. Ký cert
Write-Host "4. Ký Server Certificate bằng CA..."
& $openssl x509 -req `
    -in $csrFile `
    -CA (Join-Path $CertDir "ca.crt") `
    -CAkey (Join-Path $CertDir "ca.key") `
    -CAcreateserial `
    -out (Join-Path $CertDir "server.crt") `
    -days 365 -sha256 `
    -extfile $extFile

Remove-Item -Path $csrFile, $extFile -Force -ErrorAction SilentlyContinue

# 5. Java Truststore
Write-Host "5. Tạo Java Truststore cho Backend (truststore.jks)..."
if (Get-Command keytool -ErrorAction SilentlyContinue) {
    $jksFile = Join-Path $CertDir "truststore.jks"
    if (Test-Path $jksFile) { Remove-Item -Path $jksFile -Force }
    & keytool -importcert -noprompt `
        -alias campus-security-ca `
        -file (Join-Path $CertDir "ca.crt") `
        -keystore $jksFile `
        -storepass changeit
    Write-Host "   -> Đã tạo $jksFile (pass: changeit)" -ForegroundColor Green
} else {
    Write-Host "   [Bỏ qua] 'keytool' không tìm thấy trong PATH." -ForegroundColor Yellow
}

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "Hoàn tất! Các file đã sinh trong mosquitto/certs/:" -ForegroundColor Green
Write-Host " - ca.crt           : Root CA dùng cho client kiểm tra broker"
Write-Host " - ca.key           : Khoá bí mật Root CA (giữ kín, không chia sẻ)"
Write-Host " - server.crt       : Chứng chỉ SSL của Mosquitto broker"
Write-Host " - server.key       : Khoá bí mật của Mosquitto broker"
Write-Host " - truststore.jks   : Java Keystore chứa ca.crt cho Backend Spring Boot"
Write-Host ""
Write-Host "Hướng dẫn sử dụng:"
Write-Host " 1. Mosquitto: Đã mount tự động vào /mosquitto/certs trong docker-compose.yml."
Write-Host " 2. AI Service: Đặt trong .env:"
Write-Host "      MQTT_BROKER_PORT=8883"
Write-Host "      MQTT_TLS_ENABLED=true"
Write-Host "      MQTT_TLS_CA_PATH=../mosquitto/certs/ca.crt"
Write-Host " 3. Backend: Đặt trong .env hoặc application.yml:"
Write-Host "      MQTT_BROKER_URL=ssl://localhost:8883"
Write-Host "      MQTT_TRUSTSTORE_PATH=../mosquitto/certs/truststore.jks"
Write-Host "      MQTT_TRUSTSTORE_PASSWORD=changeit"
Write-Host "======================================================================" -ForegroundColor Cyan
