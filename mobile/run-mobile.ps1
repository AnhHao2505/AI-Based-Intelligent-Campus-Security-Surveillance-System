Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "  KHOI DONG GUARD PATROL MOBILE (FLUTTER)" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan

# 1. Thong cong ket noi qua cap USB (adb reverse)
Write-Host "[1/2] Dang thong cong ket noi USB (adb reverse)..." -ForegroundColor Yellow
$adbPath = if (Get-Command adb -ErrorAction SilentlyContinue) { "adb" } elseif (Test-Path "C:\Android\Sdk\platform-tools\adb.exe") { "C:\Android\Sdk\platform-tools\adb.exe" } else { $null }

if ($adbPath) {
    & $adbPath reverse tcp:8080 tcp:8080 | Out-Null
    Write-Host "  -> Da thong cong tcp:8080 thanh cong!" -ForegroundColor Green
} else {
    Write-Host "  -> Khong tim thay adb, bo qua adb reverse." -ForegroundColor DarkYellow
}

# 2. Chay ung dung Flutter
Write-Host "[2/2] Dang khoi chay ung dung len dien thoai..." -ForegroundColor Yellow
flutter run
