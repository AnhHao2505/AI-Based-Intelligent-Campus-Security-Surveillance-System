@echo off
echo ===================================================
echo   KHOI DONG GUARD PATROL MOBILE (FLUTTER)
echo ===================================================

:: 1. Thong cong ket noi qua cap USB (adb reverse)
echo [1/2] Dang thong cong ket noi USB (adb reverse)...
adb reverse tcp:8080 tcp:8080 >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    if exist "C:\Android\Sdk\platform-tools\adb.exe" (
        "C:\Android\Sdk\platform-tools\adb.exe" reverse tcp:8080 tcp:8080 >nul 2>&1
    )
)

:: 2. Chay ung dung Flutter
echo [2/2] Dang khoi chay ung dung len dien thoai...
echo.
flutter run
