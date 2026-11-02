import { apiGet, apiFetchBlob } from '../api/apiClient';

/**
 * Lấy dữ liệu tổng hợp báo cáo an ninh theo khoảng thời gian.
 * @param {Object} params
 * @param {string} params.startDate - YYYY-MM-DD
 * @param {string} params.endDate - YYYY-MM-DD
 * @returns {Promise<Object>} SecurityReportSummaryDto
 */
export async function getSecurityReportSummary({ startDate, endDate }) {
  if (!startDate || !endDate) {
    throw new Error('Vui lòng chọn đầy đủ ngày bắt đầu và ngày kết thúc');
  }
  const query = new URLSearchParams({ startDate, endDate }).toString();
  return apiGet(`/api/reports/security-summary?${query}`);
}

/**
 * Kích hoạt tải ngay lập tức file PDF từ Blob trực tiếp về máy tính.
 * Tuyệt đối không mở hộp thoại in trình duyệt (window.print).
 */
function triggerDirectBlobDownload(blob, filename) {
  const blobUrl = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = blobUrl;
  link.download = filename;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  setTimeout(() => {
    window.URL.revokeObjectURL(blobUrl);
  }, 2000);
}

/**
 * Tải trực tiếp file PDF báo cáo an ninh:
 * - Khi kết nối Backend: Tải file PDF vector chuẩn OpenPDF từ endpoint /api/reports/download-pdf.
 * - Khi ở chế độ Demo: Tự động kết xuất vector PDF trực tiếp bằng jsPDF (không qua html2canvas để tránh lỗi oklch).
 *
 * @param {Object} params
 * @param {string} params.startDate - YYYY-MM-DD
 * @param {string} params.endDate - YYYY-MM-DD
 */
export async function downloadSecurityReportPdf({ startDate, endDate }) {
  if (!startDate || !endDate) {
    throw new Error('Vui lòng chọn đầy đủ ngày bắt đầu và ngày kết thúc');
  }

  const filename = `Bao-Cao-An-Ninh-FPTU-${startDate}-den-${endDate}.pdf`;
  const token = localStorage.getItem('accessToken');

  // Chế độ demo
  if (token?.startsWith('frontend-demo-')) {
    const { generateDemoPdfBlob } = await import('../utils/demoPdfGenerator');
    const demoBlob = await generateDemoPdfBlob({ startDate, endDate });
    triggerDirectBlobDownload(demoBlob, filename);
    return;
  }

  // Kết nối Backend thực tế
  const query = new URLSearchParams({ startDate, endDate }).toString();
  try {
    const blob = await apiFetchBlob(`/api/reports/download-pdf?${query}`);
    triggerDirectBlobDownload(blob, filename);
  } catch (backendError) {
    console.warn('Backend download failed, falling back to local vector generation:', backendError);
    // Dự phòng khi backend không khả dụng
    const { generateDemoPdfBlob } = await import('../utils/demoPdfGenerator');
    const fallbackBlob = await generateDemoPdfBlob({ startDate, endDate });
    triggerDirectBlobDownload(fallbackBlob, filename);
  }
}
