import { useCallback, useEffect, useState } from "react";
import { History, RotateCcw } from "lucide-react";
import "../../styles/AccessHistoryPage.css";
import PageHeader from "../../components/ui/PageHeader";
import { Button, EmptyState, ErrorState, Input, LoadingState, Pagination, Table } from "../../components/ui";
import AccessResultBadge from "../../components/accessHistory/AccessResultBadge";
import { useAuth } from "../../context/AuthContext";
import { getMyAccessHistory } from "../../services/accessHistoryService";
import { dateRangeError, defaultDateRange, toRangeParams } from "../../utils/accessHistoryHelpers";
import { formatDateTime } from "../../utils/formatDateTime";

const PAGE_SIZE = 20;

/**
 * Lịch sử ra vào của chính người dùng (NORMAL_USER) — GET /api/access-history/my.
 * Gồm cả lượt được vào và bị từ chối. Mặc định 7 ngày gần nhất.
 * Không tải danh sách khu vực: GET /api/areas chỉ dành cho ADMIN/FM (NORMAL_USER nhận 403).
 */
export default function AccessHistoryPage() {
	const { user } = useAuth();
	const [fromDate, setFromDate] = useState(() => defaultDateRange().from);
	const [toDate, setToDate] = useState(() => defaultDateRange().to);
	const [logs, setLogs] = useState([]);
	const [page, setPage] = useState(0);
	const [totalPages, setTotalPages] = useState(0);
	const [totalElements, setTotalElements] = useState(0);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState(null);

	const rangeError = dateRangeError(fromDate, toDate);

	const loadLogs = useCallback(async () => {
		if (rangeError) return;
		setLoading(true);
		setError(null);
		try {
			const res = await getMyAccessHistory({ ...toRangeParams(fromDate, toDate), page, size: PAGE_SIZE });
			setLogs(res?.content || []);
			setTotalPages(res?.totalPages || 0);
			setTotalElements(res?.totalElements || 0);
		} catch (err) {
			setError(err?.message || "Không tải được lịch sử ra vào");
			setLogs([]);
		} finally {
			setLoading(false);
		}
	}, [fromDate, toDate, page, rangeError]);

	useEffect(() => {
		loadLogs();
	}, [loadLogs]);

	const changeFrom = (e) => {
		setFromDate(e.target.value);
		setPage(0);
	};
	const changeTo = (e) => {
		setToDate(e.target.value);
		setPage(0);
	};
	const resetRange = () => {
		const range = defaultDateRange();
		setFromDate(range.from);
		setToDate(range.to);
		setPage(0);
	};

	const columns = [
		{ key: "loggedAt", title: "Thời gian", width: "160px", render: (v) => <span className="ahp-time">{formatDateTime(v)}</span> },
		{ key: "areaName", title: "Khu vực", render: (v) => v || "—" },
		{ key: "cameraCode", title: "Camera", isCode: true, width: "140px" },
		{ key: "accessResult", title: "Kết quả", width: "140px", render: (v) => <AccessResultBadge result={v} /> },
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
					description="Chưa có lượt camera nhận diện bạn trong khoảng ngày đã chọn."
				/>
			);
		}
		return (
			<>
				<Table columns={columns} data={logs} mobileCard />
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
				title="Lịch sử truy cập"
				description={
					user?.fullName
						? `Các lần camera nhận diện ${user.fullName} tại các khu vực giám sát, gồm cả lượt bị từ chối.`
						: "Các lần camera nhận diện bạn tại các khu vực giám sát, gồm cả lượt bị từ chối."
				}
			/>
			<div className="ahp-card">
				<div className="ahp-toolbar">
					<Input label="Từ ngày" type="date" value={fromDate} onChange={changeFrom} />
					<Input label="Đến ngày" type="date" value={toDate} onChange={changeTo} error={rangeError || undefined} />
					<div className="ahp-toolbar__actions">
						<Button variant="secondary" icon={RotateCcw} onClick={resetRange}>
							7 ngày gần nhất
						</Button>
					</div>
				</div>
				<div className="ahp-body">{renderBody()}</div>
			</div>
		</div>
	);
}
