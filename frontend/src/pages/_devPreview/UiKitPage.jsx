import React, { useState } from 'react';
import {
  Plus,
  Trash2,
  Save,
  Search,
  Mail,
  User,
  Shield,
  ShieldAlert,
  AlertTriangle,
  Camera,
  CheckCircle,
  Sun,
  Moon,
  ExternalLink,
  Layers,
  KeyRound,
} from 'lucide-react';
import {
  Button,
  Input,
  Select,
  Badge,
  Card,
  Modal,
  Table,
  Pagination,
} from '../../components/ui';
import { useTheme } from '../../context/ThemeContext';
import './UiKitPage.css';

export default function UiKitPage() {
  const { theme, toggleTheme } = useTheme();

  // State for Input & Select
  const [inputValue, setInputValue] = useState('Camera Cổng Chính');
  const [inputEmail, setInputEmail] = useState('');
  const [selectRole, setSelectRole] = useState('ADMIN');

  // State for Modals
  const [activeModal, setActiveModal] = useState(null); // 'sm' | 'md' | 'lg' | 'xl' | 'no-backdrop-close' | null

  // State for Interactive Pagination
  const [interactivePage, setInteractivePage] = useState(0);

  // Sample Table Data (5 rows)
  const sampleTableData = [
    {
      id: 1,
      code: 'CAM-T01-01',
      name: 'Camera Cổng Thư Viện',
      area: 'Sảnh Tòa Alpha',
      securityLevel: 'public',
      status: 'ONLINE',
      updatedAt: '15/09/2026 21:40',
    },
    {
      id: 2,
      code: 'CAM-T02-04',
      name: 'Hành Lang Phòng Lab AI',
      area: 'Khu Vực Nghiên Cứu',
      securityLevel: 'semiPrivate',
      status: 'ONLINE',
      updatedAt: '15/09/2026 20:15',
    },
    {
      id: 3,
      code: 'CAM-SRV-02',
      name: 'Phòng Máy Chủ Trung Tâm',
      area: 'Server Room',
      securityLevel: 'private',
      status: 'ONLINE',
      updatedAt: '15/09/2026 19:30',
    },
    {
      id: 4,
      code: 'CAM-PRK-03',
      name: 'Bãi Đỗ Xe Cán Bộ',
      area: 'Bãi Xe B',
      securityLevel: 'public',
      status: 'OFFLINE',
      updatedAt: '15/09/2026 18:00',
    },
    {
      id: 5,
      code: 'CAM-GATE-A',
      name: 'Cổng Ra Vào Chính',
      area: 'Cổng Chính FPTU',
      securityLevel: 'semiPrivate',
      status: 'ONLINE',
      updatedAt: '15/09/2026 17:45',
    },
  ];

  const tableColumns = [
    {
      key: 'code',
      title: 'MÃ CAMERA',
      width: '140px',
      isCode: true,
    },
    {
      key: 'name',
      title: 'TÊN THIẾT BỊ',
      render: (val) => <span style={{ fontWeight: 600 }}>{val}</span>,
    },
    {
      key: 'area',
      title: 'KHU VỰC PHỤ TRÁCH',
    },
    {
      key: 'securityLevel',
      title: 'CẤP ĐỘ AN NINH',
      width: '160px',
      align: 'center',
      render: (level) => {
        if (level === 'public') {
          return <Badge variant="public" dot>PUBLIC</Badge>;
        }
        if (level === 'semiPrivate') {
          return <Badge variant="semiPrivate" dot>SEMI_PRIVATE</Badge>;
        }
        return <Badge variant="private" dot>PRIVATE</Badge>;
      },
    },
    {
      key: 'status',
      title: 'TRẠNG THÁI',
      width: '120px',
      align: 'center',
      render: (st) => (
        <Badge variant={st === 'ONLINE' ? 'success' : 'danger'}>
          {st}
        </Badge>
      ),
    },
    {
      key: 'updatedAt',
      title: 'CẬP NHẬT',
      width: '160px',
      align: 'right',
      render: (t) => <span style={{ color: 'var(--theme-text-muted)' }}>{t}</span>,
    },
  ];

  const roleOptions = [
    { value: 'ADMIN', label: 'Quản trị viên (ADMIN)' },
    { value: 'FACILITY_MANAGER', label: 'Quản lý cơ sở vật chất (FACILITY_MANAGER)' },
    { value: 'INTERNAL_GUARD', label: 'Bảo vệ nội bộ (INTERNAL_GUARD)' },
    { value: 'OUTSOURCED_GUARD', label: 'Bảo vệ thuê ngoài (OUTSOURCED_GUARD)' },
    { value: 'NORMAL_USER', label: 'Người dùng thường (NORMAL_USER)' },
  ];

  return (
    <div className="ui-kit-page">
      <div className="ui-kit-container">
        {/* Top Header & Theme Switcher */}
        <header className="ui-kit-header">
          <div>
            <div className="ui-kit-badge">
              <Layers size={14} />
              <span>DESIGN SYSTEM COMPONENT KIT</span>
            </div>
            <h1 className="ui-kit-title">UI Kit Preview & Visual Test Bench</h1>
            <p className="ui-kit-subtitle">
              Kiểm tra toàn bộ 8 base component trong cả Light Mode và Dark Mode trước khi áp dụng vào các màn hình.
            </p>
          </div>

          <div className="ui-kit-header__actions">
            <button
              type="button"
              className="ui-kit-theme-toggle"
              onClick={toggleTheme}
              title={`Chuyển sang chế độ ${theme === 'light' ? 'Tối' : 'Sáng'}`}
            >
              {theme === 'light' ? <Moon size={18} /> : <Sun size={18} />}
              <span>Chế độ: <strong>{theme === 'light' ? 'Light Mode' : 'Dark Mode'}</strong></span>
            </button>
          </div>
        </header>

        {/* ------------------------------------------------------------------
            1. BUTTON COMPONENT
            ------------------------------------------------------------------ */}
        <section className="ui-kit-section">
          <div className="ui-kit-section__header">
            <h2 className="ui-kit-section__title">1. Button Component</h2>
            <span className="ui-kit-section__meta">4 variants × 2 sizes, icon, loading, disabled</span>
          </div>

          <Card padding="md">
            <div className="ui-kit-grid">
              {/* Row 1: MD Size with Icons */}
              <div className="ui-kit-block">
                <div className="ui-kit-block__title">Size MD (38px) — Có Icon</div>
                <div className="ui-kit-row">
                  <Button variant="primary" icon={Plus}>Thêm khu vực</Button>
                  <Button variant="secondary" icon={Save}>Lưu thay đổi</Button>
                  <Button variant="danger" icon={Trash2}>Xóa thiết bị</Button>
                  <Button variant="ghost" icon={ExternalLink}>Xem tài liệu</Button>
                </div>
              </div>

              {/* Row 2: SM Size */}
              <div className="ui-kit-block">
                <div className="ui-kit-block__title">Size SM (32px) — Nhỏ gọn</div>
                <div className="ui-kit-row">
                  <Button variant="primary" size="sm" icon={Plus}>Thêm</Button>
                  <Button variant="secondary" size="sm" icon={Save}>Lưu</Button>
                  <Button variant="danger" size="sm" icon={Trash2}>Xóa</Button>
                  <Button variant="ghost" size="sm">Chi tiết</Button>
                </div>
              </div>

              {/* Row 3: Loading & Disabled States */}
              <div className="ui-kit-block">
                <div className="ui-kit-block__title">Trạng thái Loading & Disabled</div>
                <div className="ui-kit-row">
                  <Button variant="primary" loading>Đang xử lý...</Button>
                  <Button variant="secondary" loading size="sm">Đang tải...</Button>
                  <Button variant="primary" disabled icon={Plus}>Bị vô hiệu hoá</Button>
                  <Button variant="secondary" disabled>Disabled</Button>
                  <Button variant="danger" disabled icon={Trash2}>Xóa</Button>
                </div>
              </div>
            </div>
          </Card>
        </section>

        {/* ------------------------------------------------------------------
            2. INPUT & SELECT COMPONENTS
            ------------------------------------------------------------------ */}
        <section className="ui-kit-section">
          <div className="ui-kit-section__header">
            <h2 className="ui-kit-section__title">2. Input & Select Components</h2>
            <span className="ui-kit-section__meta">Label trên, 100% width, error, hint, icon</span>
          </div>

          <Card padding="md">
            <div className="ui-kit-grid-2col">
              {/* Column 1: Input variations */}
              <div className="ui-kit-stack">
                <Input
                  label="Tên thiết bị"
                  value={inputValue}
                  onChange={(e) => setInputValue(e.target.value)}
                  placeholder="Ví dụ: Camera cổng chính A"
                  required
                  hint="Tên định danh hiển thị trên bản đồ số và cảnh báo"
                />

                <Input
                  label="Tìm kiếm người dùng"
                  placeholder="Nhập tên, mã số hoặc email..."
                  icon={Search}
                />

                <Input
                  label="Địa chỉ email cán bộ"
                  value={inputEmail}
                  onChange={(e) => setInputEmail(e.target.value)}
                  placeholder="user@fpt.edu.vn"
                  icon={Mail}
                  required
                  error="Email không đúng định dạng @fpt.edu.vn"
                />

                <Input
                  label="Mã định danh hệ thống (Không thể đổi)"
                  value="SE193843-ROOT"
                  disabled
                  icon={User}
                />
              </div>

              {/* Column 2: Select variations */}
              <div className="ui-kit-stack">
                <Select
                  label="Vai trò tài khoản"
                  value={selectRole}
                  onChange={(e) => setSelectRole(e.target.value)}
                  options={roleOptions}
                  required
                  hint="Quyền hạn kiểm soát truy cập và phân vùng quản trị"
                />

                <Select
                  label="Chọn khu vực quản lý"
                  placeholder="-- Vui lòng chọn một khu vực --"
                  options={[
                    { value: 'zone-1', label: 'Tòa Alpha - Tầng 1' },
                    { value: 'zone-2', label: 'Tòa Beta - Tầng 2' },
                    { value: 'zone-3', label: 'Phòng Server Cốt Lõi' },
                  ]}
                />

                <Select
                  label="Lựa chọn phân quyền"
                  value=""
                  placeholder="Chưa chọn quyền"
                  options={roleOptions}
                  required
                  error="Trường này bắt buộc phải chọn vai trò hợp lệ"
                />

                <Select
                  label="Trạng thái phân vùng (Bị khóa)"
                  value="ADMIN"
                  options={roleOptions}
                  disabled
                />
              </div>
            </div>
          </Card>
        </section>

        {/* ------------------------------------------------------------------
            3. BADGE COMPONENT
            ------------------------------------------------------------------ */}
        <section className="ui-kit-section">
          <div className="ui-kit-section__header">
            <h2 className="ui-kit-section__title">3. Badge Component</h2>
            <span className="ui-kit-section__meta">3 màu ngữ nghĩa cố định + 5 màu theme token</span>
          </div>

          <Card padding="md">
            <div className="ui-kit-stack">
              <div>
                <div className="ui-kit-block__title">Màu cấp độ an ninh (Cố định ở cả 2 theme):</div>
                <div className="ui-kit-row">
                  <Badge variant="public" dot icon={Shield}>PUBLIC (#22c55e)</Badge>
                  <Badge variant="semiPrivate" dot icon={ShieldAlert}>SEMI_PRIVATE (#fbbf24)</Badge>
                  <Badge variant="private" dot icon={AlertTriangle}>PRIVATE (#f87171)</Badge>
                </div>
              </div>

              <div>
                <div className="ui-kit-block__title">Trạng thái ngữ nghĩa & Thương hiệu:</div>
                <div className="ui-kit-row">
                  <Badge variant="success" dot icon={CheckCircle}>Hoạt động (Success)</Badge>
                  <Badge variant="warning" dot icon={AlertTriangle}>Cảnh báo (Warning)</Badge>
                  <Badge variant="danger" dot icon={Trash2}>Ngắt kết nối (Danger)</Badge>
                  <Badge variant="neutral">Bản nháp (Neutral)</Badge>
                  <Badge variant="brand" dot icon={Camera}>Thương hiệu (Brand)</Badge>
                </div>
              </div>
            </div>
          </Card>
        </section>

        {/* ------------------------------------------------------------------
            4. CARD COMPONENT
            ------------------------------------------------------------------ */}
        <section className="ui-kit-section">
          <div className="ui-kit-section__header">
            <h2 className="ui-kit-section__title">4. Card Component</h2>
            <span className="ui-kit-section__meta">Dải màu 3px bên trái (border-radius 0 10px 10px 0), paddings sm/md</span>
          </div>

          <div className="ui-kit-grid-3col">
            <Card padding="md">
              <h3 style={{ margin: '0 0 8px 0', fontSize: '1rem', color: 'var(--theme-text-primary)' }}>Card Mặc Định</h3>
              <p style={{ margin: 0, fontSize: '0.85rem', color: 'var(--theme-text-secondary)' }}>
                Padding MD (20px 24px), viền tiêu chuẩn bao quanh 4 phía.
              </p>
            </Card>

            <Card padding="md" accentColor="#22c55e">
              <h3 style={{ margin: '0 0 8px 0', fontSize: '1rem', color: 'var(--theme-text-primary)' }}>Accent PUBLIC (#22c55e)</h3>
              <p style={{ margin: 0, fontSize: '0.85rem', color: 'var(--theme-text-secondary)' }}>
                Dải màu 3px xanh lá bên trái, bo góc: <code>0 10px 10px 0</code>.
              </p>
            </Card>

            <Card padding="md" accentColor="#f87171">
              <h3 style={{ margin: '0 0 8px 0', fontSize: '1rem', color: 'var(--theme-text-primary)' }}>Accent PRIVATE (#f87171)</h3>
              <p style={{ margin: 0, fontSize: '0.85rem', color: 'var(--theme-text-secondary)' }}>
                Dải màu 3px đỏ bên trái, thể hiện khu vực an ninh nghiêm ngặt.
              </p>
            </Card>

            <Card padding="md" accentColor="var(--brand-blue)">
              <h3 style={{ margin: '0 0 8px 0', fontSize: '1rem', color: 'var(--theme-text-primary)' }}>Accent Brand Blue</h3>
              <p style={{ margin: 0, fontSize: '0.85rem', color: 'var(--theme-text-secondary)' }}>
                Dải màu 3px xanh thương hiệu <code>var(--brand-blue)</code>.
              </p>
            </Card>

            <Card padding="sm" accentColor="#fbbf24">
              <h3 style={{ margin: '0 0 4px 0', fontSize: '0.9rem', color: 'var(--theme-text-primary)' }}>Padding SM + SEMI_PRIVATE</h3>
              <p style={{ margin: 0, fontSize: '0.8rem', color: 'var(--theme-text-secondary)' }}>
                Dải màu vàng cảnh báo 3px, padding nhỏ 12px 16px.
              </p>
            </Card>

            <Card padding="md" onClick={() => alert('Đã bấm vào Card tương tác!')}>
              <h3 style={{ margin: '0 0 8px 0', fontSize: '1rem', color: 'var(--theme-text-primary)' }}>Clickable Card</h3>
              <p style={{ margin: 0, fontSize: '0.85rem', color: 'var(--theme-text-secondary)' }}>
                Di chuột thử: có hiệu ứng nhấc lên và đổ bóng (hover lift).
              </p>
            </Card>
          </div>
        </section>

        {/* ------------------------------------------------------------------
            5. MODAL COMPONENT
            ------------------------------------------------------------------ */}
        <section className="ui-kit-section">
          <div className="ui-kit-section__header">
            <h2 className="ui-kit-section__title">5. Modal Component</h2>
            <span className="ui-kit-section__meta">Badge icon 30×30, 4 kích thước (sm/md/lg/xl), 4 icon variants, phím Escape</span>
          </div>

          <Card padding="md">
            <p style={{ marginTop: 0, color: 'var(--theme-text-secondary)', fontSize: '0.9rem' }}>
              Bấm các nút dưới đây để kiểm tra hộp thoại modal với đầy đủ kích thước và biến thể màu icon badge:
            </p>
            <div className="ui-kit-row">
              <Button variant="primary" icon={Plus} onClick={() => setActiveModal('md')}>
                Mở Modal MD (560px — Brand Icon)
              </Button>
              <Button variant="danger" icon={Trash2} onClick={() => setActiveModal('sm')}>
                Mở Modal SM (420px — Danger Icon)
              </Button>
              <Button variant="secondary" icon={AlertTriangle} onClick={() => setActiveModal('lg')}>
                Mở Modal LG (680px — Warning Icon)
              </Button>
              <Button variant="secondary" icon={CheckCircle} onClick={() => setActiveModal('xl')}>
                Mở Modal XL (900px — Success Icon)
              </Button>
              <Button variant="ghost" icon={KeyRound} onClick={() => setActiveModal('no-backdrop-close')}>
                Modal Chặn Backdrop Click (closeOnBackdrop=false)
              </Button>
            </div>
          </Card>

          {/* Modal 1: MD with Brand Icon */}
          <Modal
            isOpen={activeModal === 'md'}
            onClose={() => setActiveModal(null)}
            size="md"
            title="Thêm khu vực an ninh mới"
            subtitle="Cấu hình tọa độ đa giác và thông số phân vùng giám sát"
            icon={Camera}
            iconVariant="brand"
            footer={
              <>
                <Button variant="ghost" onClick={() => setActiveModal(null)}>Hủy bỏ</Button>
                <Button variant="primary" icon={Save} onClick={() => setActiveModal(null)}>Lưu khu vực</Button>
              </>
            }
          >
            <div className="ui-kit-stack">
              <p style={{ margin: 0 }}>
                Kiểm tra ô icon tiêu đề bên trên: có kích thước đúng <strong>30×30px bo góc 8px</strong>.
                Nền icon là <code>var(--brand-subtle)</code>, màu icon là <code>var(--brand-text)</code>.
              </p>
              <Input label="Tên khu vực" placeholder="Ví dụ: Sảnh Alpha" required />
              <Select label="Cấp độ an ninh" options={[
                { value: 'PUBLIC', label: 'PUBLIC — Khu vực công cộng' },
                { value: 'SEMI_PRIVATE', label: 'SEMI_PRIVATE — Khu vực bán riêng tư' },
                { value: 'PRIVATE', label: 'PRIVATE — Khu vực bảo mật nghiêm ngặt' },
              ]} />
            </div>
          </Modal>

          {/* Modal 2: SM with Danger Icon */}
          <Modal
            isOpen={activeModal === 'sm'}
            onClose={() => setActiveModal(null)}
            size="sm"
            title="Xác nhận vô hiệu hoá camera"
            subtitle="Hành động này sẽ ngắt kết nối luồng WebRTC streaming"
            icon={Trash2}
            iconVariant="danger"
            footer={
              <>
                <Button variant="ghost" onClick={() => setActiveModal(null)}>Hủy</Button>
                <Button variant="danger" onClick={() => setActiveModal(null)}>Xác nhận ngắt</Button>
              </>
            }
          >
            <p style={{ margin: 0, color: 'var(--theme-danger-text)' }}>
              Bạn có chắc chắn muốn ngắt camera <strong>CAM-T01-01</strong> khỏi hệ thống giám sát an ninh trực tiếp không?
            </p>
          </Modal>

          {/* Modal 3: LG with Warning Icon */}
          <Modal
            isOpen={activeModal === 'lg'}
            onClose={() => setActiveModal(null)}
            size="lg"
            title="Giải trình hạ cấp mức an ninh"
            subtitle="Quy định an toàn FPTU ICSSS — Mã lỗi ERR_AREA_007"
            icon={AlertTriangle}
            iconVariant="warning"
            footer={
              <>
                <Button variant="ghost" onClick={() => setActiveModal(null)}>Quay lại</Button>
                <Button variant="primary" onClick={() => setActiveModal(null)}>Gửi giải trình</Button>
              </>
            }
          >
            <div className="ui-kit-stack">
              <p style={{ margin: 0 }}>
                Hạ cấp từ <strong>PRIVATE</strong> sang <strong>PUBLIC</strong> đòi hỏi phải ghi nhận lý do giải trình cụ thể phục vụ kiểm toán an ninh.
              </p>
              <Input label="Lý do giải trình" placeholder="Nhập lý do chi tiết..." required />
            </div>
          </Modal>

          {/* Modal 4: XL with Success Icon */}
          <Modal
            isOpen={activeModal === 'xl'}
            onClose={() => setActiveModal(null)}
            size="xl"
            title="Nhật ký sự kiện giám sát toàn diện"
            subtitle="Dữ liệu tổng hợp sự cố từ hệ thống AI và bảo vệ trực tiếp"
            icon={CheckCircle}
            iconVariant="success"
            footer={
              <Button variant="secondary" onClick={() => setActiveModal(null)}>Đóng</Button>
            }
          >
            <p style={{ margin: 0 }}>
              Đây là Modal kích thước <strong>XL (900px)</strong> thích hợp cho việc hiển thị bảng dữ liệu hoặc sơ đồ phân tích phức tạp.
            </p>
          </Modal>

          {/* Modal 5: Close on Backdrop = false */}
          <Modal
            isOpen={activeModal === 'no-backdrop-close'}
            onClose={() => setActiveModal(null)}
            closeOnBackdrop={false}
            size="md"
            title="Hộp thoại bắt buộc thao tác"
            subtitle="closeOnBackdrop={false} — Click ngoài backdrop sẽ không đóng"
            icon={KeyRound}
            iconVariant="brand"
            footer={
              <Button variant="primary" onClick={() => setActiveModal(null)}>Hiểu và đóng</Button>
            }
          >
            <p style={{ margin: 0 }}>
              Hãy thử nhấp chuột ra ngoài vùng nền mờ phía sau: Modal <strong>KHÔNG</strong> tự đóng. Bạn chỉ có thể đóng bằng nút Đóng hoặc phím <strong>Escape</strong>.
            </p>
          </Modal>
        </section>

        {/* ------------------------------------------------------------------
            6. TABLE COMPONENT
            ------------------------------------------------------------------ */}
        <section className="ui-kit-section">
          <div className="ui-kit-section__header">
            <h2 className="ui-kit-section__title">6. Table Component</h2>
            <span className="ui-kit-section__meta">Header 11px chữ hoa, skeleton loading (giữ header), empty state (giữ header)</span>
          </div>

          <div className="ui-kit-stack">
            {/* Table 1: Normal with 5 Rows */}
            <div>
              <div className="ui-kit-block__title">Bảng có dữ liệu (5 dòng):</div>
              <Table
                columns={tableColumns}
                data={sampleTableData}
                onRowClick={(rec) => console.log('Clicked row:', rec.code)}
              />
            </div>

            {/* Table 2: Loading with Skeleton Rows */}
            <div>
              <div className="ui-kit-block__title">Bảng ở trạng thái Đang tải (Loading Skeleton — GIỮ HEADER):</div>
              <Table
                columns={tableColumns}
                loading={true}
                data={[]}
              />
            </div>

            {/* Table 3: Empty State */}
            <div>
              <div className="ui-kit-block__title">Bảng rỗng (Empty State — GIỮ HEADER):</div>
              <Table
                columns={tableColumns}
                loading={false}
                data={[]}
                emptyText="Chưa có thiết bị camera nào được ghi nhận trong phân vùng này"
              />
            </div>
          </div>
        </section>

        {/* ------------------------------------------------------------------
            7. PAGINATION COMPONENT
            ------------------------------------------------------------------ */}
        <section className="ui-kit-section">
          <div className="ui-kit-section__header">
            <h2 className="ui-kit-section__title">7. Pagination Component</h2>
            <span className="ui-kit-section__meta">Nội bộ 0-indexed Spring Boot, chỉ +1 khi hiển thị, active dùng --brand-*</span>
          </div>

          <Card padding="md">
            <div className="ui-kit-stack">
              <div>
                <div className="ui-kit-block__title">Trang đầu tiên (currentPage = 0, totalPages = 12, totalElements = 120):</div>
                <Pagination
                  currentPage={0}
                  totalPages={12}
                  totalElements={120}
                  pageSize={10}
                  itemLabel="camera"
                  onPageChange={(p) => console.log('Go to page:', p)}
                />
              </div>

              <div>
                <div className="ui-kit-block__title">Trang ở giữa (currentPage = 5, totalPages = 12, totalElements = 120):</div>
                <Pagination
                  currentPage={5}
                  totalPages={12}
                  totalElements={120}
                  pageSize={10}
                  itemLabel="camera"
                  onPageChange={(p) => console.log('Go to page:', p)}
                />
              </div>

              <div>
                <div className="ui-kit-block__title">Trang cuối cùng (currentPage = 11, totalPages = 12, totalElements = 120):</div>
                <Pagination
                  currentPage={11}
                  totalPages={12}
                  totalElements={120}
                  pageSize={10}
                  itemLabel="camera"
                  onPageChange={(p) => console.log('Go to page:', p)}
                />
              </div>

              <div>
                <div className="ui-kit-block__title">Demo Tương Tác Trực Tiếp (Bấm đổi trang thử nghiệm):</div>
                <Pagination
                  currentPage={interactivePage}
                  totalPages={8}
                  totalElements={75}
                  pageSize={10}
                  itemLabel="tài khoản"
                  onPageChange={(newPage) => setInteractivePage(newPage)}
                />
                <div style={{ marginTop: '8px', fontSize: '0.8rem', color: 'var(--brand-text)' }}>
                  Trang nội bộ (0-index): <strong>{interactivePage}</strong> — Trang hiển thị giao diện (1-index): <strong>{interactivePage + 1}</strong>
                </div>
              </div>
            </div>
          </Card>
        </section>
      </div>
    </div>
  );
}
