import React, { useState, useEffect } from 'react';
import { X, FileSpreadsheet, Download, Loader2, Calendar, Users, AlertCircle, CheckCircle2 } from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';
import Select from '../ui/Select';

export default function ExportTimesheetModal({ isOpen, onClose, teams = [], currentDate = new Date() }) {
  const [selectedMonthYear, setSelectedMonthYear] = useState('');
  const [selectedTeamId, setSelectedTeamId] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    if (isOpen) {
      const d = currentDate instanceof Date ? currentDate : new Date();
      const y = d.getFullYear();
      const m = String(d.getMonth() + 1).padStart(2, '0');
      setSelectedMonthYear(`${y}-${m}`);
      setSelectedTeamId('');
      setError(null);
      setSuccess(false);
    }
  }, [isOpen, currentDate]);

  if (!isOpen) return null;

  const handleExport = async (e) => {
    e.preventDefault();
    if (!selectedMonthYear) {
      setError('Vui lòng chọn tháng/năm cần xuất báo cáo');
      return;
    }

    const [yearStr, monthStr] = selectedMonthYear.split('-');
    const year = parseInt(yearStr, 10);
    const month = parseInt(monthStr, 10);

    if (!year || !month) {
      setError('Định dạng tháng/năm không hợp lệ');
      return;
    }

    setLoading(true);
    setError(null);
    setSuccess(false);

    try {
      await guardScheduleApi.exportTimesheet({
        year,
        month,
        teamId: selectedTeamId || undefined
      });
      setSuccess(true);
      setTimeout(() => {
        onClose();
      }, 1500);
    } catch (err) {
      console.error('Lỗi xuất báo cáo Excel:', err);
      setError(err.message || 'Không thể tạo file báo cáo Excel. Vui lòng thử lại sau.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[1100] flex items-center justify-center p-4 sm:p-6 overflow-y-auto bg-black/60 backdrop-blur-xs animate-in fade-in duration-200"
      onClick={(e) => {
        if (e.target === e.currentTarget && !loading) onClose();
      }}
    >
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl shadow-2xl w-full max-w-lg max-h-[calc(100dvh-2rem)] my-auto flex flex-col overflow-hidden transform transition-all duration-200 animate-in zoom-in-95">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/70 dark:bg-slate-950/50 shrink-0">
          <div className="flex items-center gap-3.5">
            <div className="w-11 h-11 rounded-xl bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 flex items-center justify-center border border-emerald-500/20 shadow-xs shrink-0">
              <FileSpreadsheet size={24} />
            </div>
            <div className="flex flex-col gap-0.5">
              <h2 className="text-base sm:text-lg font-bold text-slate-900 dark:text-white leading-tight">
                Xuất Bảng Chấm Công
              </h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Tổng hợp công & lương (.xlsx)
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={loading}
            className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition"
            aria-label="Đóng"
          >
            <X size={18} />
          </button>
        </div>

        {/* Form Body with explicit flex column & generous gap */}
        <form onSubmit={handleExport} className="p-6 flex flex-col gap-5 flex-1 min-h-0 overflow-y-auto">
          {error && (
            <div className="p-3.5 bg-rose-50 dark:bg-rose-950/50 border border-rose-200 dark:border-rose-900/60 rounded-xl flex items-start gap-3 text-xs text-rose-700 dark:text-rose-300 leading-relaxed shadow-xs">
              <AlertCircle size={18} className="shrink-0 mt-0.5 text-rose-600 dark:text-rose-400" />
              <span>{error}</span>
            </div>
          )}

          {success && (
            <div className="p-3.5 bg-emerald-50 dark:bg-emerald-950/50 border border-emerald-200 dark:border-emerald-900/60 rounded-xl flex items-center gap-3 text-xs text-emerald-700 dark:text-emerald-300 leading-relaxed shadow-xs">
              <CheckCircle2 size={18} className="shrink-0 text-emerald-600 dark:text-emerald-400" />
              <span>Tải file Excel thành công! Đang đóng cửa sổ...</span>
            </div>
          )}

          {/* Month / Year Picker */}
          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
              <Calendar size={15} className="text-emerald-600 dark:text-emerald-400" />
              <span>Tháng / Năm chấm công <strong className="text-rose-500">*</strong></span>
            </label>
            <input
              type="month"
              value={selectedMonthYear}
              onChange={(e) => setSelectedMonthYear(e.target.value)}
              className="w-full h-11 px-4 py-2.5 rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-white text-sm font-semibold focus:ring-2 focus:ring-emerald-500 focus:border-transparent outline-none transition shadow-xs"
              required
            />
          </div>

          {/* Team Selector */}
          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
              <Users size={15} className="text-emerald-600 dark:text-emerald-400" />
              <span>Đội bảo vệ</span>
            </label>
            <Select
              value={selectedTeamId}
              onChange={(e) => setSelectedTeamId(e.target.value)}
              options={[
                { value: '', label: 'Tất cả các đội bảo vệ' },
                ...teams.map((team) => ({
                  value: team.id,
                  label: `${team.teamName} ${team.description ? `(${team.description})` : ''}`
                })),
              ]}
            />
          </div>

          {/* Info Card with dark-mode safe styling and flex gap */}
          <div className="p-4 rounded-xl bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700/80 flex flex-col gap-3 shadow-xs">
            <div className="font-bold text-xs text-slate-800 dark:text-slate-200 flex items-center gap-2">
              <FileSpreadsheet size={16} className="text-emerald-600 dark:text-emerald-400 shrink-0" />
              <span>Nội dung file</span>
            </div>
            <div className="flex flex-col gap-2 text-xs text-slate-600 dark:text-slate-300">
              <div className="flex items-start gap-2.5 p-2.5 rounded-lg bg-white dark:bg-slate-900/80 border border-slate-200/80 dark:border-slate-700/80">
                <span className="shrink-0 px-2 py-0.5 rounded text-[10px] font-extrabold uppercase tracking-wide bg-emerald-100 dark:bg-emerald-950 text-emerald-700 dark:text-emerald-300 border border-emerald-200 dark:border-emerald-800">
                  Sheet 1
                </span>
                <span className="leading-relaxed">
                  Bảng công, phụ cấp đêm, OT, trực thay và tính lương.
                </span>
              </div>
              <div className="flex items-start gap-2.5 p-2.5 rounded-lg bg-white dark:bg-slate-900/80 border border-slate-200/80 dark:border-slate-700/80">
                <span className="shrink-0 px-2 py-0.5 rounded text-[10px] font-extrabold uppercase tracking-wide bg-blue-100 dark:bg-blue-950 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800">
                  Sheet 2
                </span>
                <span className="leading-relaxed">
                  Chi tiết từng ca trực và giờ điểm danh vào/ra.
                </span>
              </div>
            </div>
          </div>

          {/* Footer Actions */}
          <div className="pt-4 mt-1 border-t border-slate-100 dark:border-slate-800 flex items-center justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              disabled={loading}
              className="px-4 py-2.5 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition"
            >
              Hủy
            </button>
            <button
              type="submit"
              disabled={loading || !selectedMonthYear}
              className="px-5 py-2.5 text-xs font-bold text-white bg-emerald-600 hover:bg-emerald-700 active:scale-95 disabled:opacity-50 disabled:pointer-events-none rounded-xl shadow-xs transition flex items-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 size={15} className="animate-spin" />
                  <span>Đang xuất...</span>
                </>
              ) : (
                <>
                  <Download size={15} />
                  <span>Tải file Excel</span>
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
