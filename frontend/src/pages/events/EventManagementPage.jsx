import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { CalendarDays, CalendarPlus, CircleStop, Eye, Pencil, RotateCcw, Search, X, XCircle } from "lucide-react";
import { useAuth } from "../../context/AuthContext";
import { ROLES } from "../../constants/roles";
import { getAreas } from "../../services/areaService";
import { getAllEventSchedules } from "../../services/eventScheduleService";
import { formatDisplayDateTime, getScheduleStatusView, SCHEDULE_STATUS_ORDER } from "../../utils/areaHelpers";
import {
	Badge,
	Button,
	EmptyState,
	ErrorState,
	LoadingState,
	Modal,
	PageHeader,
	Pagination,
	Select,
} from "../../components/ui";
import EventScheduleFormModal from "../../components/event/EventScheduleFormModal";
import EventStopModal from "../../components/event/EventStopModal";
import "../../styles/EventManagementPage.css";

const PAGE_SIZE = 10;

const STATUS_FILTER_OPTIONS = [
	{ value: "", label: "Tất cả trạng thái" },
	...SCHEDULE_STATUS_ORDER.map((s) => ({ value: s, label: getScheduleStatusView(s).label })),
];

/**
 * Quản lý sự kiện (Q1): một màn, một bảng, mọi trạng thái; lọc trạng thái / tìm kiếm / khu vực / khoảng ngày.
 * FACILITY_MANAGER: tạo, sửa + huỷ (SCHEDULED), dừng (STARTED). ADMIN: chỉ xem (F8).
 * Lịch sử thay đổi xem ở Nhật ký kiểm toán (Q9).
 */
