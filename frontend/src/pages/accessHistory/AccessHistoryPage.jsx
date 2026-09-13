import { useState, useEffect } from 'react';
import {
  History,
  CircleCheck,
  CircleX,
  RefreshCw
} from 'lucide-react';
import '../../styles/AccessHistoryPage.css';

export default function AccessHistoryPage() {
  // 2e. Cấu trúc chờ nối API
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Filters
  const [timeRange, setTimeRange] = useState('ALL');
  const [selectedAreaId, setSelectedAreaId] = useState('');

  // TODO: nối API khi backend có bảng lịch sử nhận diện (thuộc MF4)
  // Dự kiến: GET /api/access-history/my?from=&to=&areaId=&page=&size=
  // Trả về: { content: [{ timestamp, areaName, areaCode, result, note }],
  //           totalElements, totalPages }
  const fetchHistory = async () => {
    // chưa hiện thực
  };

  useEffect(() => {
    fetchHistory();
  }, [timeRange, selectedAreaId]);

  const timeFilterOptions = [
    { label: 'Hôm nay', val: 'TODAY' },
    { label: '7 ngày', val: '7D' },
    { label: '30 ngày', val: '30D' },
    { label: 'Tất cả', val: 'ALL' }
  ];

  const formatTimestamp = (ts) => {
    if (!ts) return '—';
    try {
      const d = new Date(ts);
      if (isNaN(d.getTime())) return ts;
      return d.toLocaleString('vi-VN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      });
    } catch {
      return ts;
    }
  };

  return (
    <div className="ahp-container">
      {/* 2b. Thẻ duy nhất: Lịch sử truy cập */}
      <div className="ahp-card">
        {/* Đầu thẻ */}
        <div className="ahp-card__header">
          <div className="ahp-card__header-left">
            <div className="ahp-card__icon-box">
              <History size={16} />
            </div>
            <div>
              <h2 className="ahp-card__title">Lịch sử truy cập</h2>
              <p className="ahp-card__subtitle">
                Các lần bạn được camera nhận diện tại các khu vực giám sát
              </p>
            </div>
          </div>

          {/* Bên phải đầu thẻ: bộ lọc khoảng thời gian & dropdown chọn khu vực */}
          <div className="ahp-filter-group">
            <div className="ahp-filter-tabs">
              {timeFilterOptions.map((opt) => (
                <button
                  key={opt.val}
                  type="button"
                  className={`ahp-filter-btn ${timeRange === opt.val ? 'ahp-filter-btn--active' : ''}`}
                  onClick={() => setTimeRange(opt.val)}
                >
                  {opt.label}
                </button>
              ))}
            </div>

            <select
              className="ahp-select"
              value={selectedAreaId}
              onChange={(e) => setSelectedAreaId(e.target.value)}
            >
              <option value="">Tất cả khu vực</option>
            </select>

            <button
              type="button"
              className="ahp-refresh-btn"
              onClick={fetchHistory}
              disabled={loading}
              title="Làm mới"
            >
              <RefreshCw size={13} className={loading ? 'ahp-spin' : ''} />
            </button>
          </div>
        </div>

        {/* 2c. Bảng dữ liệu / 2d. Trạng thái rỗng */}
        <div className="ahp-table-container">
          {loading ? (
            <div className="ahp-empty">
              <RefreshCw size={24} className="ahp-spin ahp-empty__icon" />
              <div className="ahp-empty__title">Đang tải lịch sử truy cập...</div>
            </div>
          ) : history.length === 0 ? (
            /* 2d. TRẠNG THÁI RỖNG */
            <div className="ahp-empty">
              <History size={32} strokeWidth={1.5} className="ahp-empty__icon" />
              <div className="ahp-empty__title">Chưa có lịch sử truy cập</div>
              <div className="ahp-empty__subtitle">
                Lịch sử sẽ xuất hiện khi hệ thống nhận diện bạn tại các khu vực được giám sát
              </div>
            </div>
          ) : (
            /* Bảng 4 cột: Thời gian · Khu vực · Kết quả · Ghi chú */
            <table className="ahp-table">
              <thead>
                <tr>
                  <th>Thời gian</th>
                  <th>Khu vực</th>
                  <th>Kết quả</th>
                  <th>Ghi chú</th>
                </tr>
              </thead>
              <tbody>
                {history.map((item, index) => {
                  const isValid =
                    item.result === 'VALID' ||
                    item.result === 'APPROVED' ||
                    item.result === 'Hợp lệ';

                  return (
                    <tr
                      key={item.id || index}
                      className={!isValid ? 'ahp-row--unauthorized' : ''}
                    >
                      <td className="ahp-table-time">
                        {formatTimestamp(item.timestamp)}
                      </td>
                      <td>
                        <div className="ahp-table-area-name">
                          {item.areaName || '—'}
                        </div>
                        {item.areaCode && (
                          <div className="ahp-table-area-code">
                            [{item.areaCode}]
                          </div>
                        )}
                      </td>
                      <td>
                        {isValid ? (
                          <span className="ahp-result-badge ahp-result-badge--valid">
                            <CircleCheck size={12} />
                            <span>Hợp lệ</span>
                          </span>
                        ) : (
                          <span className="ahp-result-badge ahp-result-badge--unauthorized">
                            <CircleX size={12} />
                            <span>Không có quyền</span>
                          </span>
                        )}
                      </td>
                      <td style={{ color: 'var(--theme-text-secondary)', fontSize: '12px' }}>
                        {item.note || '—'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
