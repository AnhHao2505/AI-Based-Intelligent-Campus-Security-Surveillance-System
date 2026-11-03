import React from 'react';
import { format, parseISO } from 'date-fns';
import { vi } from 'date-fns/locale';

export default function ReportDocumentView({ reportData }) {
  if (!reportData) return null;

  const percentFormat = (val) => {
    if (val === null || val === undefined) return '0.0%';
    return `${Math.round(val * 1000) / 10}%`;
  };

  const formatSeconds = (sec) => {
    if (sec === null || sec === undefined) return '—';
    if (sec < 60) return `${sec} giây`;
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return s > 0 ? `${m} phút ${s} giây` : `${m} phút`;
  };

  const formatDateSafely = (dateStr) => {
    if (!dateStr) return '—';
    try {
      const d = typeof dateStr === 'string' ? parseISO(dateStr) : new Date(dateStr);
      return format(d, 'dd/MM/yyyy HH:mm:ss', { locale: vi });
    } catch {
      return dateStr;
    }
  };

  const formatDateOnly = (dateStr) => {
    if (!dateStr) return '—';
    try {
      const d = typeof dateStr === 'string' ? parseISO(dateStr) : new Date(dateStr);
      return format(d, 'dd/MM/yyyy', { locale: vi });
    } catch {
      return dateStr;
    }
  };

  const reportId = `SEC-RPT-${format(new Date(reportData.generatedAt || new Date()), 'yyyyMMdd-HHmmss')}`;

  return (
    <article className="report-paper w-full" id="security-report-document">
      {/* Institutional Document Header */}
      <header className="report-header">
        <div className="report-header__top">
          <div>
            <div className="report-header__org-title">
              Đại học FPT - Cơ sở Tân Uyên
            </div>
            <div className="report-header__org-sub">
              Hệ thống giám sát thông minh bằng camera tích hợp AI
            </div>
          </div>
          <div className="report-header__badge">
            LƯU HÀNH NỘI BỘ
          </div>
        </div>

        <h1 className="report-header__main-title">
          BÁO CÁO TỔNG HỢP GIÁM SÁT AN NINH & KIỂM SOÁT VÙNG HẠN CHẾ
        </h1>
        <div className="report-header__sub-title">
          (Trích xuất dữ liệu AI Computer Vision, lịch sử ra vào, điều phối ca trực bảo vệ & kiểm toán vận hành)
        </div>

        <div className="report-header__meta-grid">
          <div>
            <div className="report-meta-item__label">Chu kỳ báo cáo:</div>
            <div className="report-meta-item__value">
              {formatDateOnly(reportData.startDate)} — {formatDateOnly(reportData.endDate)}
            </div>
          </div>
          <div>
            <div className="report-meta-item__label">Số hiệu văn bản:</div>
            <div className="report-meta-item__value">{reportId}</div>
          </div>
          <div>
            <div className="report-meta-item__label">Cán bộ lập báo cáo:</div>
            <div className="report-meta-item__value">
              {reportData.generatedByName} ({reportData.generatedByEmail})
            </div>
          </div>
        </div>
      </header>

      {/* Section I: Executive Summary & KPIs */}
      <section className="report-section">
        <h2 className="report-section__title">
          <span>I. TỔNG HỢP CHỈ SỐ HOẠT ĐỘNG AN NINH (EXECUTIVE SUMMARY)</span>
        </h2>
        <p className="report-section__desc">
          Trong khoảng thời gian từ ngày <strong>{formatDateOnly(reportData.startDate)}</strong> đến hết ngày{' '}
          <strong>{formatDateOnly(reportData.endDate)}</strong>, hệ thống camera AI tại cơ sở Tân Uyên đã thực hiện
          giám sát liên tục các khu vực phân quyền (Public, Confidential, Highly Confidential). Toàn bộ luồng dữ liệu
          được phân tích tự động bằng mô hình nhận diện người YOLO, máy trạng thái phát hiện lảng vảng và trích xuất đặc trưng khuôn mặt ArcFace 512D.
        </p>

        <table className="report-table">
          <thead>
            <tr>
              <th style={{ width: '40%' }}>Hạng mục chỉ số an ninh</th>
              <th className="report-col-right" style={{ width: '20%' }}>Số lượng ghi nhận</th>
              <th style={{ width: '40%' }}>Ghi chú nghiệp vụ / Tỉ lệ đối soát</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="font-semibold">Tổng số sự cố an ninh phát hiện</td>
              <td className="report-col-right font-semibold">{reportData.totalIncidents}</td>
              <td>
                Đã xác minh thật: <strong>{reportData.verifiedIncidents}</strong> vụ · Báo sai/nhầm:{' '}
                <strong>{reportData.dismissedIncidents}</strong> ({percentFormat(reportData.falseAlarmRate)})
              </td>
            </tr>
            <tr>
              <td>Sự cố đang chờ hoặc đang xử lý</td>
              <td className="report-col-right">{reportData.pendingIncidents}</td>
              <td>Chưa có bảo vệ tiếp nhận hoặc đang tiến hành xác minh thực địa</td>
            </tr>
            <tr>
              <td className="font-semibold">Tổng lượt quét kiểm soát ra vào</td>
              <td className="report-col-right font-semibold">{reportData.totalAccessScans.toLocaleString()}</td>
              <td>
                Hợp lệ: <strong>{reportData.authorizedAccessCount.toLocaleString()}</strong> ({percentFormat(reportData.totalAccessScans > 0 ? reportData.authorizedAccessCount / reportData.totalAccessScans : 0)}) · Vi phạm: <strong>{reportData.violationAccessCount}</strong>
              </td>
            </tr>
            <tr>
              <td>Thời gian phản hồi tiếp nhận trung bình (Claim)</td>
              <td className="report-col-right font-mono">{formatSeconds(reportData.avgResponseTimeSeconds)}</td>
              <td>Tính từ lúc AI kích hoạt cảnh báo đến khi bảo vệ chốt nhận trên thiết bị di động</td>
            </tr>
            <tr>
              <td>Thời gian xử lý & đóng sự cố trung bình (Resolve)</td>
              <td className="report-col-right font-mono">{formatSeconds(reportData.avgResolutionTimeSeconds)}</td>
              <td>Bao gồm thời gian di chuyển, kiểm tra hiện trường, lập biên bản hoặc nhắc nhở</td>
            </tr>
            <tr>
              <td>Tình trạng mạng lưới Camera giám sát</td>
              <td className="report-col-right font-mono">{reportData.onlineCamerasCount}/{reportData.activeCamerasCount}</td>
              <td>
                Tỉ lệ trực tuyến đạt:{' '}
                <strong>
                  {reportData.activeCamerasCount > 0
                    ? percentFormat(reportData.onlineCamerasCount / reportData.activeCamerasCount)
                    : '100%'}
                </strong>{' '}
                (không có sự cố ngắt kết nối kéo dài)
              </td>
            </tr>
            <tr>
              <td>Thực thi kế hoạch ca trực bảo vệ</td>
              <td className="report-col-right font-mono">{reportData.completedShiftsCount}/{reportData.scheduledShiftsCount}</td>
              <td>Tỉ lệ hoàn tất ca phân công đạt: <strong>{percentFormat(reportData.shiftFulfillmentRate)}</strong></td>
            </tr>
            <tr>
              <td>Lưu lượng khách đăng ký thăm cơ sở</td>
              <td className="report-col-right font-mono">{reportData.totalGuestVisits}</td>
              <td>Đã được phê duyệt hợp lệ và hoàn tất thủ tục: <strong>{reportData.approvedGuestVisits}</strong> lượt</td>
            </tr>
          </tbody>
        </table>
      </section>

      {/* Section II: Threat & Incident Analysis */}
      <section className="report-section">
        <h2 className="report-section__title">
          <span>II. PHÂN TÍCH CHI TIẾT SỰ CỐ AN NINH & NGUY CƠ VI PHẠM</span>
        </h2>
        <p className="report-section__desc">
          Các sự cố được phân loại dựa trên tính chất vi phạm khu vực cấm, đối chiếu quyền truy cập theo vai trò (User Access Level)
          và danh sách nhân sự được phân công đích danh (Assigned Personnel).
        </p>

        <div className="report-two-cols">
          <div>
            <div className="text-[11px] font-bold uppercase mb-1">1. Phân loại theo tính chất sự cố</div>
            <table className="report-table report-table--compact">
              <thead>
                <tr>
                  <th>Loại sự cố</th>
                  <th className="report-col-right">Số vụ</th>
                  <th className="report-col-right">Tỉ lệ</th>
                </tr>
              </thead>
              <tbody>
                {reportData.eventTypeCounts?.length > 0 ? (
                  reportData.eventTypeCounts.map((item) => (
                    <tr key={item.code}>
                      <td>{item.label}</td>
                      <td className="report-col-right">{item.count}</td>
                      <td className="report-col-right">{percentFormat(item.percentage)}</td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={3} className="text-center py-2 text-slate-500">
                      Không có sự cố ghi nhận trong chu kỳ
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div>
            <div className="text-[11px] font-bold uppercase mb-1">2. Phân loại theo mức độ nghiêm trọng</div>
            <table className="report-table report-table--compact">
              <thead>
                <tr>
                  <th>Mức độ</th>
                  <th className="report-col-right">Số vụ</th>
                  <th className="report-col-right">Tỉ lệ</th>
                </tr>
              </thead>
              <tbody>
                {reportData.severityCounts?.map((sev) => (
                  <tr key={sev.code}>
                    <td>
                      <span className={`report-marker ${sev.code === 'CRITICAL' ? 'report-marker--critical' : ''}`}>
                        {sev.label.toUpperCase()}
                      </span>
                    </td>
                    <td className="report-col-right">{sev.count}</td>
                    <td className="report-col-right">{percentFormat(sev.percentage)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Hotspot Areas and Resolution Category */}
        <div className="report-two-cols mt-2">
          <div>
            <div className="text-[11px] font-bold uppercase mb-1">3. Khu vực phát sinh sự cố nhiều nhất</div>
            <table className="report-table report-table--compact">
              <thead>
                <tr>
                  <th>Tên khu vực</th>
                  <th>Phân khu / Tòa nhà</th>
                  <th className="report-col-right">Số vụ</th>
                </tr>
              </thead>
              <tbody>
                {reportData.topIncidentAreas?.length > 0 ? (
                  reportData.topIncidentAreas.map((area, idx) => (
                    <tr key={area.areaId || idx}>
                      <td>{area.areaName}</td>
                      <td>{area.building || 'Khuôn viên'}</td>
                      <td className="report-col-right">{area.count} vụ</td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={3} className="text-center py-2 text-slate-500">
                      Không có khu vực vi phạm
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div>
            <div className="text-[11px] font-bold uppercase mb-1">4. Kết quả xử lý thực địa của bảo vệ</div>
            <table className="report-table report-table--compact">
              <thead>
                <tr>
                  <th>Hình thức kết luận</th>
                  <th className="report-col-right">Số vụ</th>
                  <th className="report-col-right">Tỉ lệ</th>
                </tr>
              </thead>
              <tbody>
                {reportData.resolutionCategoryCounts?.length > 0 ? (
                  reportData.resolutionCategoryCounts.map((rc) => (
                    <tr key={rc.code}>
                      <td>{rc.label}</td>
                      <td className="report-col-right">{rc.count}</td>
                      <td className="report-col-right">{percentFormat(rc.percentage)}</td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={3} className="text-center py-2 text-slate-500">
                      Chưa có sự cố được đóng trong chu kỳ
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {/* Section III: Access Control & Perimeter Scans */}
      <section className="report-section">
        <h2 className="report-section__title">
          <span>III. KIỂM SOÁT TRUY CẬP RA VÀO & VI PHẠM CHU VI</span>
        </h2>
        <p className="report-section__desc">
          Nhật ký ra vào tổng hợp từ toàn bộ các camera cửa kiểm soát và ranh giới phân vùng. Mọi trường hợp sai quyền,
          ngoài giờ hoặc không xác định danh tính đều được ghi nhận tự động phục vụ công tác hậu kiểm.
        </p>

        <table className="report-table">
          <thead>
            <tr>
              <th style={{ width: '22%' }}>Kết quả kiểm soát</th>
              <th style={{ width: '50%' }}>Quy định an ninh áp dụng</th>
              <th className="report-col-right" style={{ width: '14%' }}>Số lượt quét</th>
              <th className="report-col-right" style={{ width: '14%' }}>Tỉ trọng (%)</th>
            </tr>
          </thead>
          <tbody>
            {reportData.accessResultCounts?.map((res) => (
              <tr key={res.code}>
                <td>
                  <span className="report-marker">{res.label.toUpperCase()}</span>
                </td>
                <td className="text-[11px] text-slate-700">
                  {(res.code === 'AUTHORIZED_PERSON' || res.code === 'AUTHORIZED_ACCESS') && 'Nhận diện khuôn mặt hợp lệ, khớp cấp độ truy cập hoặc có phân công/duyệt đơn'}
                  {(res.code === 'UNAUTHORIZED_PERSON' || res.code === 'UNAUTHORIZED_ACCESS') && 'Nhận diện được danh tính nhưng không đủ thẩm quyền khu vực hoặc ngoài khung giờ được duyệt'}
                  {res.code === 'ACCESS_OUT_OF_TIME' && 'Có thẩm quyền nhưng tiếp cận ngoài khung giờ làm việc hoặc ngoài thời gian phê duyệt'}
                  {res.code === 'UNKNOWN_PERSON' && 'Đối tượng không tồn tại dữ liệu sinh trắc trong hệ thống, chưa đăng ký khách'}
                  {(res.code === 'PERSON_INTRUSION' || res.code === 'INTRUSION') && 'Xuất hiện trong vùng giám sát ngoài giờ hoạt động'}
                  {res.code === 'SUSPICIOUS_MOVING_OBJECT' && 'Chuyển động nghi vấn trong vùng ROI không gắn với người'}
                </td>
                <td className="report-col-right font-mono font-medium">{res.count.toLocaleString()}</td>
                <td className="report-col-right font-mono">{percentFormat(res.percentage)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {/* Daily Trend Overview */}
        {reportData.dailyAccessTrends?.length > 1 && (
          <div className="mt-2">
            <div className="text-[11px] font-bold uppercase mb-1">Bảng diễn biến lưu lượng ra vào theo ngày</div>
            <table className="report-table report-table--compact">
              <thead>
                <tr>
                  <th>Ngày</th>
                  <th className="report-col-right">Hợp lệ</th>
                  <th className="report-col-right">Sai quyền</th>
                  <th className="report-col-right">Ngoài giờ</th>
                  <th className="report-col-right">Người lạ</th>
                  <th className="report-col-right">Xâm nhập</th>
                  <th className="report-col-right font-bold">Tổng lượt</th>
                </tr>
              </thead>
              <tbody>
                {reportData.dailyAccessTrends.map((d) => (
                  <tr key={d.date}>
                    <td className="font-mono">{formatDateOnly(d.date)}</td>
                    <td className="report-col-right font-mono">{d.authorized.toLocaleString()}</td>
                    <td className="report-col-right font-mono">{d.unauthorized}</td>
                    <td className="report-col-right font-mono">{d.outOfTime}</td>
                    <td className="report-col-right font-mono">{d.unknownPerson}</td>
                    <td className="report-col-right font-mono">{d.intrusion}</td>
                    <td className="report-col-right font-mono font-bold">{d.total.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Section IV: Guard Force Performance */}
      <section className="report-section">
        <h2 className="report-section__title">
          <span>IV. HOẠT ĐỘNG LỰC LƯỢNG BẢO VỆ & ĐIỀU PHỐI HIỆN TRƯỜNG</span>
        </h2>
        <p className="report-section__desc">
          Lực lượng bảo vệ nhận thông báo sự cố tức thì qua ứng dụng Mobile chuyên dụng. Thời gian tiếp nhận và kết luận xử lý
          được lưu trữ trên hệ thống làm cơ sở đánh giá chỉ số KPI chấp hành nhiệm vụ.
        </p>

        <table className="report-table report-table--compact">
          <thead>
            <tr>
              <th style={{ width: '15%' }}>Mã nhân viên</th>
              <th style={{ width: '35%' }}>Họ và tên cán bộ bảo vệ</th>
              <th className="report-col-right" style={{ width: '25%' }}>Số vụ đã tiếp nhận & xử lý</th>
              <th style={{ width: '25%' }}>Tình trạng nhiệm vụ</th>
            </tr>
          </thead>
          <tbody>
            {reportData.topGuards?.length > 0 ? (
              reportData.topGuards.map((g) => (
                <tr key={g.guardId || g.userCode}>
                  <td className="font-mono">{g.userCode}</td>
                  <td className="font-semibold">{g.fullName}</td>
                  <td className="report-col-right font-mono font-bold">{g.resolvedCount} vụ</td>
                  <td>Đã hoàn tất báo cáo hiện trường</td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={4} className="text-center py-2 text-slate-500">
                  Chưa ghi nhận ca trực xử lý sự cố trong chu kỳ
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      {/* Section V: Significant Incidents Log */}
      <section className="report-section">
        <h2 className="report-section__title">
          <span>V. NHẬT KÝ CHI TIẾT CÁC SỰ CỐ TRỌNG YẾU TRONG KỲ</span>
        </h2>
        <p className="report-section__desc">
          Danh sách trích xuất các vụ việc có mức độ nguy cấp, xâm nhập trái phép hoặc đã lập biên bản vi phạm trong kỳ báo cáo:
        </p>

        <table className="report-table report-table--compact">
          <thead>
            <tr>
              <th style={{ width: '13%' }}>Thời điểm</th>
              <th style={{ width: '18%' }}>Vị trí & Camera</th>
              <th style={{ width: '17%' }}>Hành vi ghi nhận</th>
              <th style={{ width: '9%' }}>Mức độ</th>
              <th style={{ width: '12%' }}>Đối tượng</th>
              <th style={{ width: '13%' }}>Bảo vệ xử lý</th>
              <th style={{ width: '18%' }}>Ghi chú kết luận</th>
            </tr>
          </thead>
          <tbody>
            {reportData.significantIncidents?.length > 0 ? (
              reportData.significantIncidents.map((inc) => (
                <tr key={inc.id}>
                  <td className="font-mono text-[10.5px] whitespace-nowrap">
                    {formatDateSafely(inc.detectedAt)}
                  </td>
                  <td>
                    <div>{inc.location}</div>
                    <div className="font-mono text-[10px] text-slate-600">{inc.cameraCode}</div>
                  </td>
                  <td>{inc.eventType}</td>
                  <td>
                    <span className="report-marker">{inc.severity}</span>
                  </td>
                  <td className="font-mono">{inc.subjectUserCode}</td>
                  <td>{inc.resolvedByName}</td>
                  <td className="text-[10.5px]">
                    <strong>{inc.resolutionCategory}:</strong> {inc.resolutionNotes}
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={7} className="text-center py-3 text-slate-500">
                  Không có sự cố vi phạm nghiêm trọng phát sinh trong chu kỳ báo cáo
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      {/* Section VI: Official Sign-off & Regulatory Legal Notice */}
      <footer className="report-signoff">
        <div className="report-signoff__disclaimer">
          Căn cứ Quy chế Quản lý & Vận hành An ninh Cơ sở của Đại học FPT - Cơ sở Tân Uyên, văn bản này là tài liệu kiểm toán nội bộ
          được kết xuất trực tiếp từ máy chủ cơ sở dữ liệu giám sát an ninh. Mọi hình ảnh snapshot, log đối soát khuôn mặt
          và biên bản xử lý vi phạm liên quan đã được lưu trữ an toàn tại hệ thống MinIO Storage phục vụ đối chiếu khi có yêu cầu.
        </div>

        <div className="report-signoff__grid">
          <div>
            <div className="report-signoff__role">CÁN BỘ LẬP BÁO CÁO</div>
            <div className="report-signoff__note">(Ký, ghi rõ họ tên và chức danh)</div>
            <div className="report-signoff__space" />
            <div className="report-signoff__name">{reportData.generatedByName}</div>
            <div className="text-[11px] font-mono text-slate-600">{reportData.generatedByEmail}</div>
          </div>

          <div>
            <div className="report-signoff__role">BAN AN NINH & QUẢN LÝ CƠ SỞ</div>
            <div className="report-signoff__note">(Xác nhận, phê duyệt và lưu trữ)</div>
            <div className="report-signoff__space" />
            <div className="report-signoff__name">Ban Quản lý Cơ sở Tân Uyên</div>
            <div className="text-[11px] text-slate-600">Đại học FPT - Cơ sở Tân Uyên</div>
          </div>
        </div>
      </footer>
    </article>
  );
}

