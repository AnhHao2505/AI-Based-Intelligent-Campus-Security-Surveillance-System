#!/usr/bin/env bash
# ==============================================================================
# scripts/check-colors.sh
# Hàng rào chặn hồi quy mã màu (Color Regression Guard)
# Dự án: ICSSS / FPTU SecureVision (feat/ui-normal-user-screens)
#
# Kiểm tra các file frontend/src/**/*.css (loại trừ LoginPage.css và theme.css):
# 1. rgba(59, 168, 217, ...) -> Cần dùng var(--brand-subtle/border/glow/focus-ring)
# 2. #0d1117 -> Cần dùng var(--theme-on-*)
# 3. var(--brand-cyan) dùng cho color hoặc fill -> Cần dùng var(--brand-text)
# ==============================================================================

set -euo pipefail

# Xác định đường dẫn gốc dự án
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${REPO_ROOT}/frontend/src"

if [[ ! -d "${TARGET_DIR}" ]]; then
  echo "❌ Lỗi: Không tìm thấy thư mục ${TARGET_DIR}" >&2
  exit 1
fi

echo "🔍 Đang quét mã màu trong ${TARGET_DIR}/**/*.css (loại trừ LoginPage.css & theme.css)..."

ERRORS=0

# 1. Kiểm tra rgba(59, 168, 217, ...)
MATCH_RGBA=$(grep -rnE "rgba\(59,\s*168,\s*217" "${TARGET_DIR}" \
  --include="*.css" \
  --exclude="LoginPage.css" \
  --exclude="theme.css" || true)

if [[ -n "${MATCH_RGBA}" ]]; then
  echo ""
  echo "❌ LỖI: Phát hiện mã màu viết cứng rgba(59, 168, 217, ...) tại:"
  echo "${MATCH_RGBA}"
  echo ""
  echo "👉 Hướng dẫn khắc phục: Vui lòng sử dụng token chuẩn trong theme.css:"
  echo "   - Nền mờ / hover:          var(--brand-subtle)"
  echo "   - Viền mờ / separator:     var(--brand-subtle-border)"
  echo "   - Quầng sáng / glow:       var(--brand-glow)"
  echo "   - Viền focus ô nhập liệu:  var(--focus-ring)"
  echo ""
  ERRORS=$((ERRORS + 1))
fi

# 2. Kiểm tra #0d1117
MATCH_DARK_TEXT=$(grep -rnE "#0d1117" "${TARGET_DIR}" \
  --include="*.css" \
  --exclude="LoginPage.css" \
  --exclude="theme.css" || true)

if [[ -n "${MATCH_DARK_TEXT}" ]]; then
  echo ""
  echo "❌ LỖI: Phát hiện mã màu chữ viết cứng #0d1117 tại:"
  echo "${MATCH_DARK_TEXT}"
  echo ""
  echo "👉 Hướng dẫn khắc phục: Vui lòng sử dụng token chữ trên nền solid (WCAG >= 4.5:1):"
  echo "   - Nền thành công (Success): var(--theme-on-success)"
  echo "   - Nền nguy hiểm (Danger):   var(--theme-on-danger)"
  echo "   - Nền cảnh báo (Warning):   var(--theme-on-warning)"
  echo "   - Nền thông tin (Info):     var(--theme-on-info)"
  echo "   - Nền thương hiệu (Brand):  var(--theme-on-brand)"
  echo ""
  ERRORS=$((ERRORS + 1))
fi

# 3. Kiểm tra var(--brand-cyan) dùng cho thuộc tính color hoặc fill
MATCH_CYAN_TEXT=$(grep -rnE "^\s*(color|fill)\s*:[^;]*var\(--brand-cyan\)" "${TARGET_DIR}" \
  --include="*.css" \
  --exclude="LoginPage.css" \
  --exclude="theme.css" || true)

if [[ -n "${MATCH_CYAN_TEXT}" ]]; then
  echo ""
  echo "❌ LỖI: Phát hiện sử dụng var(--brand-cyan) cho thuộc tính color hoặc fill tại:"
  echo "${MATCH_CYAN_TEXT}"
  echo ""
  echo "👉 Hướng dẫn khắc phục: Màu --brand-cyan (#7ed3f2) có độ tương phản thấp (~1.7:1) trên nền sáng."
  echo "   Vui lòng sử dụng token chữ tương phản an toàn:"
  echo "   - Chữ / icon thương hiệu:       var(--brand-text)       (Tối thiểu 4.5:1 cả light & dark)"
  echo "   - Chữ nhấn mạnh / tiêu đề:      var(--brand-text-strong)"
  echo ""
  ERRORS=$((ERRORS + 1))
fi

if [[ ${ERRORS} -gt 0 ]]; then
  echo "💥 Đã phát hiện ${ERRORS} nhóm lỗi màu viết cứng. Vui lòng sửa lại trước khi commit!" >&2
  exit 1
else
  echo "✅ Hoàn tất kiểm tra màu sắc: Toàn bộ CSS đạt chuẩn Design System token!"
  exit 0
fi
