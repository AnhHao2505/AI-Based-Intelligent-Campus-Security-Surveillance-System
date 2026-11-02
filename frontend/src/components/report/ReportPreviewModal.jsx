import React, { useState } from 'react';
import { Download, X, FileText, Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import ReportDocumentView from './ReportDocumentView';
import { downloadSecurityReportPdf } from '../../services/reportService';
import './ReportDocument.css';

export default function ReportPreviewModal({ isOpen, onClose, reportData }) {
  const [downloading, setDownloading] = useState(false);

  if (!isOpen || !reportData) return null;

  const handleDownloadPdf = async () => {
    setDownloading(true);
    try {
      await downloadSecurityReportPdf({
        startDate: reportData.startDate,
        endDate: reportData.endDate,
      });
      toast.success('Đã tải xuống file PDF báo cáo an ninh thành công!');
    } catch (err) {
      console.error('Lỗi khi xuất PDF:', err);
      toast.error(err?.message || 'Không thể tạo file PDF. Vui lòng thử lại.');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="report-modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="report-modal-container">
        {/* Modal Toolbar */}
        <div className="report-modal-toolbar">
          <div className="report-modal-toolbar__title">
            <FileText size={17} className="text-slate-800" />
            <span className="font-semibold text-slate-900">Xem trước Báo cáo Giám sát An ninh</span>
          </div>

          <div className="report-modal-toolbar__actions">
            <button
              type="button"
              className="px-3.5 py-1.5 bg-slate-900 hover:bg-slate-800 disabled:bg-slate-600 text-white rounded text-xs font-semibold inline-flex items-center gap-1.5 transition-colors shadow-sm"
              onClick={handleDownloadPdf}
              disabled={downloading}
              title="Tải trực tiếp file PDF về máy tính"
            >
              {downloading ? (
                <>
                  <Loader2 size={13} className="animate-spin" />
                  <span>Đang tải file PDF...</span>
                </>
              ) : (
                <>
                  <Download size={13} />
                  <span>Tải về PDF</span>
                </>
              )}
            </button>
            <button
              type="button"
              className="p-1 hover:bg-slate-200 rounded text-slate-500 transition-colors"
              onClick={onClose}
              aria-label="Đóng"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Paper Container */}
        <div className="report-modal-scroll">
          <ReportDocumentView reportData={reportData} />
        </div>
      </div>
    </div>
  );
}
