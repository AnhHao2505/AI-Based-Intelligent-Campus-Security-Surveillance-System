import { useState, useEffect, useCallback } from "react";
import {
	X,
	Video,
	Info,
	MapPin,
	Layers,
	Activity,
	HeartPulse,
	Power,
	PowerOff,
	Trash2,
	Focus,
} from "lucide-react";
import { toast } from "sonner";
import {
	fetchCameraDetail,
	updateCamera,
	decommissionCamera,
	reactivateCamera,
	deleteCamera,
	upsertStreamConfig,
	fetchHealthLogs,
	captureSnapshot,
	startStream,
	stopStream,
	testConnection,
	updateRoiGeometry,
} from "../../services/cameraService";
import { formatImageUrl } from "../../utils/roiUtils";
import { formatLocation } from "../../utils/formatLocation";
import { extractCameraAreaInfo } from "../../utils/campusCameraHelpers";
import RoiEditorModal from "./RoiEditorModal";
import CameraGeneralTab from "./CameraGeneralTab";
import CameraStreamTab from "./CameraStreamTab";
import CameraSurveillanceTab from "./CameraSurveillanceTab";
import CameraAccessLogsTab from "./CameraAccessLogsTab";
import CameraHealthLogs from "./CameraHealthLogs";
import { LoadingState, ErrorState, Modal, Button } from "../ui";
import "../../styles/cameraShared.css";
import "../../styles/CameraDetailPage.css";

const VALID_TABS = [
	"general",
	"stream",
	"surveillance",
	"access-logs",
	"health",
];

