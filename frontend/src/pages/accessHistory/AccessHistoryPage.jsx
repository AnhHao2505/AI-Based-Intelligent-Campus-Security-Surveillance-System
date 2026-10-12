import { History } from 'lucide-react';
import '../../styles/AccessHistoryPage.css';
import PageHeader from '../../components/ui/PageHeader';
import { EmptyState } from '../../components/ui';

/**
 * Lịch sử truy cập cá nhân.
 * Chưa có API lịch sử ra vào (chờ nối AI → checkEntry), nên trang chỉ hiện thông báo.
 * Không hiện bộ lọc giả (bị khoá) để tránh người dùng tưởng chức năng bị lỗi.
 * Không tải danh sách khu vực: GET /api/areas chỉ dành cho ADMIN/FM (NORMAL_USER nhận 403).
 */
export default function AccessHistoryPage() {
  return (
    <div className="ahp-container">
      <PageHeader
        title="Lịch sử truy cập"
        description="Các lần bạn được camera nhận diện tại các khu vực giám sát."
      />
      <div className="ahp-card">
        <EmptyState
          icon={History}
          title="Chưa có dữ liệu lịch sử ra vào"
          description="Lịch sử sẽ hiện ở đây khi hệ thống camera bắt đầu ghi nhận lượt ra vào của bạn."
        />
      </div>
    </div>
  );
}
