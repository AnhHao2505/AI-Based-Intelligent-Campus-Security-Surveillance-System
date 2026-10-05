import { useState } from 'react';
import {
  History,
  RefreshCw
} from 'lucide-react';
import '../../styles/AccessHistoryPage.css';
import PageHeader from '../../components/ui/PageHeader';

export default function AccessHistoryPage() {
  // Bộ lọc đang ở chế độ disabled vì chức năng lịch sử ra vào đang phát triển.
  // Không tải danh sách khu vực: GET /api/areas chỉ dành cho ADMIN/FM (NORMAL_USER nhận 403).
  const [timeRange, setTimeRange] = useState('ALL');
  const [selectedAreaId, setSelectedAreaId] = useState('');

  const timeFilterOptions = [
    { label: 'Hôm nay', val: 'TODAY' },
    { label: '7 ngày', val: '7D' },
    { label: '30 ngày', val: '30D' },
    { label: 'Tất cả', val: 'ALL' }
  ];

  return (
    <div className="ahp-container">
      <PageHeader
        title="Lịch sử truy cập"
        description="Các lần bạn được camera nhận diện tại các khu vực giám sát."
      />
      {/* Thẻ duy nhất: Lịch sử truy cập */}
      <div className="ahp-card">
        {/* Đầu thẻ: bộ lọc */}
        <div className="ahp-card__header ahp-card__header--toolbar">

          {/* Bên phải đầu thẻ: bộ lọc khoảng thời gian & dropdown chọn khu vực (disabled do chưa có API) */}
          <div className="ahp-filter-group">
            <div className="ahp-filter-tabs">
              {timeFilterOptions.map((opt) => (
                <button
                  key={opt.val}
                  type="button"
                  className={`ahp-filter-btn ${timeRange === opt.val ? 'ahp-filter-btn--active' : ''}`}
                  onClick={() => setTimeRange(opt.val)}
                  disabled
                  title="Chức năng đang phát triển"
                >
                  {opt.label}
                </button>
              ))}
            </div>

            <select
              className="ahp-select"
              value={selectedAreaId}
              onChange={(e) => setSelectedAreaId(e.target.value)}
              disabled
              title="Chức năng đang phát triển"
            >
              <option value="">Tất cả khu vực</option>
            </select>

            <button
              type="button"
              className="ahp-refresh-btn"
              disabled
              title="Chức năng đang phát triển"
            >
              <RefreshCw size={13} />
            </button>
          </div>
        </div>

        {/* Thông báo tính năng đang phát triển */}
        <div className="ahp-table-container">
          <div className="ahp-empty" style={{ padding: '60px 20px' }}>
            <History size={40} strokeWidth={1.5} className="ahp-empty__icon" style={{ opacity: 0.5, marginBottom: '16px' }} />
            <div className="ahp-empty__title" style={{ fontSize: '1.125rem', fontWeight: 600 }}>
              Chức năng lịch sử ra vào đang phát triển
            </div>
            <div className="ahp-empty__subtitle" style={{ maxWidth: '480px', margin: '8px auto 0', lineHeight: 1.5 }}>
              Chức năng lịch sử ra vào đang được hoàn thiện. Vui lòng quay lại sau.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