export default function EventManagementPage() {
	const { user } = useAuth();
	const canManage = user?.role === ROLES.FACILITY_MANAGER;
	const [searchParams] = useSearchParams();

	const [searchInput, setSearchInput] = useState("");
	const [keyword, setKeyword] = useState("");
	const [status, setStatus] = useState(searchParams.get("status") || "");
	const [areaId, setAreaId] = useState(searchParams.get("areaId") || "");
	const [from, setFrom] = useState("");
	const [to, setTo] = useState("");
	const [page, setPage] = useState(0);

	const [data, setData] = useState({ content: [], totalPages: 0, totalElements: 0 });
	const [loading, setLoading] = useState(false);
	const [loadError, setLoadError] = useState(null);
	const [areas, setAreas] = useState([]);

	const [formState, setFormState] = useState({ mode: null, schedule: null });
	const [stopTarget, setStopTarget] = useState(null);
	const [detail, setDetail] = useState(null);

	// Tìm kiếm: debounce 400ms như các màn khác
	useEffect(() => {
		const timer = setTimeout(() => {
			setKeyword(searchInput.trim());
			setPage(0);
		}, 400);
		return () => clearTimeout(timer);
	}, [searchInput]);

	// Khu Bảo mật đang hoạt động (Q2): dùng cho bộ lọc và form
	useEffect(() => {
		let isMounted = true;
		getAreas({ size: 200, isActive: true })
			.then((res) => {
				if (!isMounted) return;
				const list = (res?.content || res || []).filter((a) => a.areaLevel === "CONFIDENTIAL" && a.isActive !== false);
				setAreas(list.sort((a, b) => a.name.localeCompare(b.name, "vi")));
			})
			.catch((err) => console.error("Lỗi tải danh sách khu vực:", err));
		return () => {
			isMounted = false;
		};
	}, []);

	const rangeError = from && to && from > to ? "Từ ngày phải trước hoặc bằng Đến ngày" : null;

	const loadSchedules = useCallback(async () => {
		if (rangeError) return;
		setLoading(true);
		setLoadError(null);
		try {
			const res = await getAllEventSchedules({ status, areaId, from, to, q: keyword, page, size: PAGE_SIZE });
			setData({
				content: res?.content || [],
				totalPages: res?.totalPages || 0,
				totalElements: res?.totalElements || 0,
			});
		} catch (err) {
			console.error("Lỗi tải danh sách sự kiện:", err);
			setLoadError(err?.message || "Không thể tải danh sách sự kiện.");
		} finally {
			setLoading(false);
		}
	}, [status, areaId, from, to, keyword, page, rangeError]);

	useEffect(() => {
		loadSchedules();
	}, [loadSchedules]);

	const onFilter = (setter) => (e) => {
		setter(e.target.value);
		setPage(0);
	};

	const hasActiveFilters = Boolean(searchInput || status || areaId || from || to);
	const resetFilters = () => {
		setSearchInput("");
		setKeyword("");
		setStatus("");
		setAreaId("");
		setFrom("");
		setTo("");
		setPage(0);
	};

	const areaFilterOptions = useMemo(() => {
		const opts = [{ value: "", label: "Tất cả khu vực" }, ...areas.map((a) => ({ value: a.id, label: a.name }))];
		// Lọc theo khu từ liên kết (badge ở màn khu vực) nhưng khu không còn trong danh sách -> vẫn chọn được
		if (areaId && !areas.some((a) => a.id === areaId)) {
			const row = data.content.find((s) => s.areaId === areaId);
			opts.push({ value: areaId, label: row?.areaName || "Khu vực đã chọn" });
		}
		return opts;
	}, [areas, areaId, data.content]);

	const closeForm = () => setFormState({ mode: null, schedule: null });
	const handleSaved = (opts) => {
		if (!opts?.keepOpen) closeForm();
		loadSchedules();
	};
	const handleStopped = (opts) => {
		if (!opts?.keepOpen) setStopTarget(null);
		loadSchedules();
	};

	const renderStatus = (s) => {
		const view = getScheduleStatusView(s.status);
		return (
			<span title={s.status === "FAILED" && s.failReason ? s.failReason : undefined}>
				<Badge variant={view.variant}>{view.label}</Badge>
			</span>
		);
	};

	const renderActions = (s) => (
		<div className="evm-actions">
			<button
				type="button"
				className="evm-icon-btn"
				onClick={() => setDetail(s)}
				title="Xem chi tiết"
				aria-label={`Xem chi tiết sự kiện ${s.title}`}
			>
				<Eye size={16} />
			</button>
			{canManage && s.status === "SCHEDULED" && (
				<>
					<button
						type="button"
						className="evm-icon-btn"
						onClick={() => setFormState({ mode: "edit", schedule: s })}
						title="Sửa sự kiện"
						aria-label={`Sửa sự kiện ${s.title}`}
					>
						<Pencil size={16} />
					</button>
					<button
						type="button"
						className="evm-icon-btn evm-icon-btn--danger"
						onClick={() => setFormState({ mode: "cancel", schedule: s })}
						title="Hủy sự kiện"
						aria-label={`Hủy sự kiện ${s.title}`}
					>
						<XCircle size={16} />
					</button>
				</>
			)}
			{canManage && s.status === "STARTED" && (
				<button
					type="button"
					className="evm-stop-btn"
					onClick={() => setStopTarget(s)}
					title="Dừng sự kiện đang diễn ra"
					aria-label={`Dừng sự kiện ${s.title}`}
				>
					<CircleStop size={15} />
					<span>Dừng</span>
				</button>
			)}
		</div>
	);

	const renderBody = () => {
		if (loading) return <LoadingState size="sm" text="Đang tải danh sách sự kiện..." />;
		if (loadError) return <ErrorState size="sm" message={loadError} onRetry={loadSchedules} />;
		if (data.content.length === 0) {
			return (
				<EmptyState
					size="sm"
					icon={CalendarDays}
					title={hasActiveFilters ? "Không có sự kiện phù hợp bộ lọc" : "Chưa có sự kiện nào"}
					description={canManage && !hasActiveFilters ? "Bấm “Tạo sự kiện” để đặt lịch sự kiện đầu tiên." : undefined}
				/>
			);
		}
		return (
			<>
				<div className="evm-table-wrap">
					<table className="evm-table">
						<thead>
							<tr>
								<th>Tên sự kiện</th>
								<th>Khu vực</th>
								<th>Bắt đầu – Kết thúc</th>
								<th>Loại</th>
								<th>Trạng thái</th>
								<th>Người tạo</th>
								<th className="evm-col-actions">Thao tác</th>
							</tr>
						</thead>
						<tbody>
							{data.content.map((s) => (
								<tr key={s.id}>
									<td>
										<div className="evm-title" title={s.note ? `${s.title}\nGhi chú: ${s.note}` : s.title}>
											{s.title}
										</div>
									</td>
									<td className="evm-area">{s.areaName || "—"}</td>
									<td className="evm-time">
										<div>{formatDisplayDateTime(s.startAt)}</div>
										<div className="evm-muted">đến {formatDisplayDateTime(s.endAt)}</div>
									</td>
									<td className="evm-type">{s.reasonLabel || "—"}</td>
									<td>{renderStatus(s)}</td>
									<td className="evm-creator">{s.createdByName || "—"}</td>
									<td className="evm-col-actions">{renderActions(s)}</td>
								</tr>
							))}
						</tbody>
					</table>
				</div>
				<Pagination
					currentPage={page}
					totalPages={data.totalPages}
					totalElements={data.totalElements}
					pageSize={PAGE_SIZE}
					onPageChange={setPage}
					itemLabel="sự kiện"
				/>
			</>
		);
	};

	const detailRows = (s) => {
		const view = getScheduleStatusView(s.status);
		const rows = [
			["Tên sự kiện", s.title],
			["Khu vực", s.areaName],
			["Thời gian", `${formatDisplayDateTime(s.startAt)} → ${formatDisplayDateTime(s.endAt)}`],
			["Loại sự kiện", s.reasonLabel || "—"],
			["Trạng thái", view.label],
			["Ghi chú", s.note || "—"],
			["Người tạo", `${s.createdByName || "—"}${s.createdAt ? ` · ${formatDisplayDateTime(s.createdAt)}` : ""}`],
		];
		if (s.updatedByName && s.updatedAt) rows.push(["Sửa lần cuối", `${s.updatedByName} · ${formatDisplayDateTime(s.updatedAt)}`]);
		if (s.status === "CANCELLED") {
			rows.push([
				"Hủy bởi",
				`${s.cancelledByName || "—"}${s.cancelledAt ? ` · ${formatDisplayDateTime(s.cancelledAt)}` : ""}`,
			]);
			rows.push(["Lý do hủy", [s.cancelReasonLabel, s.cancelNote].filter(Boolean).join(" – ") || "—"]);
		}
		if (s.status === "FAILED") {
			rows.push(["Lý do không kích hoạt", `${s.failReason || "—"}${s.failedAt ? ` · ${formatDisplayDateTime(s.failedAt)}` : ""}`]);
		}
		return rows;
	};

	return (
		<div className="evm-container">
			<PageHeader
				title="Quản lý sự kiện"
				description={
					canManage
						? "Đặt lịch mở khu Bảo mật cho sự kiện, theo dõi và dừng sự kiện đang diễn ra. Lịch sử thay đổi xem ở Nhật ký kiểm toán."
						: "Xem lịch sự kiện của các khu Bảo mật. Lịch sử thay đổi xem ở Nhật ký kiểm toán."
				}
				actions={
					canManage ? (
						<Button variant="primary" icon={CalendarPlus} onClick={() => setFormState({ mode: "create", schedule: null })}>
							Tạo sự kiện
						</Button>
					) : null
				}
			/>

			<div className="evm-card">
				<div className="evm-toolbar">
					<div className="evm-search">
						<Search size={16} className="evm-search__icon" />
						<input
							type="text"
							className="evm-search__input"
							value={searchInput}
							onChange={(e) => setSearchInput(e.target.value)}
							placeholder="Tìm theo tên sự kiện hoặc tên khu..."
							aria-label="Tìm theo tên sự kiện hoặc tên khu"
						/>
					</div>
					<Select
						className="ui-select-group--filter"
						aria-label="Lọc theo trạng thái"
						value={status}
						onChange={onFilter(setStatus)}
						options={STATUS_FILTER_OPTIONS}
					/>
					<Select
						className="ui-select-group--filter"
						aria-label="Lọc theo khu vực"
						value={areaId}
						onChange={onFilter(setAreaId)}
						options={areaFilterOptions}
					/>
					<div className="evm-date-range">
						<label className="evm-date-label" htmlFor="evm-from">
							Từ
						</label>
						<input id="evm-from" type="date" className="evm-date-input" value={from} onChange={onFilter(setFrom)} />
						<label className="evm-date-label" htmlFor="evm-to">
							Đến
						</label>
						<input id="evm-to" type="date" className="evm-date-input" value={to} onChange={onFilter(setTo)} />
					</div>
					<div className="evm-toolbar__actions">
						<button
							type="button"
							className="evm-clear-btn"
							onClick={resetFilters}
							disabled={!hasActiveFilters}
							title={hasActiveFilters ? "Xóa tất cả bộ lọc đang chọn" : "Chưa có bộ lọc nào"}
						>
							<X size={16} />
							<span>Xóa bộ lọc</span>
						</button>
						<button
							type="button"
							className="evm-refresh-btn"
							onClick={loadSchedules}
							disabled={loading}
							title="Làm mới"
							aria-label="Làm mới"
						>
							<RotateCcw size={16} className={loading ? "animate-spin" : ""} />
						</button>
					</div>
				</div>
				{rangeError && (
					<div className="evm-range-error" role="alert">
						{rangeError}
					</div>
				)}
				<div className="evm-body">{renderBody()}</div>
			</div>

			<EventScheduleFormModal
				mode={formState.mode}
				schedule={formState.schedule}
				areas={areas}
				onClose={closeForm}
				onSaved={handleSaved}
			/>

			<EventStopModal schedule={stopTarget} onClose={() => setStopTarget(null)} onStopped={handleStopped} />

			<Modal
				isOpen={Boolean(detail)}
				onClose={() => setDetail(null)}
				title="Chi tiết sự kiện"
				icon={CalendarDays}
				size="md"
				footer={
					<Button variant="secondary" onClick={() => setDetail(null)}>
						Đóng
					</Button>
				}
			>
				{detail && (
					<dl className="evm-detail">
						{detailRows(detail).map(([k, v]) => (
							<div key={k} className="evm-detail__row">
								<dt>{k}</dt>
								<dd>{v}</dd>
							</div>
						))}
					</dl>
				)}
			</Modal>
		</div>
	);
}
