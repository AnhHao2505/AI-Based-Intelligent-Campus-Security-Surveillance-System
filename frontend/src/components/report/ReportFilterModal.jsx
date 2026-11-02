import React, { useState } from 'react';
import { FileText, Calendar, Sparkles, AlertCircle } from 'lucide-react';
import { format, subDays, startOfMonth, endOfMonth, subMonths } from 'date-fns';
import { toast } from 'sonner';
import Modal from '../ui/Modal';
import { Button, Input } from '../ui';
import { getSecurityReportSummary } from '../../services/reportService';

export default function ReportFilterModal({ isOpen, onClose, onReportGenerated }) {
  const todayStr = format(new Date(), 'yyyy-MM-dd');
  const [startDate, setStartDate] = useState(() => format(subDays(new Date(), 6), 'yyyy-MM-dd'));
  const [endDate, setEndDate] = useState(todayStr);
  const [loading, setLoading] = useState(false);
  const [activePreset, setActivePreset] = useState('7_DAYS');
  const [error, setError] = useState('');

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

  const handleApplyPreset = (preset) => {
    setActivePreset(preset.id);
    const range = preset.getRange();
    setStartDate(range.start);
    setEndDate(range.end);
    setError('');
  };

  const handleDateChange = (type, val) => {
    setActivePreset('CUSTOM');
    setError('');
    if (type === 'start') setStartDate(val);
    else setEndDate(val);
  };

  const handleGenerate = async (e) => {
    e.preventDefault();
    if (!startDate || !endDate) {
      setError('Vui lòng chọn ngày bắt đầu và ngày kết thúc.');
      return;
    }
    if (startDate > endDate) {
      setError('Ngày bắt đầu không được lớn hơn ngày kết thúc.');
      return;
    }

    setLoading(true);
    setError('');
    try {
      const data = await getSecurityReportSummary({ startDate, endDate });
      toast.success('Tổng hợp dữ liệu báo cáo thành công!');
      onClose();
      onReportGenerated?.(data);
    } catch (err) {
      console.error('Lỗi khi tạo báo cáo:', err);
      const msg = err?.message || 'Không thể tổng hợp báo cáo. Vui lòng thử lại sau.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Tạo Báo Cáo Giám Sát An Ninh"
      subtitle="Tổng hợp dữ liệu sự cố, ra vào và ca trực theo chu kỳ thời gian"
      icon={FileText}
      iconVariant="brand"
      size="md"
      footer={
        <div className="flex items-center justify-end gap-3 w-full">
          <Button variant="ghost" onClick={onClose} disabled={loading}>
            Hủy
          </Button>
          <Button
            variant="primary"
            onClick={handleGenerate}
            loading={loading}
            icon={Sparkles}
          >
            Tạo báo cáo xem trước
          </Button>
        </div>
      }
    >
      <form onSubmit={handleGenerate} className="flex flex-col gap-5">
        {/* Preset Range Pills */}
        <div>
          <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300 mb-2.5 uppercase tracking-wider">
            Khoảng thời gian nhanh
          </label>
          <div className="flex flex-wrap gap-2">
            {PRESETS.map((p) => {
              const active = activePreset === p.id;
              return (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => handleApplyPreset(p)}
                  className={`px-3 py-1.5 rounded-md text-xs font-medium transition-all ${
                    active
                      ? 'bg-orange-600 text-white shadow-sm font-semibold'
                      : 'bg-slate-100 hover:bg-slate-200 dark:bg-slate-800 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700'
                  }`}
                >
                  {p.label}
                </button>
              );
            })}
          </div>
        </div>

        {/* Date Inputs */}
        <div className="grid grid-cols-2 gap-4">
          <Input
            label="Từ ngày"
            required
            type="date"
            value={startDate}
            max={todayStr}
            onChange={(e) => handleDateChange('start', e.target.value)}
            disabled={loading}
            className="w-full"
          />

          <Input
            label="Đến ngày"
            required
            type="date"
            value={endDate}
            max={todayStr}
            onChange={(e) => handleDateChange('end', e.target.value)}
            disabled={loading}
            className="w-full"
          />
        </div>

        {error && (
          <div className="p-3 rounded-lg bg-red-50 dark:bg-red-950/40 border border-red-200 dark:border-red-900 flex items-start gap-2.5 text-xs text-red-700 dark:text-red-400">
            <AlertCircle size={15} className="shrink-0 mt-0.5" />
            <span>{error}</span>
          </div>
        )}

        {/* Information Callout */}
        <div className="p-3.5 bg-slate-50 dark:bg-slate-800/60 rounded-lg border border-slate-200 dark:border-slate-700/80 text-xs text-slate-600 dark:text-slate-400">
          <div className="font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-2 mb-1.5">
            <Calendar size={14} className="text-orange-500 shrink-0" />
            <span>Phạm vi nội dung báo cáo:</span>
          </div>
          <p className="leading-relaxed m-0 text-slate-600 dark:text-slate-400">
            Hệ thống sẽ tổng hợp toàn bộ sự cố AI đã phát hiện, kết quả kiểm soát ra vào tại các camera,
            tỉ lệ thực thi ca trực của đội bảo vệ và thống kê cơ sở hạ tầng trong khoảng thời gian đã chọn.
          </p>
        </div>
      </form>
    </Modal>
  );
}

