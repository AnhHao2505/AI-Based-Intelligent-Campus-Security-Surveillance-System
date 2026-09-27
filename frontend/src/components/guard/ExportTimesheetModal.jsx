import React, { useState, useEffect } from 'react';
import { X, FileSpreadsheet, Download, Loader2, Calendar, Users, AlertCircle, CheckCircle2 } from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';

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
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-xs animate-in fade-in duration-150">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl shadow-2xl w-full max-w-md overflow-hidden transform transition-all">
        {/* Header */}
        <div className="flex items-center justify-between p-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/60 dark:bg-slate-950/40">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 flex items-center justify-center border border-emerald-500/20">
              <FileSpreadsheet size={22} />
            </div>
            <div>
              <h2 className="text-base font-bold text-slate-900 dark:text-white">
                Xuất Báo Cáo Chấm Công
              </h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Tổng hợp công & tính lương bảo vệ (.xlsx)
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            disabled={loading}
            className="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition"
          >
            <X size={18} />
          </button>
        </div>

        {/* Form Body */}
        <form onSubmit={handleExport} className="p-5 space-y-4">
          {error && (
            <div className="p-3 bg-rose-50 dark:bg-rose-950/50 border border-rose-200 dark:border-rose-900/60 rounded-xl flex items-start gap-2.5 text-xs text-rose-700 dark:text-rose-300">
              <AlertCircle size={16} className="shrink-0 mt-0.5 text-rose-600" />
              <span>{error}</span>
            </div>
          )}

          {success && (
            <div className="p-3 bg-emerald-50 dark:bg-emerald-950/50 border border-emerald-200 dark:border-emerald-900/60 rounded-xl flex items-center gap-2 text-xs text-emerald-700 dark:text-emerald-300">
              <CheckCircle2 size={16} className="shrink-0 text-emerald-600" />
              <span>Tải file Excel thành công! Đang đóng cửa sổ...</span>
            </div>
          )}

          {/* Month / Year Picker */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300 mb-1.5">
              <span className="flex items-center gap-1.5">
                <Calendar size={14} className="text-slate-500" />
                <span>Tháng / Năm chấm công <strong className="text-rose-500">*</strong></span>
              </span>
            </label>
            <input
              type="month"
              value={selectedMonthYear}
              onChange={(e) => setSelectedMonthYear(e.target.value)}
              className="w-full px-3.5 py-2.5 rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-white text-sm font-medium focus:ring-2 focus:ring-emerald-500 focus:border-transparent outline-none transition"
              required
            />
          </div>

          {/* Team Selector */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300 mb-1.5">
              <span className="flex items-center gap-1.5">
                <Users size={14} className="text-slate-500" />
                <span>Đội bảo vệ</span>
              </span>
            </label>
            <select
              value={selectedTeamId}
              onChange={(e) => setSelectedTeamId(e.target.value)}
              className="w-full px-3.5 py-2.5 rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-white text-sm font-medium focus:ring-2 focus:ring-emerald-500 focus:border-transparent outline-none transition"
            >
              <option value="">Tất cả các đội bảo vệ</option>
              {teams.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.teamName} {team.description ? `(${team.description})` : ''}
                </option>
              ))}
            </select>
          </div>

          {/* Info Card */}
          <div className="p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200/80 dark:border-slate-700/60 rounded-xl space-y-1.5 text-[11px] text-slate-600 dark:text-slate-300">
            <div className="font-semibold text-slate-700 dark:text-slate-200 flex items-center gap-1.5">
              <span>File Excel xuất ra bao gồm:</span>
            </div>
            <ul className="list-disc list-inside space-y-0.5 text-slate-500 dark:text-slate-400 pl-1">
              <li><strong>Sheet 1:</strong> Bảng tổng hợp công, phụ cấp ca đêm, OT, trực thay và công thức tính lương tự động.</li>
              <li><strong>Sheet 2:</strong> Nhật ký đối soát chi tiết từng ca trực và giờ check-in/out.</li>
            </ul>
          </div>

          {/* Actions */}
          <div className="pt-2 flex items-center justify-end gap-2.5">
            <button
              type="button"
              onClick={onClose}
              disabled={loading}
              className="px-4 py-2 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition"
            >
              Hủy
            </button>
            <button
              type="submit"
              disabled={loading || !selectedMonthYear}
              className="px-4 py-2 text-xs font-semibold text-white bg-emerald-600 hover:bg-emerald-700 active:scale-95 disabled:opacity-50 disabled:pointer-events-none rounded-xl shadow-xs transition flex items-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 size={14} className="animate-spin" />
                  <span>Đang xuất file...</span>
                </>
              ) : (
                <>
                  <Download size={14} />
                  <span>Tải Báo Cáo Excel</span>
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
