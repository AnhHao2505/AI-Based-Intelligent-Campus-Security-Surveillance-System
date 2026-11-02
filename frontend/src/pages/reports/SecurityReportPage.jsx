import React, { useState, useEffect, useCallback } from 'react';
import {
  FileText,
  Calendar,
  Download,
  Loader2,
  RefreshCw,
  AlertCircle,
  Filter
} from 'lucide-react';
import { format, subDays, startOfMonth, endOfMonth, subMonths } from 'date-fns';
import { toast } from 'sonner';
import { PageHeader, Button, Input, LoadingState, ErrorState, EmptyState } from '../../components/ui';
import { getSecurityReportSummary, downloadSecurityReportPdf } from '../../services/reportService';
import ReportFilterModal from '../../components/report/ReportFilterModal';
import ReportPreviewModal from '../../components/report/ReportPreviewModal';
import ReportDocumentView from '../../components/report/ReportDocumentView';
import '../../components/report/ReportDocument.css';

export default function SecurityReportPage() {
  const todayStr = format(new Date(), 'yyyy-MM-dd');
  const [startDate, setStartDate] = useState(() => format(subDays(new Date(), 6), 'yyyy-MM-dd'));
  const [endDate, setEndDate] = useState(todayStr);
  const [activePreset, setActivePreset] = useState('7_DAYS');

  const [reportData, setReportData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(null);

  const [isFilterModalOpen, setIsFilterModalOpen] = useState(false);
  const [isPreviewModalOpen, setIsPreviewModalOpen] = useState(false);

  const PRESETS = [
    {
      id: 'TODAY',
      label: 'Hôm nay',
      getRange: () => {
        const t = format(new Date(), 'yyyy-MM-dd');
        return { start: t, end: t };
      },
    },
    {
      id: '7_DAYS',
      label: '7 ngày qua',
      getRange: () => ({
        start: format(subDays(new Date(), 6), 'yyyy-MM-dd'),
        end: format(new Date(), 'yyyy-MM-dd'),
      }),
    },
    {
      id: '30_DAYS',
      label: '30 ngày qua',
      getRange: () => ({
        start: format(subDays(new Date(), 29), 'yyyy-MM-dd'),
        end: format(new Date(), 'yyyy-MM-dd'),
      }),
    },
    {
      id: 'THIS_MONTH',
      label: 'Tháng này',
      getRange: () => ({
        start: format(startOfMonth(new Date()), 'yyyy-MM-dd'),
        end: format(new Date(), 'yyyy-MM-dd'),
      }),
    },
    {
      id: 'LAST_MONTH',
      label: 'Tháng trước',
      getRange: () => {
        const prev = subMonths(new Date(), 1);
        return {
          start: format(startOfMonth(prev), 'yyyy-MM-dd'),
          end: format(endOfMonth(prev), 'yyyy-MM-dd'),
        };
      },
    },
  ];

  const fetchReport = useCallback(async (start, end) => {
    if (!start || !end) return;
    if (start > end) {
      setError('Ngày bắt đầu không được lớn hơn ngày kết thúc');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await getSecurityReportSummary({ startDate: start, endDate: end });
      setReportData(data);
    } catch (err) {
      console.error('Lỗi tải báo cáo:', err);
      setError(err?.message || 'Không thể tải dữ liệu báo cáo an ninh.');
      setReportData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchReport(startDate, endDate);
  }, [fetchReport]);

  const handleApplyPreset = (preset) => {
    setActivePreset(preset.id);
    const range = preset.getRange();
    setStartDate(range.start);
    setEndDate(range.end);
    fetchReport(range.start, range.end);
  };

  const handleCustomFilterSubmit = (e) => {
    e.preventDefault();
    setActivePreset('CUSTOM');
    fetchReport(startDate, endDate);
  };

  const handleDownloadPdf = async () => {
    if (!reportData) return;
    setDownloading(true);
    try {
      await downloadSecurityReportPdf({
        startDate: reportData.startDate,
        endDate: reportData.endDate,
      });
      toast.success('Đã tải xuống file PDF báo cáo an ninh thành công!');
    } catch (err) {
      console.error('Lỗi khi tải file PDF:', err);
      toast.error(err?.message || 'Không thể tạo file PDF. Vui lòng thử lại.');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-5">
      {/* Header */}
      <PageHeader
        title="Báo Cáo An Ninh Toàn Diện"
        description="Tổng hợp dữ liệu giám sát an ninh, cảnh báo AI vi phạm, lưu lượng ra vào và hiệu suất ca trực"
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              onClick={() => setIsFilterModalOpen(true)}
              className="inline-flex items-center gap-1.5 text-xs py-1.5 px-3"
            >
              <Filter size={13} />
              <span>Lọc nâng cao</span>
            </Button>
            {reportData && (
              <Button
                variant="primary"
                onClick={handleDownloadPdf}
                disabled={downloading}
                className="inline-flex items-center gap-1.5 shadow-sm text-xs py-1.5 px-3.5 bg-slate-900 hover:bg-slate-800 disabled:bg-slate-600 text-white"
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
              </Button>
            )}
          </div>
        }
      />

      {/* Inline Quick Filter Toolbar */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg p-3.5 shadow-sm space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs font-bold text-slate-500 uppercase tracking-wider mr-1">
              Khoảng thời gian:
            </span>
            {PRESETS.map((p) => {
              const active = activePreset === p.id;
              return (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => handleApplyPreset(p)}
                  className={`px-3 py-1 rounded text-xs font-semibold transition-all ${
                    active
                      ? 'bg-slate-900 text-white shadow-sm'
                      : 'bg-slate-100 hover:bg-slate-200 dark:bg-slate-800 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-300'
                  }`}
                >
                  {p.label}
                </button>
              );
            })}
          </div>

          <form onSubmit={handleCustomFilterSubmit} className="flex items-center gap-2">
            <div className="flex items-center gap-1.5 text-xs text-slate-600">
              <Input
                type="date"
                value={startDate}
                max={todayStr}
                onChange={(e) => {
                  setStartDate(e.target.value);
                  setActivePreset('CUSTOM');
                }}
                className="py-1 px-2 text-xs"
              />
              <span>→</span>
              <Input
                type="date"
                value={endDate}
                max={todayStr}
                onChange={(e) => {
                  setEndDate(e.target.value);
                  setActivePreset('CUSTOM');
                }}
                className="py-1 px-2 text-xs"
              />
            </div>
            <Button
              type="submit"
              variant="outline"
              disabled={loading}
              className="py-1 px-2.5 text-xs inline-flex items-center gap-1"
            >
              {loading ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />}
              <span>Áp dụng</span>
            </Button>
          </form>
        </div>
      </div>

      {/* Main Content Area */}
      {loading ? (
        <div className="py-24">
          <LoadingState text="Đang trích xuất và tổng hợp báo cáo an ninh..." />
        </div>
      ) : error ? (
        <ErrorState
          title="Không thể tải báo cáo an ninh"
          message={error}
          onRetry={() => fetchReport(startDate, endDate)}
        />
      ) : !reportData ? (
        <EmptyState
          icon={FileText}
          title="Chưa có dữ liệu báo cáo"
          description="Vui lòng chọn khoảng thời gian để tạo báo cáo an ninh."
          action={
            <Button variant="primary" onClick={() => setIsFilterModalOpen(true)}>
              Chọn khoảng thời gian
            </Button>
          }
        />
      ) : (
        <div className="bg-slate-200/70 dark:bg-slate-950 p-6 rounded-lg border border-slate-300 dark:border-slate-800 overflow-x-auto shadow-inner">
          <div className="flex justify-end gap-2 mb-3">
            <Button
              variant="primary"
              onClick={handleDownloadPdf}
              disabled={downloading}
              className="inline-flex items-center gap-1.5 shadow-sm text-xs py-1.5 px-3 bg-slate-900 hover:bg-slate-800 disabled:bg-slate-600 text-white"
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
            </Button>
          </div>

          <div className="flex justify-center">
            <ReportDocumentView reportData={reportData} />
          </div>
        </div>
      )}

      {/* Filter Modal */}
      <ReportFilterModal
        isOpen={isFilterModalOpen}
        onClose={() => setIsFilterModalOpen(false)}
        onReportGenerated={(data) => {
          setReportData(data);
          setStartDate(data.startDate);
          setEndDate(data.endDate);
        }}
      />

      {/* Fullscreen Preview Modal */}
      <ReportPreviewModal
        isOpen={isPreviewModalOpen}
        onClose={() => setIsPreviewModalOpen(false)}
        reportData={reportData}
      />
    </div>
  );
}
