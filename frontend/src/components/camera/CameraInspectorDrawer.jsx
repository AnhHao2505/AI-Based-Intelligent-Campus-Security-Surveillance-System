import { useState, useEffect, useCallback } from "react";
import {
	X,
	Video,
	Info,
	MapPin,
	Layers,
	Activity,
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
	startStream,
	stopStream,
	testConnection,
	updateRoiGeometry,
} from "../../services/cameraService";
import { formatLocation } from "../../utils/formatLocation";
import { extractCameraAreaInfo } from "../../utils/campusCameraHelpers";
import RoiEditorModal from "./RoiEditorModal";
import CameraGeneralTab from "./CameraGeneralTab";
import CameraStreamTab from "./CameraStreamTab";
import CameraSurveillanceTab from "./CameraSurveillanceTab";
import CameraAccessLogsTab from "./CameraAccessLogsTab";
import { LoadingState, ErrorState, Modal, Button } from "../ui";
import "../../styles/cameraShared.css";
import "../../styles/CameraDetailPage.css";

const VALID_TABS = [
	"general",
	"stream",
	"surveillance",
	"access-logs",
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
	fovPlacement = null,
	mapPlacement = null,
	isPickingLocationOnMap = false,
	onRequestPickLocationOnMap,
	onUpdateFov,
	onRemoveFov,
	onRemoveMapLocation,
}) {
	const activePlacement = mapPlacement || fovPlacement;
	const handleRemoveLocation = onRemoveMapLocation || onRemoveFov;
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

	const [testingConnection, setTestingConnection] = useState(false);
	const [testResult, setTestResult] = useState(null);

	const [startingStream, setStartingStream] = useState(false);
	const [stoppingStream, setStoppingStream] = useState(false);
	const [roiModalOpen, setRoiModalOpen] = useState(false);
	const [activeSnapshotForModal, setActiveSnapshotForModal] = useState(null);

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

	useEffect(() => {
		setTestResult(null);
		loadCameraDetails();
	}, [loadCameraDetails]);

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
				onCameraMutated?.();
			} else {
				setCamera((prev) => ({
					...prev,
					operationalStatus: res?.operationalStatus || "OFFLINE",
				}));
				toast.error(res?.message || "Không thể kết nối Stream trực tiếp.");
				onCameraMutated?.();
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

	const handleOpenRoiEditor = (snapshotMeta) => {
		setActiveSnapshotForModal(
			snapshotMeta || { data: null, width: 1920, height: 1080 },
		);
		setRoiModalOpen(true);
	};

	const handleSaveRoi = async (roiGeometry) => {
		const updated = await updateRoiGeometry(cameraId, roiGeometry);
		const newRoi = updated?.roiGeometry || updated;
		setCamera((prev) => ({
			...prev,
			roiGeometry: { ...prev?.roiGeometry, ...newRoi },
		}));
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
								mapPlacement={activePlacement}
								isPickingLocationOnMap={isPickingLocationOnMap}
								onPickLocationOnMap={isAdmin ? onRequestPickLocationOnMap : null}
								onRemoveMapLocation={handleRemoveLocation}
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
								onOpenRoiEditor={isAdmin ? handleOpenRoiEditor : undefined}
							/>
						)}

						{activeTab === "access-logs" && (
							<CameraAccessLogsTab camera={camera} />
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
