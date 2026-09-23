# Khảo sát xung đột Design System: CSS thuần và Tailwind v4

> Tài liệu kỹ thuật đo đạc và phân tích hiện trạng kiến trúc styling trên nhánh `feat/ui-normal-user-screens`.  
> Không chứa đánh giá chủ quan hay chọn phe. Chỉ phản ánh cơ chế kỹ thuật, đo đạc tương phản và các đánh đổi kiến trúc.  
> Tài liệu tham chiếu: [`docs/ui-structure-index.md`](file:///Users/anhhao/Documents/SEP/docs/ui-structure-index.md).

---

## Mục lục
- [1. Thứ tự nạp CSS thực tế](#1-thứ-tự-nạp-css-thực-tế)
- [2. Tailwind preflight và các va chạm tiềm năng](#2-tailwind-preflight-và-các-va-chạm-tiềm-năng)
- [3. @theme của Tailwind v4 với :root của theme.css](#3-theme-của-tailwind-v4-với-root-của-themecss)
- [4. Cơ chế dark mode và rủi ro lệch pha](#4-cơ-chế-dark-mode-và-rủi-ro-lệch-pha)
- [5. Mức độ trộn lẫn hiện tại trên bề mặt giao diện](#5-mức-độ-trộn-lẫn-hiện-tại-trên-bề-mặt-giao-diện)
- [6. Ba phương án kiến trúc và đánh đổi kỹ thuật](#6-ba-phương-án-kiến-trúc-và-đánh-đổi-kỹ-thuật)
- [7. Danh sách câu hỏi cần chốt để ra quyết định](#7-danh-sách-câu-hỏi-cần-chốt-để-ra-quyết-định)

---

## 1. Thứ tự nạp CSS thực tế

### A. Chuỗi điểm vào (Entry Point Chain)
1. [`frontend/src/main.jsx:6`](file:///Users/anhhao/Documents/SEP/frontend/src/main.jsx#L6): Nạp đầu tiên câu lệnh `import './index.css'`.
2. [`frontend/src/index.css:1-3`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L1-L3):
   - Dòng 1: `@import url('https://fonts.googleapis.com/...');` (Google Fonts).
   - Dòng 2: `@import 'tailwindcss';` (Khai báo nạp engine Tailwind v4).
   - Dòng 3: `@import './styles/theme.css';` (Khai báo nạp 73 Design System tokens).
   - Dòng 9-42: CSS Global Reset trần (`*`, `html`, `body`, `#root`, `a`, `button`).
3. [`frontend/src/main.jsx:7`](file:///Users/anhhao/Documents/SEP/frontend/src/main.jsx#L7): Nạp `import App from './App.jsx'`.
4. [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx): Nạp các layout wrapper ([`AppLayout.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/AppLayout.jsx) -> [`AppLayout.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AppLayout.css), [`Sidebar.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/Sidebar.jsx) -> [`Sidebar.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/Sidebar.css)).
5. Các module trang (`pages/*.jsx`) được nạp tĩnh trong router, kéo theo các file CSS riêng của từng màn hình (`DashboardPage.css`, `AreaListPage.css`...).
6. Các component dùng chung được import trong trang (`Button.jsx` -> `Button.css`, `Modal.jsx` -> `Modal.css`).

### B. Vai trò can thiệp của `@tailwindcss/vite`
- Cấu hình tại [`frontend/vite.config.js:8`](file:///Users/anhhao/Documents/SEP/frontend/vite.config.js#L8): `plugins: [react(), tailwindcss()]`.
- Plugin `@tailwindcss/vite` phân giải chỉ thị `@import 'tailwindcss'` trực tiếp bên trong luồng xử lý CSS của `index.css`. Nó biên dịch ra:
  1. Các biến CSS mặc định của Tailwind v4 (`--font-*`, `--color-*`).
  2. Toàn bộ reset của Tailwind Preflight (được bọc trong `@layer base`).
  3. Các utility classes phát sinh theo mã nguồn JSX được quét.
- Vì `@import './styles/theme.css'` nằm **sau** `@import 'tailwindcss'` trong `index.css`, các biến của `theme.css` xuất hiện ngay sau khối biến Tailwind trong CSS bundle.

### C. Vị trí thực tế trong bundle sản xuất (`dist/assets/index-*.css`)

| Thứ tự | Offset byte | Nguồn nạp | Nội dung quy tắc CSS | Cơ chế thắng thua (Cascade) |
|---|---|---|---|---|
| 1 | ~259 B | `index.css` (L9-14) | Reset toàn cục trần `*,:before,:after` (`box-sizing`, `margin:0`, `padding:0`) | Bị ghi đè bởi các selector có độ đặc hiệu (specificity) cao hơn |
| 2 | ~287 B | `@tailwindcss/vite` | Biến nội bộ Tailwind (`--tw-*`) và `@layer base` (Preflight reset: `h1-h6`, `button`, `input`, `ol/ul`, `table`, `img/svg`) | Nằm trong `@layer base`, do đó có độ ưu tiên thấp hơn bất kỳ CSS thông thường nào không nằm trong layer |
| 3 | ~811 B | `@tailwindcss/vite` | Palette màu mặc định Tailwind (`--color-slate-*`, `--color-emerald-*`...) | Không ghi đè tên biến vì không trùng tên với `theme.css` |
| 4 | ~7.755 B | `theme.css` | 73 CSS Variables Design System (`:root` và `[data-theme="dark"]`), utility classes | Thắng các giá trị mặc định của CSS layer |
| 5 | ~14.995 B | `Sidebar.css`, `AppLayout.css` | CSS Layout chung toàn ứng dụng | Thắng `index.css` |
| 6 | ~21.383 B đến ~175 kB | 14 file CSS trang (`pages/**/*.css`) | BEM/prefix classes của từng màn hình | Nạp sau, ghi đè các style chung nếu trùng độ đặc hiệu |
| 7 | ~179.335 B đến ~197 kB | 8 file CSS linh kiện (`components/ui/*.css`) | Linh kiện UI dùng chung (`.ui-btn`, `.ui-badge`, `.ui-modal`...) | Nạp cuối cùng trong bundle |

---

## 2. Tailwind preflight và các va chạm tiềm năng

### A. Trạng thái hoạt động
Tailwind Preflight **đang chạy thực tế**. Khi sử dụng `@import 'tailwindcss';` trong Tailwind v4, Preflight tự động được tích hợp bên trong chỉ thị `@layer base` của CSS bundle sản xuất (chiếm khoảng 3.689 ký tự trong bundle).

### B. Danh sách các quy tắc reset của Preflight có nguy cơ va chạm

| Phần tử | Quy tắc Preflight của Tailwind v4 | Hành vi mặc định trình duyệt bị xoá | Nguy cơ va chạm với 28 file CSS dự án | Vị trí minh chứng mã nguồn |
|---|---|---|---|---|
| `*, :before, :after` | `border: 0 solid;` | Mặc định không có viền hoặc border-width mặc định `medium` (~3px) | Nếu CSS chỉ khai báo `border-color` hoặc `border-style` mà quên khai báo `border-width`, viền sẽ biến mất hoàn toàn (0px) | Toàn hệ thống |
| `h1`–`h6` | `font-size: inherit; font-weight: inherit;` | Các thẻ tiêu đề có cỡ chữ to và độ đậm `bold` mặc định | Tiêu đề bị biến thành chữ thường 1rem và độ đậm thường nếu class bao ngoài không đặt `font-size`/`font-weight` | [`AreaListPage.css:258`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L258) (`.area-header__title-group h1`), [`CameraDetailPage.css:129`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css#L129) |
| `ol`, `ul`, `menu` | `list-style: none; margin: 0; padding: 0;` | Danh sách có dấu chấm tròn (`disc`) hoặc số thứ tự, cùng lề trái `padding-inline-start: 40px` | Mất toàn bộ dấu đầu dòng của danh sách giải thích/hướng dẫn | [`ManageAccountPage.jsx:1330, 1336`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L1330) (lập trình viên phải tự gõ ký tự `•` thủ công trong JSX); [`AreaListPage.css:1287`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L1287) (`.area-dependencies-box ul` chỉ đặt `padding-left: 18px` nhưng mất hoàn toàn bullet) |
| `img`, `svg`, `video`, `canvas` | `display: block; vertical-align: middle;` | `svg` và `canvas` mặc định là `inline` hoặc `inline-block` | Icon SVG đứng cạnh văn bản sẽ tự động nhảy xuống hàng mới nếu thẻ cha không có `display: flex` hoặc `display: inline-flex` | Các icon Lucide đặt trong thẻ inline `<span>` hoặc `<p>` không dùng flex |
| `button` | `font-family: inherit; font-size: 100%; line-height: inherit; color: inherit; text-transform: none;` | Nền xám nhạt, viền nổi, padding native của button trình duyệt | Xoá sạch viền và nền native (hầu hết custom CSS đã chủ động override) | [`index.css:40`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L40) |
| `table` | `text-indent: 0; border-color: inherit; border-collapse: collapse;` | `border-collapse: separate; border-spacing: 2px;` | Bảng tự động dính viền lại với nhau | [`ManageAccountPage.css:307`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css#L307) (`.account-table`) |

### C. Giới hạn xác minh qua đọc code tĩnh
Do Tailwind v4 đặt các reset trên trong `@layer base`, theo đặc tả CSS Cascading and Inheritance Level 4, bất kỳ CSS nào nằm ngoài layer (toàn bộ 28 file CSS của dự án) sẽ **tự động thắng `@layer base`** nếu cùng nhắm vào một selector hoặc có class riêng.  
Tuy nhiên, đối với các trường hợp mã nguồn **không viết CSS mà dựa ngầm vào mặc định trình duyệt** (như dấu chấm của thẻ `ul`/`li`, cỡ chữ của `h2`), Preflight sẽ triệt tiêu thuộc tính mặc định đó. Việc kiểm tra giao diện thực tế có bị vỡ hiển thị hay không cần chạy ứng dụng và bật F12 DevTools để kiểm tra Computed Styles của từng thẻ.

---

## 3. @theme của Tailwind v4 với :root của theme.css

### A. Khai báo `@theme` trong dự án
- Tìm kiếm chỉ thị `@theme` trên toàn bộ dự án: **Không có bất kỳ khai báo `@theme` nào**.
- File cấu hình `tailwind.config.js` / `tailwind.config.ts`: **Không tồn tại**.
- Tailwind v4 đang hoạt động hoàn toàn dựa vào cấu hình ngầm định (zero-config) từ gói `@tailwindcss/vite`.

### B. Đối chiếu tên biến giữa Tailwind v4 và `theme.css`

| Nhóm biến | Tiền tố trong Tailwind v4 | Tiền tố trong `theme.css` | Số token trùng tên |
|---|---|---|---|
| Nhận diện thương hiệu | Không có | `--brand-*` (10 token) | **0** |
| Giao diện sáng / tối | `--color-slate-*`, `--color-blue-*`... | `--theme-*` (61 token) | **0** |
| Hiệu ứng focus / trạng thái | Không có (dùng utility class) | `--focus-ring`, `--theme-on-*` | **0** |
| Typography / Spacing | `--font-sans`, `--font-mono`, `--spacing` | Không quản lý qua token | **0** |

- **Kết luận:** **Không có bất kỳ sự xung đột trùng tên biến CSS (token name collision) nào** giữa Tailwind v4 và `theme.css`.
- **Hệ quả kiến trúc:** Hai hệ thống token tồn tại song song như hai thế giới màu độc lập:
  - Utility classes của Tailwind (như `bg-slate-100`, `text-slate-700`, `border-slate-200` trong `data-table.jsx`) tham chiếu trực tiếp đến biến nội bộ `--color-slate-*` của Tailwind.
  - Các màn hình và linh kiện UI khác tham chiếu đến biến ngữ cảnh `--theme-*` của `theme.css`.

---

## 4. Cơ chế dark mode và rủi ro lệch pha

### A. Cơ chế đổi theme của `theme.css`
- File điều khiển: [`frontend/src/context/ThemeContext.jsx:14`](file:///Users/anhhao/Documents/SEP/frontend/src/context/ThemeContext.jsx#L14).
- Thuộc tính gán: `document.documentElement.setAttribute('data-theme', theme)`.
- Các giá trị: `data-theme="light"` hoặc `data-theme="dark"`.
- Lưu trữ: `localStorage.getItem('app_theme')`.
- Trong `theme.css`: Chuyển đổi toàn bộ màu sắc thông qua bộ chọn `[data-theme="dark"]` ([`theme.css:108`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L108)).

### B. Cơ chế đổi theme của Tailwind v4
- Mặc định trong Tailwind v4, variant `dark:` biên dịch thành truy vấn media của hệ điều hành: `@media (prefers-color-scheme: dark)`.
- Để Tailwind v4 nhận biết thuộc tính `data-theme="dark"`, dự án **bắt buộc phải có khai báo**:
  ```css
  @custom-variant dark (&:where([data-theme=dark], [data-theme=dark] *));
  ```
- Kiểm tra mã nguồn dự án: **Chưa có khai báo `@custom-variant dark` ở bất kỳ file nào**.

### C. Đo lường rủi ro lệch pha giao diện (Desynchronization)

| Kịch bản người dùng | Trạng thái OS | Trạng thái nút bấm app | Trạng thái `theme.css` | Trạng thái Tailwind (`dark:`) | Hệ quả trực quan |
|---|---|---|---|---|---|
| Kịch bản 1 | Light | Bật Dark | Đổi sang tối (`[data-theme="dark"]` kích hoạt) | Vẫn ở sáng (`prefers-color-scheme: light`) | Giao diện chính tối, nhưng linh kiện dùng `dark:` của Tailwind vẫn giữ màu sáng |
| Kịch bản 2 | Dark | Để mặc định Light | Ở trạng thái sáng (`data-theme="light"`) | Tự động kích hoạt dark (`prefers-color-scheme: dark`) | Giao diện nền sáng của `theme.css` bị các utility `dark:` của Tailwind đè màu tối |
| Hiện trạng `data-table.jsx` | Bất kỳ | Chuyển Dark | Toàn trang chuyển nền tối (`#111827`) | Không có variant `dark:` | Bảng `DataTable` giữ nguyên chữ xám đậm và viền xám sáng viết cứng (`text-slate-700`, `border-slate-200`), gây suy giảm tương phản nghiêm trọng |

### D. Đo đạc độ tương phản thực tế trên `data-table.jsx` (Ngưỡng WCAG AA: chữ ≥ 4.5:1, viền ≥ 3:1)
Mã nguồn tại [`frontend/src/components/ui/data-table.jsx:10, 23, 25`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/data-table.jsx#L10):

1. **Chữ nội dung `text-slate-700` (`#334155`):**
   - Trên nền Light (`#ffffff`): Tỷ lệ tương phản **10.4:1** (Đạt chuẩn AA).
   - Khi bật Dark mode, nền trang chuyển sang `--theme-bg-surface` (`#111827`): Tỷ lệ tương phản tụt xuống **1.77:1** (💥 Vi phạm nghiêm trọng WCAG AA, không thể đọc được chữ).
2. **Chữ tiêu đề bảng `text-slate-500` (`#64748b`):**
   - Trên nền Light (`#ffffff`): Tỷ lệ tương phản **4.86:1** (Đạt chuẩn AA).
   - Khi bật Dark mode trên nền `#111827`: Tỷ lệ tương phản tụt xuống **3.78:1** (💥 Vi phạm WCAG AA < 4.5:1).
3. **Đường viền bảng `border-slate-200` (`#e2e8f0`):**
   - Trên nền Light (`#ffffff`): Tỷ lệ tương phản **1.2:1** (Không đạt chuẩn viền UI component 3:1).
   - Khi bật Dark mode trên nền `#111827`: Tỷ lệ tương phản là **13.5:1** (Trở thành đường kẻ trắng sáng chói mắt giữa giao diện tối).

---

## 5. Mức độ trộn lẫn hiện tại trên bề mặt giao diện

### A. Thống kê file JSX sử dụng Tailwind classes
Toàn bộ mã nguồn frontend có đúng **2 file** chứa các class utility của Tailwind:
1. [`frontend/src/components/ui/data-table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/data-table.jsx): 12 utility classes (`w-full`, `overflow-x-auto`, `text-left`, `text-sm`, `border-b`, `border-slate-200`, `text-xs`, `uppercase`, `tracking-wide`, `text-slate-500`, `px-3`, `py-3`, `font-semibold`, `border-slate-100`, `text-slate-700`).
2. [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx): 8 utility classes tại các định nghĩa cột của bảng phân hệ:
   - Dòng 87: `className="font-medium"`
   - Dòng 92: `className="inline-flex items-center gap-2 text-slate-500"`
   - Dòng 93: `className="h-2 w-2 rounded-full bg-emerald-500 bg-slate-300"`
   - Dòng 385: `className="mt-4"` trên thẻ `<DataTable />`

*(Lưu ý: [`CameraDetailPage.jsx:579`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L579) và [`CameraListPage.jsx:200`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L200) có dùng class `font-bold`, nhưng class này được định nghĩa thủ công trong [`CameraListPage.css:296`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css#L296), không phải từ Tailwind).*

### B. Thống kê sử dụng `cn()` và `cva`
- Hàm `cn()`: Được định nghĩa tại [`frontend/src/lib/utils.ts:4`](file:///Users/anhhao/Documents/SEP/frontend/src/lib/utils.ts#L4) bằng `clsx` và `tailwind-merge`. Hiện chỉ được import và gọi tại đúng **1 nơi duy nhất**: [`frontend/src/components/ui/data-table.jsx:8`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/data-table.jsx#L8).
- Thư viện `cva` (`class-variance-authority`): Đã được khai báo trong `package.json:19` nhưng **chưa được import hay sử dụng ở bất kỳ dòng mã nào trong toàn bộ dự án**.

### C. Đối chiếu với 15 màn hình tại `docs/ui-structure-index.md`

| Màn hình | Sử dụng bộ 8 linh kiện `src/components/ui/` | Sử dụng `data-table.jsx` (Tailwind) | Trạng thái kiến trúc styling |
|---|---|---|---|
| `DashboardPage` | Không | **Có** (Bảng phân hệ hệ thống tại L385) | Lai (Thân trang dùng `DashboardPage.css`, bảng dùng Tailwind) |
| `UiKitPage` (Dev preview) | **Có** (Cả 8 component) | Không | CSS thuần + Token `theme.css` |
| 13 màn hình production còn lại | Không | Không | 100% CSS thuần truyền thống (Custom CSS riêng) |

### D. Kết luận về tỷ lệ trộn lẫn
- Số màn hình bị trộn lẫn trên production: **1 / 14 màn hình** (chỉ có `DashboardPage`, tương đương 7.1% số màn hình).
- Tỷ lệ diện tích bề mặt hiển thị thực tế chịu ảnh hưởng của Tailwind trên production: **Dưới 1%** (chỉ chiếm 1 bảng 4 dòng dữ liệu phụ tại góc dưới của màn hình Dashboard).

---

## 6. Ba phương án kiến trúc và đánh đổi kỹ thuật

### Phương án 1: Giữ CSS thuần, gỡ Tailwind khỏi luồng render

Chuyển đổi `data-table.jsx` sang sử dụng component `Table` thuần có sẵn của bộ UI kit (`src/components/ui/Table.jsx`), loại bỏ `@import 'tailwindcss'` khỏi `index.css`.

| Tiêu chí | Nội dung chi tiết |
|---|---|
| **Khối lượng công việc** | Rất nhỏ: đụng 4 file ([`DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx), [`data-table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/data-table.jsx) hoặc xoá file này, [`index.css`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css), [`vite.config.js`](file:///Users/anhhao/Documents/SEP/frontend/vite.config.js)). |
| **Cái gì mất đi** | Khả năng viết nhanh giao diện bằng utility classes; hệ sinh thái copy-paste của shadcn/ui. |
| **Tận dụng công Đợt 1** | **100%**. Bộ 8 component UI, 73 token `theme.css`.
| **Rủi ro lớn nhất** | Tốc độ xây dựng các màn hình phức tạp mới có thể chậm hơn nếu lập trình viên quen dùng utility-first; phải tự viết CSS cho từng biến thể linh kiện. |

---

### Phương án 2: Chuyển hẳn sang Tailwind, thoái dần `theme.css` và bộ 8 component

Đưa Tailwind v4 thành chuẩn duy nhất của dự án. Ánh xạ toàn bộ 73 token thành `@theme` của Tailwind, cấu hình `@custom-variant dark`, viết lại các màn hình từ CSS thuần sang Tailwind utilities và shadcn components.

| Tiêu chí | Nội dung chi tiết |
|---|---|
| **Khối lượng công việc** | Cực lớn: đụng toàn bộ **28 file CSS** (hơn 12.000 dòng CSS cần refactor hoặc xoá), viết lại CSS của 15 màn hình, chuyển đổi 8 component UI. |
| **Cái gì mất đi** | Toàn bộ 28 file CSS hiện tại; cấu trúc scope prefix BEM (`.arp-`, `.zone-`...); quy trình quản lý CSS modules/vanilla hiện tại. |
| **Tận dụng công Đợt 1** | **Rất thấp (~15%)**. Bộ 8 linh kiện CSS thuần bị bỏ; hệ thống token phải chuyển đổi cú pháp sang chỉ thị `@theme` của Tailwind v4; cơ chế kiểm tra màu (vốn quét file `.css`) phải viết lại hoàn toàn để quét class JSX. |
| **Rủi ro lớn nhất** | Rủi ro hồi quy giao diện trên diện rộng trong lúc chuyển đổi; tốn nhiều tuần làm việc chỉ để tái cấu trúc mã mà không tạo thêm tính năng nghiệp vụ; xung đột mã nguồn lớn khi merge nhánh. |

---

### Phương án 3: Kiến trúc lai có ranh giới phân định rõ ràng

Chấp nhận cả hai công nghệ cùng tồn tại, nhưng thiết lập quy định ranh giới tách bạch giữa các tầng kiến trúc.

| Tiêu chí | Nội dung chi tiết |
|---|---|
| **Quy tắc phân định tầng** | **Tầng Token/Core:** `theme.css` là nguồn sự thật duy nhất (Single Source of Truth). Cấu hình `@theme` của Tailwind v4 trỏ ngược vào các CSS variable của `theme.css` (ví dụ: `--color-primary: var(--theme-primary)`).<br>**Tầng Linh kiện (Components):** Khối linh kiện bảng dữ liệu phức tạp hoặc linh kiện mới dùng Tailwind/shadcn; bộ 8 linh kiện nền tảng hiện tại giữ nguyên.<br>**Tầng Màn hình (Pages):** Các màn hình cũ (2.302 dòng của `AreaListPage`, 1.568 dòng của `ManageAccountPage`) giữ nguyên CSS riêng, không refactor; các màn hình hoặc modal mới được phép dùng Tailwind classes. |
| **Khối lượng công việc** | Trung bình: cấu hình đồng bộ `@theme` và `@custom-variant dark` trong CSS (khoảng 3 file: `index.css`, `theme.css`, `data-table.jsx`). |
| **Cái gì mất đi** | Tính đồng nhất tuyệt đối của codebase (lập trình viên cần biết cả 2 cách viết); dung lượng bundle CSS tăng do nạp cả runtime utility lẫn CSS thuần. |
| **Tận dụng công Đợt 1** | **~85%**. Toàn bộ 73 token và bộ linh kiện được bảo tồn làm lõi; chỉ cần bổ sung cầu nối ánh xạ sang Tailwind. |
| **Rủi ro lớn nhất** | Lập trình viên mới có thể viết lẫn lộn utility class và custom class trong cùng một thẻ; nguy cơ vỡ dark mode nếu quên cấu hình `@custom-variant dark`. |

---

## 7. Danh sách câu hỏi cần chốt để ra quyết định

Để đội ngũ kỹ thuật đưa ra quyết định kiến trúc dứt khoát, cần giải đáp 4 câu hỏi trọng tâm sau:

1. **Về định hướng thư viện linh kiện:** Dự án muốn tự chủ giao diện bằng bộ linh kiện thiết kế riêng thuần CSS (Design System nội bộ), hay muốn tận dụng kho linh kiện mẫu phong phú của shadcn/ui để đẩy nhanh tiến độ làm các tính năng nghiệp vụ tiếp theo?
2. **Về nguồn lực và tiến độ:** Đội ngũ có chấp nhận dành thời gian để refactor toàn diện hơn 12.000 dòng CSS sang Tailwind (Phương án 2), hay ưu tiên giữ nguyên trạng để tập trung hoàn thiện tính năng người dùng thông thường (Phương án 1 hoặc 3)?
3. **Về tiêu chuẩn Dark mode:** Dự án có chấp nhận bổ sung cầu nối `@custom-variant dark` để các linh kiện Tailwind tự động đồng bộ theo thuộc tính `data-theme` của `ThemeContext` hay không?
4. **Về ranh giới chuyển đổi (nếu chọn Phương án 3):** Ai sẽ chịu trách nhiệm thẩm định để đảm bảo lập trình viên không sử dụng màu sắc viết cứng ngoài bảng token (`slate-*`, `zinc-*`) khi tạo các component mới bằng Tailwind?

---

*Dữ liệu khảo sát tính đến commit:* `30f20a4`  
*Ngày tạo:* 17/09/2026
