import React from 'react';
import { useSearchParams } from 'react-router-dom';
import PageHeader from '../../components/ui/PageHeader';
import AccessRequestReviewPage from '../accessRequest/AccessRequestReviewPage';
import GuestVisitReviewPage from '../guestVisit/GuestVisitReviewPage';
import { AREA_LEVEL_CONFIG } from '../../utils/areaHelpers';
// Thanh tab dùng lại kiểu tab của màn Quản lý tài khoản (account-tabs)
import '../../styles/ManageAccountPage.css';
import '../../styles/ApprovalPage.css';

// Mỗi tab giữ tham số URL riêng (mở từ thông báo); đổi tab thì xoá tham số của tab kia, giữ ?tab
const TABS = [
  { key: 'requests', label: 'Yêu cầu truy cập', params: ['requestId'] },
  { key: 'guests', label: 'Lượt khách', params: ['visitId'] },
];
const DEFAULT_TAB = 'requests';

/**
 * Màn "Phê duyệt yêu cầu" (FM): gộp duyệt yêu cầu truy cập và duyệt lượt khách thành 2 tab, đọc / ghi ?tab=requests|guests.
 */
export default function ApprovalPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const activeTab = TABS.some((t) => t.key === tabParam) ? tabParam : DEFAULT_TAB;

  const handleTabChange = (key) => {
    if (key === activeTab) return;
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      TABS.filter((t) => t.key !== key).forEach((t) => t.params.forEach((p) => next.delete(p)));
      next.set('tab', key);
      return next;
    }, { replace: true });
  };

  return (
    <div className="apv-container">
      <PageHeader
        title="Phê duyệt yêu cầu"
        description={`Xét duyệt yêu cầu truy cập và lượt khách vào các khu vực ${AREA_LEVEL_CONFIG.CONFIDENTIAL.badgeLabel} và ${AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.badgeLabel}.`}
      />

      <nav className="account-tabs" role="tablist" aria-label="Loại yêu cầu cần phê duyệt">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            id={`apv-tab-${t.key}`}
            aria-selected={activeTab === t.key}
            aria-controls="apv-tabpanel"
            className={`account-tab-btn ${activeTab === t.key ? 'account-tab-btn--active' : ''}`}
            onClick={() => handleTabChange(t.key)}
          >
            <span>{t.label}</span>
          </button>
        ))}
      </nav>

      <div id="apv-tabpanel" role="tabpanel" aria-labelledby={`apv-tab-${activeTab}`}>
        {activeTab === 'guests' ? <GuestVisitReviewPage embedded /> : <AccessRequestReviewPage embedded />}
      </div>
    </div>
  );
}