export default function CameraInspectorDrawer({
	cameraId,
	initialTab = "general",
	availableAreas = [],
	isAdmin = false,
	onClose,
	onCameraMutated,
	onCameraDeleted,
	onFocusAreaOnMap,
	onRequestPickAreaOnMap,
	isPickingAreaOnMap = false,
	pickedAreaOverride = null,
	onConsumePickedAreaOverride,
}) {
	const [activeTab, setActiveTab] = useState(
		VALID_TABS.includes(initialTab) ? initialTab : "general",
	);

	useEffect(() => {
		if (initialTab && VALID_TABS.includes(initialTab)) {
			setActiveTab(initialTab);
		}
	}, [initialTab, cameraId]);

	const [loading, setLoading] = useState(true);
	const [saving, setSaving] = useState(false);
	const [loadError, setLoadError] = useState(null);
	const [camera, setCamera] = useState(null);

	const [logs, setLogs] = useState([]);
	const [logPage, setLogPage] = useState(0);
	const [logTotalPages, setLogTotalPages] = useState(0);
	const [logsLoading, setLogsLoading] = useState(false);
	const [logsError, setLogsError] = useState(null);

	const [testingConnection, setTestingConnection] = useState(false);
	const [testResult, setTestResult] = useState(null);

	const [startingStream, setStartingStream] = useState(false);
	const [stoppingStream, setStoppingStream] = useState(false);
	const [capturingSnapshot, setCapturingSnapshot] = useState(false);
	const [refreshingLive, setRefreshingLive] = useState(false);
	const [liveSnapshot, setLiveSnapshot] = useState(null);
	const [compareMode, setCompareMode] = useState(false);
	const [roiModalOpen, setRoiModalOpen] = useState(false);
	const [activeSnapshotForModal, setActiveSnapshotForModal] = useState(null);
	const [surveillanceViewMode, setSurveillanceViewMode] = useState("snapshot");

	const [confirmDecommissionOpen, setConfirmDecommissionOpen] = useState(false);
	const [confirmDeleteOpen, setConfirmDeleteOpen] = useState(false);

	const [generalForm, setGeneralForm] = useState({ name: "", areaId: "" });
	const [streamForm, setStreamForm] = useState({
		host: "",
		port: "",
		username: "",
		credentialRef: "",
		mainStreamPath: "",
	});

	const loadCameraDetails = useCallback(async () => {
		if (!cameraId) return;
		setLoading(true);
		setLoadError(null);
		try {
			const data = await fetchCameraDetail(cameraId);
			setCamera(data);
			const info = extractCameraAreaInfo(data);
			setGeneralForm({
				name: data.name || "",
				areaId: info.areaId || "",
			});
			if (data.streamConfig) {
				setStreamForm({
					host: data.streamConfig.host || "",
					port:
						data.streamConfig.port !== null &&
						data.streamConfig.port !== undefined
							? String(data.streamConfig.port)
							: "",
					username: data.streamConfig.username || "",
					credentialRef: "",
					mainStreamPath: data.streamConfig.mainStreamPath || "",
				});
			} else {
				setStreamForm({
					host: "",
					port: "",
					username: "",
					credentialRef: "",
					mainStreamPath: "",
				});
			}
		} catch (err) {
			setLoadError({
				status: err?.status,
				message: err?.message || "Lỗi tải thông tin chi tiết camera.",
			});
		} finally {
			setLoading(false);
		}
	}, [cameraId]);

	const loadLogs = useCallback(async () => {
		if (!cameraId) return;
		setLogsLoading(true);
		setLogsError(null);
		try {
			const data = await fetchHealthLogs(cameraId, { page: logPage, size: 5 });
			setLogs(data.content || []);
			setLogTotalPages(data.totalPages || 0);
		} catch (err) {
			setLogsError(err?.message || "Không tải được nhật ký kết nối.");
		} finally {
			setLogsLoading(false);
		}
	}, [cameraId, logPage]);

	useEffect(() => {
		setTestResult(null);
		setCompareMode(false);
		setLiveSnapshot(null);
		setLogPage(0);
		loadCameraDetails();
	}, [loadCameraDetails]);

	useEffect(() => {
		loadLogs();
	}, [loadLogs]);

	// Apply 3D map room pick override to generalForm
	useEffect(() => {
		if (!pickedAreaOverride?.id) return;
		setGeneralForm((prev) => ({ ...prev, areaId: pickedAreaOverride.id }));
		setActiveTab("general");
		toast.success(`Đã chọn khu vực: ${pickedAreaOverride.name}`);
		onConsumePickedAreaOverride?.();
	}, [pickedAreaOverride, onConsumePickedAreaOverride]);

	if (!cameraId) return null;

	const handleTestConnection = async () => {
		setTestingConnection(true);
		setTestResult(null);
		try {
			const payload = {
				host: streamForm.host ? streamForm.host.trim() : "",
				port: streamForm.port ? parseInt(streamForm.port, 10) : 554,
				username: streamForm.username ? streamForm.username.trim() : "",
				mainStreamPath: streamForm.mainStreamPath
					? streamForm.mainStreamPath.trim()
					: "",
			};
			if (streamForm.credentialRef?.trim()) {
				payload.credentialRef = streamForm.credentialRef.trim();
			}
			const res = await testConnection(cameraId, payload);
			setTestResult({
				success: Boolean(res?.success),
				message:
					res?.message ||
					(res?.success
						? "Kết nối RTSP thành công!"
						: "Không thể kết nối đến camera."),
				latencyMs: res?.latencyMs,
			});
		} catch (err) {
			setTestResult({
				success: false,
				message: err?.message || "Lỗi kiểm tra kết nối stream.",
			});
		} finally {
			setTestingConnection(false);
		}
	};

	const handleStartStream = async () => {
		setStartingStream(true);
		try {
			const res = await startStream(cameraId);
			if (res?.success) {
				setCamera((prev) => ({ ...prev, operationalStatus: "ONLINE" }));
				toast.success("Đã bật Stream trực tiếp. Camera chuyển sang ONLINE.");
				loadLogs();
				onCameraMutated?.();
			} else {
				toast.error(res?.message || "Không thể kết nối Stream trực tiếp.");
			}
		} catch (err) {
			toast.error(err?.message || "Không thể kết nối Stream trực tiếp.");
		} finally {
			setStartingStream(false);
		}
	};

	const handleStopStream = async () => {
		setStoppingStream(true);
		try {
			const res = await stopStream(cameraId);
			if (res?.success) {
				setCamera((prev) => ({ ...prev, operationalStatus: "OFFLINE" }));
				toast.success("Đã ngắt Stream trực tiếp. Camera chuyển sang OFFLINE.");
				loadLogs();
				onCameraMutated?.();
			} else {
				toast.error(res?.message || "Không thể ngắt Stream trực tiếp.");
			}
		} catch (err) {
			toast.error(err?.message || "Không thể ngắt Stream trực tiếp.");
		} finally {
			setStoppingStream(false);
		}
	};

	const handleCaptureNewSnapshot = async () => {
		setCapturingSnapshot(true);
		try {
			const res = await captureSnapshot(cameraId);
			if (res?.success) {
				toast.success("Đã trích xuất khung hình mới thành công!");
				setActiveSnapshotForModal({
					data: res.snapshotBase64,
					width: res.width || 1920,
					height: res.height || 1080,
				});
				setRoiModalOpen(true);
			} else {
				toast.error(res?.errorMessage || "Không thể chụp snapshot từ camera.");
			}
		} catch (err) {
			toast.error(err?.message || "Lỗi chụp snapshot từ camera.");
		} finally {
			setCapturingSnapshot(false);
		}
	};

	const handleCaptureFromLiveStream = (snapshotData) => {
		if (!snapshotData?.base64) {
			handleCaptureNewSnapshot();
			return;
		}
		toast.success("Đã chụp khung hình trực tiếp thành công!");
		setActiveSnapshotForModal({
			data: snapshotData.base64,
			width: snapshotData.width || 1920,
			height: snapshotData.height || 1080,
		});
		setRoiModalOpen(true);
	};

	const handleEditCurrentRoi = () => {
		const rawRefUrl =
			camera?.roiGeometry?.reference_snapshot_url ||
			camera?.roiGeometry?.referenceSnapshotUrl;
		const refUrl = formatImageUrl(rawRefUrl);
		if (!refUrl) {
			toast.error("Chưa có ảnh chụp tham chiếu. Vui lòng bấm 'Chụp ảnh mới'.");
			return;
		}
		setActiveSnapshotForModal({
			data: refUrl,
			width:
				camera?.roiGeometry?.reference_snapshot_width ||
				camera?.roiGeometry?.referenceSnapshotWidth ||
				1920,
			height:
				camera?.roiGeometry?.reference_snapshot_height ||
				camera?.roiGeometry?.referenceSnapshotHeight ||
				1080,
		});
		setRoiModalOpen(true);
	};

	const handleCheckDrift = async () => {
		setRefreshingLive(true);
		try {
			const res = await captureSnapshot(cameraId);
			if (res?.success) {
				setLiveSnapshot(res);
				setCompareMode(true);
				toast.success("Đã trích xuất khung hình để kiểm tra sai lệch.");
			} else {
				toast.error(res?.errorMessage || "Không thể lấy khung hình trực tiếp.");
			}
		} catch (err) {
			toast.error(err?.message || "Lỗi kiểm tra sai lệch.");
		} finally {
			setRefreshingLive(false);
		}
	};

	const handleSaveRoi = async (roiGeometry, snapshotMeta) => {
		const updated = await updateRoiGeometry(cameraId, roiGeometry, snapshotMeta);
		const newRoi = updated?.roiGeometry || updated;
		setCamera((prev) => ({
			...prev,
			roiGeometry: { ...prev?.roiGeometry, ...newRoi },
		}));
		setCompareMode(false);
		setLiveSnapshot(null);
		setSurveillanceViewMode("snapshot");
		toast.success("Cấu hình vùng giám sát (ROI) đã được lưu thành công!");
		loadCameraDetails();
		onCameraMutated?.();
	};

	const handleGeneralSubmit = async (e) => {
		e.preventDefault();
		setSaving(true);
		try {
			const updated = await updateCamera(cameraId, {
				name: generalForm.name,
				areaId: generalForm.areaId,
			});
			setCamera(updated);
			toast.success("Cập nhật thông tin camera thành công!");
			onCameraMutated?.(updated);
		} catch (err) {
			toast.error(err?.message || "Cập nhật thông tin thất bại.");
		} finally {
			setSaving(false);
		}
	};

	const handleStreamSubmit = async (e) => {
		e.preventDefault();
		setSaving(true);
		try {
			const payload = {
				host: streamForm.host ? streamForm.host.trim() : "",
				port: streamForm.port ? parseInt(streamForm.port, 10) : 554,
				username: streamForm.username ? streamForm.username.trim() : "",
				mainStreamPath: streamForm.mainStreamPath
					? streamForm.mainStreamPath.trim()
					: "",
			};
			if (streamForm.credentialRef?.trim()) {
				payload.credentialRef = streamForm.credentialRef.trim();
			}
			await upsertStreamConfig(cameraId, payload);
			toast.success("Đã lưu cấu hình stream!");
			loadCameraDetails();
			onCameraMutated?.();
		} catch (err) {
			toast.error(err?.message || "Lưu cấu hình stream thất bại.");
		} finally {
			setSaving(false);
		}
	};

	const handleToggleStatus = async () => {
		if (!camera) return;
		setSaving(true);
		try {
			if (camera.status === "DECOMMISSIONED") {
				await reactivateCamera(camera.id);
				toast.success("Đã kích hoạt lại camera thành công");
			} else {
				await decommissionCamera(camera.id);
				toast.success("Đã dừng hoạt động camera thành công");
			}
			setConfirmDecommissionOpen(false);
			await loadCameraDetails();
			onCameraMutated?.();
		} catch (err) {
			toast.error(err?.message || "Thay đổi trạng thái thất bại.");
		} finally {
			setSaving(false);
		}
	};

	const handleConfirmDelete = async () => {
		if (!camera) return;
		setSaving(true);
		try {
			await deleteCamera(camera.id);
			toast.success(`Đã xóa camera ${camera.cameraCode}`);
			setConfirmDeleteOpen(false);
			onCameraDeleted?.(camera.id);
		} catch (err) {
			toast.error(err?.message || "Xóa camera thất bại.");
		} finally {
			setSaving(false);
		}
	};

	const camAreaInfo = extractCameraAreaInfo(camera);
	const isDecommissioned = camera?.status === "DECOMMISSIONED";

	return (
		<div className="cam-inspector-drawer" role="region" aria-label="Chi tiết Camera">
			<div className="cam-inspector-drawer__header">
				<div className="cam-inspector-drawer__title-group">
					<div className="cam-inspector-drawer__icon">
						<Video size={18} />
					</div>
					<div style={{ minWidth: 0 }}>
						<div className="cam-inspector-drawer__title-row">
							<h3 title={camera?.name || "Chi tiết Camera"}>
								{camera?.name || "Đang tải..."}
							</h3>
							{camera?.cameraCode && (
								<span className="cam-inspector-drawer__code">
									{camera.cameraCode}
								</span>
							)}
						</div>
						{camera && (
							<div className="cam-inspector-drawer__badges">
								<span
									className={`status-badge badge-${(camera.status || "ACTIVE").toLowerCase()}`}
								>
									{isDecommissioned ? "INACTIVE" : "ACTIVE"}
								</span>
								<span
									className={`status-badge op-badge-${(camera.operationalStatus || "OFFLINE").toLowerCase()}`}
								>
									● {camera.operationalStatus || "OFFLINE"}
								</span>
								{camAreaInfo.areaName && (
									<button
										type="button"
										className="cam-inspector-drawer__area-chip"
										onClick={() =>
											camAreaInfo.areaId &&
											onFocusAreaOnMap?.(camAreaInfo.areaId)
										}
										title="Định vị khu vực trên bản đồ 3D"
									>
										<MapPin size={11} />
										<span>
											{camAreaInfo.areaName}
											{formatLocation(camAreaInfo.building, camAreaInfo.floor)
												? ` (${formatLocation(camAreaInfo.building, camAreaInfo.floor)})`
												: ""}
										</span>
										<Focus size={11} />
									</button>
								)}
							</div>
						)}
					</div>
				</div>

				<div className="cam-inspector-drawer__header-actions">
					{isAdmin && camera && (
						<>
							<button
								type="button"
								className="cam-inspector-drawer__icon-btn"
								onClick={() => {
									if (isDecommissioned) handleToggleStatus();
									else setConfirmDecommissionOpen(true);
								}}
								disabled={saving}
								title={isDecommissioned ? "Kích hoạt lại camera" : "Tắt camera"}
							>
								{isDecommissioned ? <Power size={15} /> : <PowerOff size={15} />}
							</button>
							<button
								type="button"
								className="cam-inspector-drawer__icon-btn cam-inspector-drawer__icon-btn--danger"
								onClick={() => setConfirmDeleteOpen(true)}
								disabled={saving}
								title="Xóa camera"
							>
								<Trash2 size={15} />
							</button>
						</>
					)}
					<button
						type="button"
						className="campus-rail-close-btn"
						onClick={onClose}
						aria-label="Đóng chi tiết camera"
					>
						<X size={15} />
					</button>
				</div>
			</div>

			{loading && (
				<div className="cam-inspector-drawer__state">
					<LoadingState text="Đang tải thông tin chi tiết camera..." />
				</div>
			)}

			{!loading && !camera && (
				<div className="cam-inspector-drawer__state">
					<ErrorState
						title="Không tải được thông tin camera"
						message={loadError?.message || "Camera không tồn tại hoặc đã bị xóa."}
						onRetry={loadCameraDetails}
					/>
				</div>
			)}

			{!loading && camera && (
				<>
					<div className="cam-inspector-drawer__tabs" role="tablist">
						<button
							type="button"
							role="tab"
							aria-selected={activeTab === "general"}
							className={`cam-inspector-tab ${activeTab === "general" ? "cam-inspector-tab--active" : ""}`}
							onClick={() => setActiveTab("general")}
						>
							<Info size={14} />
							<span>Thông tin</span>
						</button>
						{isAdmin && (
							<button
								type="button"
								role="tab"
								aria-selected={activeTab === "stream"}
								className={`cam-inspector-tab ${activeTab === "stream" ? "cam-inspector-tab--active" : ""}`}
								onClick={() => setActiveTab("stream")}
							>
								<Video size={14} />
								<span>Stream</span>
							</button>
						)}
						<button
							type="button"
							role="tab"
							aria-selected={activeTab === "surveillance"}
							className={`cam-inspector-tab ${activeTab === "surveillance" ? "cam-inspector-tab--active" : ""}`}
							onClick={() => setActiveTab("surveillance")}
						>
							<Layers size={14} />
							<span>Vùng ROI</span>
						</button>
						<button
							type="button"
							role="tab"
							aria-selected={activeTab === "access-logs"}
							className={`cam-inspector-tab ${activeTab === "access-logs" ? "cam-inspector-tab--active" : ""}`}
							onClick={() => setActiveTab("access-logs")}
						>
							<Activity size={14} />
							<span>Ra vào</span>
						</button>
						<button
							type="button"
							role="tab"
							aria-selected={activeTab === "health"}
							className={`cam-inspector-tab ${activeTab === "health" ? "cam-inspector-tab--active" : ""}`}
							onClick={() => setActiveTab("health")}
						>
							<HeartPulse size={14} />
							<span>Kết nối</span>
						</button>
					</div>

					<div className="cam-inspector-drawer__body">
						{activeTab === "general" && (
							<CameraGeneralTab
								camera={camera}
								generalForm={generalForm}
								setGeneralForm={setGeneralForm}
								availableAreas={availableAreas}
								saving={saving}
								onSubmit={handleGeneralSubmit}
								isAdmin={isAdmin}
								onPickAreaOnMap={isAdmin ? onRequestPickAreaOnMap : null}
								isPickingAreaOnMap={isPickingAreaOnMap}
							/>
						)}

						{activeTab === "stream" && isAdmin && (
							<CameraStreamTab
								camera={camera}
								streamForm={streamForm}
								setStreamForm={setStreamForm}
								saving={saving}
								onSubmit={handleStreamSubmit}
								testingConnection={testingConnection}
								testResult={testResult}
								onTestConnection={handleTestConnection}
								onCloseTestResult={() => setTestResult(null)}
								startingStream={startingStream}
								stoppingStream={stoppingStream}
								onStartStream={handleStartStream}
								onStopStream={handleStopStream}
							/>
						)}

						{activeTab === "surveillance" && (
							<CameraSurveillanceTab
								camera={camera}
								capturingSnapshot={capturingSnapshot}
								refreshingLive={refreshingLive}
								compareMode={compareMode}
								liveSnapshot={liveSnapshot}
								onCaptureNewSnapshot={isAdmin ? handleCaptureNewSnapshot : undefined}
								onEditCurrentRoi={isAdmin ? handleEditCurrentRoi : undefined}
								onCheckDrift={isAdmin ? handleCheckDrift : undefined}
								onCloseCompare={() => {
									setCompareMode(false);
									setLiveSnapshot(null);
								}}
								onCaptureFromLiveStream={
									isAdmin ? handleCaptureFromLiveStream : undefined
								}
								viewMode={surveillanceViewMode}
								onViewModeChange={setSurveillanceViewMode}
								onError={(msg) => toast.error(msg)}
							/>
						)}

						{activeTab === "access-logs" && (
							<CameraAccessLogsTab camera={camera} />
						)}

						{activeTab === "health" && (
							<CameraHealthLogs
								logs={logs}
								loading={logsLoading}
								page={logPage}
								totalPages={logTotalPages}
								onPageChange={setLogPage}
								onRefresh={loadLogs}
								error={logsError}
							/>
						)}
					</div>
				</>
			)}

			{isAdmin && (
				<RoiEditorModal
					isOpen={roiModalOpen}
					onClose={() => setRoiModalOpen(false)}
					snapshotBase64={activeSnapshotForModal?.data}
					snapshotWidth={activeSnapshotForModal?.width || 1920}
					snapshotHeight={activeSnapshotForModal?.height || 1080}
					initialRoiGeometry={camera?.roiGeometry}
					availableAreas={camera?.assignedAreas || []}
					onSave={handleSaveRoi}
				/>
			)}

			<Modal
				isOpen={confirmDecommissionOpen}
				onClose={() => setConfirmDecommissionOpen(false)}
				title="Tắt camera giám sát"
				size="sm"
			>
				<p style={{ margin: "0 0 16px", fontSize: "0.9rem" }}>
					Bạn có chắc chắn muốn tắt camera{" "}
					<strong>
						{camera?.cameraCode} - {camera?.name}
					</strong>
					? Luồng stream và phân tích AI của camera sẽ tạm ngừng.
				</p>
				<div style={{ display: "flex", justifyContent: "flex-end", gap: "8px" }}>
					<Button
						variant="secondary"
						onClick={() => setConfirmDecommissionOpen(false)}
					>
						Hủy
					</Button>
					<Button variant="danger" onClick={handleToggleStatus} disabled={saving}>
						Xác nhận tắt
					</Button>
				</div>
			</Modal>

			<Modal
				isOpen={confirmDeleteOpen}
				onClose={() => setConfirmDeleteOpen(false)}
				title="Xóa camera"
				size="sm"
			>
				<p style={{ margin: "0 0 16px", fontSize: "0.9rem" }}>
					Bạn có chắc chắn muốn xóa camera{" "}
					<strong>
						{camera?.cameraCode} - {camera?.name}
					</strong>
					? Hành động này sẽ gỡ camera khỏi danh sách giám sát và hủy kết nối.
				</p>
				<div style={{ display: "flex", justifyContent: "flex-end", gap: "8px" }}>
					<Button
						variant="secondary"
						onClick={() => setConfirmDeleteOpen(false)}
					>
						Hủy
					</Button>
					<Button variant="danger" onClick={handleConfirmDelete} disabled={saving}>
						Xác nhận xóa
					</Button>
				</div>
			</Modal>
		</div>
	);
}
