import { useCallback, useEffect, useState } from "react";
import { History, RotateCcw, Search } from "lucide-react";
import "../../styles/AccessHistoryPage.css";
import PageHeader from "../../components/ui/PageHeader";
import { Button, EmptyState, ErrorState, Input, LoadingState, Pagination, Select, Table } from "../../components/ui";
import AccessResultBadge from "../../components/accessHistory/AccessResultBadge";
import { searchAccessHistory } from "../../services/accessHistoryService";
import { getAreas } from "../../services/areaService";
import {
	ACCESS_RESULT_OPTIONS,
	dateRangeError,
	defaultDateRange,
	toRangeParams,
} from "../../utils/accessHistoryHelpers";
import { formatDateTime } from "../../utils/formatDateTime";

const PAGE_SIZE = 20;

/**
 * Lịch sử ra vào toàn hệ thống (FACILITY_MANAGER, ADMIN) — GET /api/access-history.
 * Lọc theo khu vực, mã người dùng, kết quả, khoảng ngày. GUARD xem theo camera ở tab Nhật ký của camera.
 */
export default function AccessHistoryAdminPage() {
	const [areaOptions, setAreaOptions] = useState([{ value: "", label: "Tất cả khu vực" }]);
	const [areaId, setAreaId] = useState("");
	const [userCodeInput, setUserCodeInput] = useState("");
	const [userCode, setUserCode] = useState("");
	const [accessResult, setAccessResult] = useState("");
	const [fromDate, setFromDate] = useState(() => defaultDateRange().from);
	const [toDate, setToDate] = useState(() => defaultDateRange().to);

	const [logs, setLogs] = useState([]);
	const [page, setPage] = useState(0);
	const [totalPages, setTotalPages] = useState(0);
	const [totalElements, setTotalElements] = useState(0);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState(null);

	const rangeError = dateRangeError(fromDate, toDate);

	useEffect(() => {
		const fetchAreas = async () => {
			try {
				const res = await getAreas({ size: 100 });
				const areas = res?.content || [];
				setAreaOptions([
					{ value: "", label: "Tất cả khu vực" },
					...areas.map((a) => ({ value: a.id, label: a.name })),
				]);
			} catch (err) {
				console.error("Lỗi tải danh sách khu vực:", err);
			}
		};
		fetchAreas();
	}, []);

	// Gõ mã người dùng: chờ 400ms rồi mới lọc
	useEffect(() => {
		const handler = setTimeout(() => {
			setUserCode(userCodeInput.trim());
			setPage(0);
		}, 400);
		return () => clearTimeout(handler);
	}, [userCodeInput]);

	const loadLogs = useCallback(async () => {
		if (rangeError) return;
		setLoading(true);
		setError(null);
		try {
			const res = await searchAccessHistory({
				areaId,
				userCode,
				accessResult,
				...toRangeParams(fromDate, toDate),
				page,
				size: PAGE_SIZE,
			});
			setLogs(res?.content || []);
			setTotalPages(res?.totalPages || 0);
			setTotalElements(res?.totalElements || 0);
		} catch (err) {
			setError(err?.message || "Không tải được lịch sử ra vào");
			setLogs([]);
		} finally {
			setLoading(false);
		}
	}, [areaId, userCode, accessResult, fromDate, toDate, page, rangeError]);

	useEffect(() => {
		loadLogs();
	}, [loadLogs]);

	const onFilter = (setter) => (e) => {
		setter(e.target.value);
		setPage(0);
	};

	const resetFilters = () => {
		const range = defaultDateRange();
		setAreaId("");
		setUserCodeInput("");
		setUserCode("");
		setAccessResult("");
		setFromDate(range.from);
		setToDate(range.to);
		setPage(0);
	};

	const columns = [
		{ key: "loggedAt", title: "Thời gian", width: "150px", render: (v) => <span className="ahp-time">{formatDateTime(v)}</span> },
		{
			key: "userCode",
			title: "Người",
			render: (v, record) => (
				<span className="ahp-person">
					<span className="ahp-person__code">{v || "—"}</span>
					{record.fullName && <span className="ahp-person__name">{record.fullName}</span>}
				</span>
			),
		},
		{ key: "areaName", title: "Khu vực", render: (v) => v || "—" },
		{ key: "cameraCode", title: "Camera", isCode: true, width: "130px" },
		{ key: "accessResult", title: "Kết quả", width: "130px", render: (v) => <AccessResultBadge result={v} /> },
		{ key: "reasonDetails", title: "Lý do", render: (v) => <span className="ahp-muted">{v || "—"}</span> },
	];

	const renderBody = () => {
		if (rangeError) {
			return <EmptyState icon={History} title="Khoảng ngày không hợp lệ" description={rangeError} />;
		}
		if (loading) return <LoadingState text="Đang tải lịch sử ra vào..." />;
		if (error) return <ErrorState message={error} onRetry={loadLogs} />;
		if (logs.length === 0) {
			return (
				<EmptyState
					icon={History}
					title="Không có lượt ra vào nào"
					description="Không có bản ghi khớp bộ lọc trong khoảng ngày đã chọn."
				/>
			);
		}
		return (
			<>
				<Table columns={columns} data={logs} />
				<Pagination
					currentPage={page}
					totalPages={totalPages}
					totalElements={totalElements}
					pageSize={PAGE_SIZE}
					onPageChange={setPage}
					itemLabel="lượt"
				/>
			</>
		);
	};

	return (
		<div className="ahp-container">
			<PageHeader
				title="Lịch sử ra vào"
				description="Các lượt camera nhận diện tại mọi khu vực, gồm cả lượt bị từ chối."
			/>
			<div className="ahp-card">
				<div className="ahp-toolbar">
					<Select label="Khu vực" value={areaId} onChange={onFilter(setAreaId)} options={areaOptions} />
					<Input
						label="Mã người dùng"
						value={userCodeInput}
						onChange={(e) => setUserCodeInput(e.target.value)}
						placeholder="VD: SV-001"
						icon={Search}
					/>
					<Select label="Kết quả" value={accessResult} onChange={onFilter(setAccessResult)} options={ACCESS_RESULT_OPTIONS} />
					<Input label="Từ ngày" type="date" value={fromDate} onChange={onFilter(setFromDate)} />
					<Input label="Đến ngày" type="date" value={toDate} onChange={onFilter(setToDate)} error={rangeError || undefined} />
					<div className="ahp-toolbar__actions">
						<Button variant="secondary" icon={RotateCcw} onClick={resetFilters}>
							Đặt lại
						</Button>
					</div>
				</div>
				<div className="ahp-body">{renderBody()}</div>
			</div>
		</div>
	);
}
