import React, { useState } from 'react';
import {
  Search,
  RotateCcw,
  Filter,
  CheckCircle2,
} from 'lucide-react';
import PageHeader from '../../components/ui/PageHeader';
import Button from '../../components/ui/Button';
import '../../styles/AuditLogPage.css';

export default function AuditLogPage() {
  const [filterSearch, setFilterSearch] = useState('');
  const [filterCategory, setFilterCategory] = useState('');
  const [filterStatus, setFilterStatus] = useState('');
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');
  const [timeRange, setTimeRange] = useState('all');

  // Fake Data matching the screenshot structure
  const fakeLogs = [
    {
      id: 'LOG-1001',
      date: '03/10/2026',
      time: '16:05:20',
      user: 'Nguyễn Văn Admin',
      email: 'admin@fpt.edu.vn',
      category: 'Tài khoản',
      catType: 'warning',
      actionTitle: 'Nạp người dùng (.zip)',
      actionDesc: 'Nạp hàng loạt 50 người dùng & hồ sơ khuôn mặt từ tập tin import_users_batch_v2.zip (Thành công: 50/50)',
      targetLabel: 'Tệp dữ liệu',
      targetValue: 'import_users_batch_v2.zip',
      status: 'Thành công',
    },
    {
      id: 'LOG-1002',
      date: '03/10/2026',
      time: '15:50:11',
      user: 'Nguyễn Văn Admin',
      email: 'admin@fpt.edu.vn',
      category: 'Tài khoản',
      catType: 'warning',
      actionTitle: 'Tạo tài khoản',
      actionDesc: 'Khởi tạo tài khoản mới cho cán bộ "Trần Thanh Hà (hatt@fpt.edu.vn)" với vai trò FACILITY_MANAGER',
      targetLabel: 'Tài khoản',
      targetValue: 'hatt@fpt.edu.vn',
      status: 'Thành công',
    },
    {
      id: 'LOG-1003',
      date: '03/10/2026',
      time: '15:42:10',
      user: 'Nguyễn Văn Admin',
      email: 'admin@fpt.edu.vn',
      category: 'Đăng nhập',
      catType: 'purple',
      actionTitle: 'Đăng nhập',
      actionDesc: 'Đăng nhập hệ thống thành công qua xác thực OAuth2 / Session',
      targetLabel: 'Hệ thống Admin Dashboard',
      targetValue: '',
      status: 'Thành công',
    },
    {
      id: 'LOG-1004',
      date: '03/10/2026',
      time: '15:30:18',
      user: 'Phạm Minh Hoàng',
      email: 'hoangpm@fpt.edu.vn',
      category: 'Khu vực',
      catType: 'success',
      actionTitle: 'Tạo mới khu vực',
      actionDesc: 'Khởi tạo khu vực mới "Phòng Lab AI & Robotics - Tầng 4 Tòa Beta"',
      targetLabel: 'Khu vực',
      targetValue: 'Beta-L4-Lab01',
      status: 'Thành công',
    }
  ];

  const getCategoryClass = (type) => {
    switch (type) {
      case 'warning': return 'category-badge--warning';
      case 'purple': return 'category-badge--purple';
      case 'success': return 'category-badge--success';
      default: return 'category-badge--default';
    }
  };

  return (
    <div className="audit-log-page">
      <PageHeader
        title="Lịch sử hệ thống"
        subtitle="Nhật ký ghi nhận lịch sử đăng nhập, thay đổi cấu hình khu vực và điều chỉnh camera toàn hệ thống."
      />

      <div className="section-card">
        {/* Filter Bar */}
        <div className="audit-filter-bar">
          <div className="audit-search-input">
            <Search size={16} className="audit-search-icon" />
            <input
              type="text"
              placeholder="Tìm theo người thực hiện, mô tả, IP..."
              value={filterSearch}
              onChange={(e) => setFilterSearch(e.target.value)}
            />
          </div>

          <div className="audit-filter-controls">
            <div className="audit-select-wrapper">
              <Filter size={14} className="audit-select-icon" />
              <select value={filterCategory} onChange={(e) => setFilterCategory(e.target.value)}>
                <option value="">Tất cả phân loại</option>
              </select>
            </div>

            <div className="audit-select-wrapper no-icon">
              <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)}>
                <option value="">Tất cả trạng thái</option>
              </select>
            </div>

            <div className="audit-date-range">
              <label className="audit-date-label">Từ</label>
              <input
                type="date"
                className="audit-date-input"
                value={filterFrom}
                onChange={(e) => setFilterFrom(e.target.value)}
              />
              <label className="audit-date-label">Đến</label>
              <input
                type="date"
                className="audit-date-input"
                value={filterTo}
                onChange={(e) => setFilterTo(e.target.value)}
              />
            </div>

            <div className="audit-time-toggle">
              <button
                className={`toggle-btn ${timeRange === 'all' ? 'active' : ''}`}
                onClick={() => setTimeRange('all')}
              >
                Tất cả
              </button>
              <button
                className={`toggle-btn ${timeRange === 'today' ? 'active' : ''}`}
                onClick={() => setTimeRange('today')}
              >
                Hôm nay
              </button>
            </div>

            <button className="audit-refresh-btn" aria-label="Làm mới">
              <RotateCcw size={16} />
            </button>
          </div>
        </div>

        {/* Table */}
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>THỜI GIAN</th>
                <th>NGƯỜI THỰC HIỆN</th>
                <th>LOẠI SỰ KIỆN</th>
                <th>HÀNH ĐỘNG & CHI TIẾT</th>
                <th>ĐỐI TƯỢNG TÁC ĐỘNG</th>
                <th>TRẠNG THÁI</th>
              </tr>
            </thead>
            <tbody>
              {fakeLogs.map((log) => (
                <tr key={log.id}>
                  <td>
                    <div className="flex-col-stack">
                      <span className="text-muted">{log.date}</span>
                      <span>{log.time}</span>
                    </div>
                  </td>
                  <td>
                    <div className="flex-col-stack">
                      <span className="font-semibold">{log.user}</span>
                      <span className="text-muted">{log.email}</span>
                    </div>
                  </td>
                  <td>
                    <span className={`category-badge ${getCategoryClass(log.catType)}`}>
                      {log.category}
                    </span>
                  </td>
                  <td>
                    <div className="flex-col-stack">
                      <span className="font-semibold">{log.actionTitle}</span>
                      <span className="text-muted-wrap">{log.actionDesc}</span>
                    </div>
                  </td>
                  <td>
                    <div className="flex-col-stack">
                      <span className="font-semibold">{log.targetLabel}:</span>
                      {log.targetValue && <span className="text-muted-wrap">{log.targetValue}</span>}
                    </div>
                  </td>
                  <td>
                    <div className="status-badge status-badge--success">
                      <CheckCircle2 size={14} />
                      {log.status}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
