import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Building2,
  Users,
  Calendar,
  RefreshCw,
  AlertCircle,
  CheckCircle2,
  Shield,
  ArrowRightLeft,
  Search,
} from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';
import Select from '../ui/Select';
import { LoadingState } from '../ui';

export default function BuildingRotationReportTab({ teams = [], buildings = [] }) {
  const defaultFrom = useMemo(() => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }, []);

  const defaultTo = useMemo(() => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }, []);

  const [fromDate, setFromDate] = useState(defaultFrom);
  const [toDate, setToDate] = useState(defaultTo);
  const [selectedTeamId, setSelectedTeamId] = useState('ALL');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [reportData, setReportData] = useState(null);

  const fetchReport = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await guardScheduleApi.getBuildingRotationReport({
        from: fromDate,
        to: toDate,
        teamId: selectedTeamId
      });
      const data = res?.data || res;
      setReportData(data);
    } catch (err) {
      console.error('Lỗi khi tải báo cáo luân chuyển tòa nhà:', err);
      setError(err.message || 'Không thể tải báo cáo luân chuyển tòa nhà');
      setReportData(null);
    } finally {
      setLoading(false);
    }
  }, [fromDate, toDate, selectedTeamId]);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const reportBuildings = useMemo(() => {
    if (reportData?.buildings && reportData.buildings.length > 0) {
      return reportData.buildings;
    }
    return (buildings || []).map((b) => ({
      buildingId: b.id,
      buildingName: b.name
    }));
  }, [reportData, buildings]);

  const hasUnassignedBuildingShifts = useMemo(() => {
    if (!reportData?.items) return false;
    return reportData.items.some(
      (item) => (item.unknownBuildingShifts ?? item.shiftsByBuildingName?.['Chưa xác định'] ?? 0) > 0
    );
  }, [reportData]);

  const filteredItems = useMemo(() => {
    if (!reportData?.items) return [];
    if (!searchKeyword.trim()) return reportData.items;
    const kw = searchKeyword.toLowerCase().trim();
    return reportData.items.filter(
      (item) =>
        (item.guardName && item.guardName.toLowerCase().includes(kw)) ||
        (item.guardCode && item.guardCode.toLowerCase().includes(kw)) ||
        (item.teamName && item.teamName.toLowerCase().includes(kw))
    );
  }, [reportData, searchKeyword]);

  return (
    <div className="space-y-4">
      {/* 1. Header & Filter Toolbar */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-4 shadow-xs space-y-3.5">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div className="p-2.5 rounded-xl bg-blue-50 dark:bg-blue-950/50 text-blue-600 dark:text-blue-400">
              <Building2 size={20} />
            </div>
            <div>
              <h2 className="text-base font-bold text-slate-900 dark:text-white">
                Báo Cáo Luân Chuyển Bảo Vệ Giữa Các Tòa Nhà
              </h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Thống kê tần suất phân ca theo tòa và tỷ lệ bảo vệ trực ngoài tòa nhà gốc của đội (BR-GB-08)
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={fetchReport}
            disabled={loading}
            className="schedule-btn-secondary self-start sm:self-auto flex items-center gap-1.5"
            title="Làm mới báo cáo"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            <span>Tải Lại Báo Cáo</span>
          </button>
        </div>

        {/* Filter Bar */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 pt-2 border-t border-slate-100 dark:border-slate-800">
          <div>
            <label className="block text-[11px] font-bold text-slate-600 dark:text-slate-300 mb-1 flex items-center gap-1">
              <Calendar size={12} className="text-blue-500" />
              <span>Từ Ngày</span>
            </label>
            <input
              type="date"
              value={fromDate}
              onChange={(e) => setFromDate(e.target.value)}
              className="w-full px-2.5 py-1.5 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>

          <div>
            <label className="block text-[11px] font-bold text-slate-600 dark:text-slate-300 mb-1 flex items-center gap-1">
              <Calendar size={12} className="text-blue-500" />
              <span>Đến Ngày</span>
            </label>
            <input
              type="date"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
              className="w-full px-2.5 py-1.5 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>

          <div>
            <label className="block text-[11px] font-bold text-slate-600 dark:text-slate-300 mb-1 flex items-center gap-1">
              <Users size={12} className="text-blue-500" />
              <span>Lọc Theo Đội</span>
            </label>
            <Select
              value={selectedTeamId}
              onChange={(e) => setSelectedTeamId(e.target.value)}
              options={[
                { value: 'ALL', label: '-- Tất cả các đội bảo vệ --' },
                ...teams.map((t) => ({ value: t.id, label: t.teamName })),
              ]}
            />
          </div>

          <div>
            <label className="block text-[11px] font-bold text-slate-600 dark:text-slate-300 mb-1 flex items-center gap-1">
              <Search size={12} className="text-blue-500" />
              <span>Tìm Kiếm Nhân Sự</span>
            </label>
            <input
              type="text"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="Tìm theo tên, mã bảo vệ..."
              className="w-full px-2.5 py-1.5 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>
        </div>
      </div>

      {/* 2. Metric Overview Cards */}
      {reportData && (
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div className="p-4 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 shadow-xs flex items-center justify-between">
            <div>
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">
                Tổng Số Nhân Viên Bảo Vệ
              </p>
              <h3 className="text-xl font-extrabold text-slate-900 dark:text-white mt-0.5">
                {reportData.totalGuards} <span className="text-xs font-normal text-slate-400">người</span>
              </h3>
            </div>
            <div className="p-2.5 rounded-xl bg-blue-50 dark:bg-blue-950/50 text-blue-600 dark:text-blue-400">
              <Users size={20} />
            </div>
          </div>

          <div className="p-4 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 shadow-xs flex items-center justify-between">
            <div>
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">
                Đã Trực Luân Chuyển Tòa Khác
              </p>
              <h3 className="text-xl font-extrabold text-indigo-600 dark:text-indigo-400 mt-0.5">
                {reportData.guardsWithCrossBuildingShifts} <span className="text-xs font-normal text-slate-400">người</span>
              </h3>
            </div>
            <div className="p-2.5 rounded-xl bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 dark:text-indigo-400">
              <ArrowRightLeft size={20} />
            </div>
          </div>

          <div className="p-4 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 shadow-xs flex items-center justify-between">
            <div>
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">
                Tỷ Lệ Luân Chuyển Nhân Sự
              </p>
              <h3 className="text-xl font-extrabold text-emerald-600 dark:text-emerald-400 mt-0.5">
                {reportData.totalGuards > 0
                  ? Math.round((reportData.guardsWithCrossBuildingShifts / reportData.totalGuards) * 100)
                  : 0}%
              </h3>
            </div>
            <div className="p-2.5 rounded-xl bg-emerald-50 dark:bg-emerald-950/50 text-emerald-600 dark:text-emerald-400">
              <Shield size={20} />
            </div>
          </div>
        </div>
      )}

      {/* 3. Main Data Table */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl overflow-hidden shadow-xs">
        {loading ? (
          <div className="py-16">
            <LoadingState size="md" text="Đang tính toán báo cáo luân chuyển tòa nhà..." />
          </div>
        ) : error ? (
          <div className="py-12 px-4 text-center">
            <div className="inline-flex p-3 rounded-full bg-rose-50 text-rose-600 dark:bg-rose-950/50 dark:text-rose-400 mb-2">
              <AlertCircle size={28} />
            </div>
            <h4 className="text-sm font-bold text-slate-900 dark:text-white">Không thể tải báo cáo</h4>
            <p className="text-xs text-rose-500 mt-1 max-w-md mx-auto">{error}</p>
            <button
              onClick={fetchReport}
              className="mt-3 px-3 py-1.5 bg-blue-600 text-white rounded-lg text-xs font-bold hover:bg-blue-700 transition"
            >
              Thử lại
            </button>
          </div>
        ) : filteredItems.length === 0 ? (
          <div className="py-16 text-center text-slate-400">
            <Building2 size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-xs font-medium">Không tìm thấy dữ liệu ca trực trong khoảng thời gian đã chọn</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs border-collapse">
              <thead>
                <tr className="bg-slate-50 dark:bg-slate-800/80 border-b border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 font-bold uppercase text-[10.5px]">
                  <th className="px-3.5 py-3">Nhân Viên Bảo Vệ</th>
                  <th className="px-3 py-3">Đội Biên Chế</th>
                  <th className="px-3 py-3">Tòa Nhà Gốc</th>

                  {/* Cột theo từng Tòa Nhà */}
                  {reportBuildings.map((b) => (
                    <th key={b.buildingId || b.buildingName} className="px-3 py-3 text-center whitespace-nowrap">
                      {b.buildingName}
                    </th>
                  ))}

                  {/* Cột Chưa xác định nếu có dữ liệu */}
                  {hasUnassignedBuildingShifts && (
                    <th className="px-3 py-3 text-center whitespace-nowrap text-amber-600 dark:text-amber-400">
                      Chưa Xác Định Tòa
                    </th>
                  )}

                  <th className="px-3 py-3 text-center whitespace-nowrap font-extrabold text-indigo-600 dark:text-indigo-400">
                    Ngoài Tòa Đội
                  </th>
                  <th className="px-3 py-3 text-center whitespace-nowrap font-extrabold">
                    Tổng Ca
                  </th>
                  <th className="px-3.5 py-3 text-center whitespace-nowrap">
                    Trạng Thái Luân Chuyển
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800 text-slate-700 dark:text-slate-200">
                {filteredItems.map((item) => {
                  const shiftsMap = item.shiftsByBuildingName || {};
                  const isNeverRotated = Boolean(item.hasNeverRotated || item.outsideTeamBuildingShifts === 0);

                  return (
                    <tr
                      key={item.guardId}
                      className="hover:bg-slate-50/70 dark:hover:bg-slate-800/50 transition-colors"
                    >
                      {/* Tên bảo vệ & mã NV */}
                      <td className="px-3.5 py-2.5">
                        <div className="font-bold text-slate-900 dark:text-white">
                          {item.guardName}
                        </div>
                        <div className="font-mono text-[10px] text-slate-400">
                          {item.guardCode || 'NV-BV'}
                        </div>
                      </td>

                      {/* Đội biên chế */}
                      <td className="px-3 py-2.5 font-medium whitespace-nowrap">
                        {item.teamName || '—'}
                      </td>

                      {/* Tòa nhà gốc */}
                      <td className="px-3 py-2.5 whitespace-nowrap">
                        <span className="inline-flex items-center gap-1 font-semibold text-slate-800 dark:text-slate-200">
                          <Building2 size={12} className="text-blue-500" />
                          {item.homeBuildingName || 'Chưa gán tòa'}
                        </span>
                      </td>

                      {/* Cột từng tòa nhà */}
                      {reportBuildings.map((b) => {
                        const count = shiftsMap[b.buildingName] || 0;
                        const isHome = item.homeBuildingName && item.homeBuildingName.toLowerCase() === b.buildingName.toLowerCase();
                        return (
                          <td
                            key={b.buildingId || b.buildingName}
                            className={`px-3 py-2.5 text-center font-medium ${
                              count > 0
                                ? isHome
                                  ? 'text-slate-800 dark:text-slate-200 font-bold'
                                  : 'text-indigo-600 dark:text-indigo-400 font-bold bg-indigo-50/40 dark:bg-indigo-950/20'
                                : 'text-slate-300 dark:text-slate-600'
                            }`}
                          >
                            {count}
                          </td>
                        );
                      })}

                      {/* Cột chưa xác định nếu có */}
                      {hasUnassignedBuildingShifts && (
                        <td className="px-3 py-2.5 text-center font-medium text-amber-600 dark:text-amber-400">
                          {item.unknownBuildingShifts ?? shiftsMap['Chưa xác định'] ?? 0}
                        </td>
                      )}

                      {/* Ngoài tòa đội */}
                      <td className="px-3 py-2.5 text-center">
                        <span
                          className={`inline-flex px-2 py-0.5 rounded-full font-bold text-xs ${
                            item.outsideTeamBuildingShifts > 0
                              ? 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/60 dark:text-indigo-300'
                              : 'text-slate-400 dark:text-slate-500'
                          }`}
                        >
                          {item.outsideTeamBuildingShifts}
                        </span>
                      </td>

                      {/* Tổng ca */}
                      <td className="px-3 py-2.5 text-center font-bold text-slate-900 dark:text-white">
                        {item.totalShifts}
                      </td>

                      {/* Trạng thái luân chuyển */}
                      <td className="px-3.5 py-2.5 text-center whitespace-nowrap">
                        {isNeverRotated ? (
                          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[10.5px] font-semibold bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700">
                            <CheckCircle2 size={11} className="text-slate-400" />
                            Chưa luân chuyển
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[10.5px] font-bold bg-indigo-50 dark:bg-indigo-950/60 text-indigo-700 dark:text-indigo-300 border border-indigo-200 dark:border-indigo-800">
                            <ArrowRightLeft size={11} className="text-indigo-600" />
                            Đã luân chuyển ({item.outsideTeamBuildingShifts} ca)
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Footer Note */}
        <div className="p-3 bg-slate-50/70 dark:bg-slate-800/40 border-t border-slate-100 dark:border-slate-800 text-[11px] text-slate-500 dark:text-slate-400 flex items-center justify-between">
          <span>
            * Số ca <strong>Ngoài tòa đội</strong> được tính trên các ca không bị hủy trong khoảng ngày, dựa theo tòa nhà phụ trách hiện tại của đội (BR-GB-08).
          </span>
          {reportData && (
            <span>
              Tổng cộng <strong>{filteredItems.length}</strong> nhân sự hiển thị
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

