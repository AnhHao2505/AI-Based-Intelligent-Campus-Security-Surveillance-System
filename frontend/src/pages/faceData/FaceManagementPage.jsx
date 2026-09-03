import React, { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import heic2any from "heic2any";
import { faceDataService } from "../../services/faceDataService";
import "../../styles/FaceManagementPage.css";

export default function FaceManagementPage() {
	// State danh sách và phân trang
	const [faces, setFaces] = useState([]);
	const [loading, setLoading] = useState(false);
	const [keyword, setKeyword] = useState("");
	const [page, setPage] = useState(0);
	const [totalPages, setTotalPages] = useState(0);
	const [totalElements, setTotalElements] = useState(0);

	// State Modal Thêm đơn lẻ
	const [showSingleModal, setShowSingleModal] = useState(false);
	const [singleForm, setSingleForm] = useState({ code: "", fullName: "" });
	const [frontFile, setFrontFile] = useState(null);
	const [frontPreview, setFrontPreview] = useState(null);
	const [singleSubmitting, setSingleSubmitting] = useState(false);

	// State Modal Nạp hàng loạt ZIP
	const [showBulkModal, setShowBulkModal] = useState(false);
	const [zipFile, setZipFile] = useState(null);
	const [bulkSubmitting, setBulkSubmitting] = useState(false);
	const [bulkResult, setBulkResult] = useState(null);

	// Thông báo Toast
	const [toast, setToast] = useState(null);

	const showToast = (message, type = "success") => {
		setToast({ message, type });
		setTimeout(() => setToast(null), 4000);
	};

	const resolveImageUrl = (url) => {
		if (!url) return "";
		if (url.startsWith("minio:9000/")) {
			return `http://localhost:9000/${url.substring("minio:9000/".length)}`;
		}
		if (url.startsWith("http://minio:9000/")) {
			return url.replace("http://minio:9000/", "http://localhost:9000/");
		}
		return url;
	};

	// Tải danh sách hồ sơ
	const loadFaces = async (searchKw = keyword, currentPage = page) => {
		setLoading(true);
		try {
			const data = await faceDataService.getFaceList(searchKw, currentPage, 10);
			setFaces(data.content || []);
			setTotalPages(data.totalPages || 0);
			setTotalElements(data.totalElements || 0);
		} catch (err) {
			console.error(err);
			showToast("Không thể kết nối đến Backend hoặc chưa có dữ liệu.", "error");
		} finally {
			setLoading(false);
		}
	};

	useEffect(() => {
		loadFaces(keyword, page);
	}, [page]);

	const handleSearch = (e) => {
		e.preventDefault();
		setPage(0);
		loadFaces(keyword, 0);
	};

	// Xử lý chọn ảnh đơn lẻ có Live Preview (Hỗ trợ tự động chuyển đổi HEIC -> JPG trực tiếp trên trình duyệt)
	const handleImageChange = async (e) => {
		const file = e.target.files[0];
		if (!file) return;

		let finalFile = file;
		let previewUrl = "";

		const fileNameLower = file.name.toLowerCase();
		const isHeic =
			fileNameLower.endsWith(".heic") ||
			fileNameLower.endsWith(".heif") ||
			file.type.includes("heic") ||
			file.type.includes("heif");

		if (isHeic) {
			try {
				const conversionResult = await heic2any({
					blob: file,
					toType: "image/jpeg",
					quality: 0.88,
				});
				const blob = Array.isArray(conversionResult)
					? conversionResult[0]
					: conversionResult;
				finalFile = new File(
					[blob],
					file.name.replace(/\.(heic|heif)$/i, ".jpg"),
					{ type: "image/jpeg" },
				);
				previewUrl = URL.createObjectURL(blob);
			} catch (err) {
				console.error("Lỗi chuyển đổi HEIC trên browser:", err);
				previewUrl = URL.createObjectURL(file);
			}
		} else {
			previewUrl = URL.createObjectURL(file);
		}

		setFrontFile(finalFile);
		setFrontPreview(previewUrl);
	};

	// Submit form đơn lẻ
	const handleSingleSubmit = async (e) => {
		e.preventDefault();
		if (!singleForm.code || !singleForm.fullName) {
			showToast("Vui lòng nhập đầy đủ Mã số và Họ tên.", "error");
			return;
		}
		if (!frontFile) {
			showToast("Vui lòng tải lên ảnh chân dung chính diện.", "error");
			return;
		}

		setSingleSubmitting(true);
		try {
			await faceDataService.registerSingleFace(
				singleForm.code,
				singleForm.fullName,
				frontFile,
			);
			showToast(
				`Đã nạp thành công hồ sơ [${singleForm.code}] vào Database & AI!`,
			);
			setShowSingleModal(false);
			resetSingleForm();
			loadFaces(keyword, 0);
		} catch (err) {
			showToast(err.message || "Lỗi khi đăng ký hồ sơ.", "error");
		} finally {
			setSingleSubmitting(false);
		}
	};

	const resetSingleForm = () => {
		setSingleForm({ code: "", fullName: "" });
		setFrontFile(null);
		setFrontPreview(null);
	};

	// Submit file ZIP hàng loạt
	const handleBulkSubmit = async (e) => {
		e.preventDefault();
		if (!zipFile) {
			showToast("Vui lòng chọn file .ZIP.", "error");
			return;
		}

		setBulkSubmitting(true);
		setBulkResult(null);
		try {
			const result = await faceDataService.bulkImportZip(zipFile);
			setBulkResult(result);
			showToast(
				`Nạp thành công ${result.successCount}/${result.totalProcessed} người dùng!`,
			);
			loadFaces(keyword, 0);
		} catch (err) {
			showToast(err.message || "Lỗi nạp file ZIP.", "error");
		} finally {
			setBulkSubmitting(false);
		}
	};

	// Xóa hồ sơ
	const handleDelete = async (id, code, name) => {
		if (!window.confirm(`Bạn có chắc muốn xóa hồ sơ của "${name}" (${code})?`))
			return;

		try {
			await faceDataService.deleteFace(id);
			showToast(`Đã xóa thành công hồ sơ [${code}].`);
			loadFaces(keyword, page);
		} catch (err) {
			showToast(err.message || "Lỗi khi xóa hồ sơ.", "error");
		}
	};

	return (
		<div className="face-mgmt-page">
			{/* Ambient Glowing Orbs */}
			<div className="face-bg-ambient">
				<div className="face-bg-orb face-bg-orb--1" />
				<div className="face-bg-orb face-bg-orb--2" />
				<div className="face-bg-orb face-bg-orb--3" />
			</div>

			{/* Toast Notification */}
			{toast && (
				<div className={`toast-notification ${toast.type}`}>
					<span>{toast.type === "success" ? "✓" : "⚠️"}</span> {toast.message}
				</div>
			)}

			{/* Main Content Area */}
			<main className="face-main-content">
				{/* Header Section */}
				<div className="face-header-section">
					<div>
						<div className="badge-pill">
							<span className="badge-dot" />
							DATASET & AI EMBEDDING
						</div>
						<h1 className="face-title-gradient">
							Quản Lý Cơ Sở Dữ Liệu Khuôn Mặt
						</h1>
						<p className="face-subtitle">
							Quản trị dữ liệu nhận diện cho toàn bộ sinh viên, cán bộ và khách
							trong khuôn viên Campus.
						</p>
					</div>

					<div className="face-action-buttons">
						<button
							className="btn-glow-primary"
							onClick={() => setShowSingleModal(true)}
						>
							<span>➕</span> Thêm Hồ Sơ Mới
						</button>
						<button
							className="btn-glow-secondary"
							onClick={() => setShowBulkModal(true)}
						>
							<span>📦</span> Nạp Hàng Loạt (.ZIP)
						</button>
					</div>
				</div>

				{/* Metric Summary Cards */}
				<div className="metrics-grid">
					<div className="glass-metric-card">
						<div className="metric-icon-box">👥</div>
						<div>
							<div className="metric-label">Tổng Hồ Sơ Khuôn Mặt</div>
							<div className="metric-number">
								{totalElements} <span className="metric-tag">người</span>
							</div>
						</div>
					</div>

					<div className="glass-metric-card">
						<div className="metric-icon-box">🧠</div>
						<div>
							<div className="metric-label">Mô Hình AI Vector</div>
							<div className="metric-number">
								512 <span className="metric-tag">Dimensions (pgvector)</span>
							</div>
						</div>
					</div>
				</div>

				{/* Filter & Search Bar */}
				<div className="filter-glass-bar">
					<form
						onSubmit={handleSearch}
						className="search-form-dark"
					>
						<div className="search-input-wrapper">
							<span className="search-icon">🔍</span>
							<input
								type="text"
								className="search-dark-input"
								placeholder="Tìm theo Mã định danh (MSSV, MSNV) hoặc Họ tên..."
								value={keyword}
								onChange={(e) => setKeyword(e.target.value)}
							/>
						</div>
						<button
							type="submit"
							className="btn-search-submit"
						>
							Tìm Kiếm
						</button>
						{keyword && (
							<button
								type="button"
								className="btn-search-reset"
								onClick={() => {
									setKeyword("");
									loadFaces("", 0);
								}}
							>
								Xóa bộ lọc
							</button>
						)}
					</form>
				</div>

				{/* Data Table Card */}
				<div className="table-glass-card">
					{loading ? (
						<div className="table-status-box">
							<div className="loading-spinner" />
							<p>Đang truy vấn cơ sở dữ liệu khuôn mặt...</p>
						</div>
					) : faces.length === 0 ? (
						<div className="table-status-box">
							<div className="empty-avatar-icon">👤</div>
							<h3>Chưa có dữ liệu khuôn mặt nào</h3>
							<p>
								Hãy bấm <strong>"Thêm Hồ Sơ Mới"</strong> hoặc{" "}
								<strong>"Nạp Hàng Loạt (.ZIP)"</strong> để nạp dữ liệu cho AI
								đối soát.
							</p>
						</div>
					) : (
						<div className="table-responsive">
							<table className="dark-face-table">
								<thead>
									<tr>
										<th>Mã số định danh</th>
										<th>Họ và Tên</th>
										<th>Ảnh Chân Dung</th>
										<th>Ngày Nạp</th>
										<th>Thao Tác</th>
									</tr>
								</thead>
								<tbody>
									{faces.map((item) => (
										<tr key={item.id}>
											<td>
												<span className="code-chip">{item.code}</span>
											</td>
											<td className="user-name-cell">{item.fullName}</td>
											<td>
												<div className="face-thumbnails-group">
													<div
														className="thumb-preview-box"
														title="Ảnh chính diện"
													>
														<img
															src={resolveImageUrl(item.imageFrontUrl)}
															alt="Front"
															onError={(e) => {
																e.target.src =
																	"https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=100&auto=format&fit=crop&q=60";
															}}
														/>
														<span>Chính diện</span>
													</div>
												</div>
											</td>
											<td className="date-cell">
												{item.createdAt
													? new Date(item.createdAt).toLocaleString("vi-VN")
													: "—"}
											</td>
											<td>
												<button
													className="btn-trash-action"
													onClick={() =>
														handleDelete(item.id, item.code, item.fullName)
													}
													title="Xóa hồ sơ này"
												>
													🗑️ Xóa
												</button>
											</td>
										</tr>
									))}
								</tbody>
							</table>
						</div>
					)}

					{/* Pagination Controls */}
					{totalPages > 1 && (
						<div className="pagination-dark-bar">
							<button
								className="btn-page-dark"
								disabled={page === 0}
								onClick={() => setPage(page - 1)}
							>
								◀ Trang trước
							</button>
							<span className="page-indicator">
								Trang {page + 1} / {totalPages}
							</span>
							<button
								className="btn-page-dark"
								disabled={page >= totalPages - 1}
								onClick={() => setPage(page + 1)}
							>
								Trang sau ▶
							</button>
						</div>
					)}
				</div>
			</main>

			{/* ================= MODAL 1: THÊM ĐƠN LẺ ================= */}
			{showSingleModal && (
				<div className="modal-backdrop-blur">
					<div className="modal-glass-container">
						<div className="modal-header-glass">
							<h2>➕ Thêm Hồ Sơ Khuôn Mặt Mới</h2>
							<button
								className="modal-close-icon"
								onClick={() => setShowSingleModal(false)}
							>
								✕
							</button>
						</div>
						<form
							onSubmit={handleSingleSubmit}
							className="modal-body-form"
						>
							<div className="input-group-dark">
								<label>Mã số định danh (MSSV hoặc MSNV) *</label>
								<input
									type="text"
									placeholder="Ví dụ: SE150001 hoặc NV-SEC-01"
									value={singleForm.code}
									onChange={(e) =>
										setSingleForm({ ...singleForm, code: e.target.value })
									}
									required
								/>
							</div>
							<div className="input-group-dark">
								<label>Họ và Tên *</label>
								<input
									type="text"
									placeholder="Ví dụ: Nguyễn Văn An"
									value={singleForm.fullName}
									onChange={(e) =>
										setSingleForm({ ...singleForm, fullName: e.target.value })
									}
									required
								/>
							</div>

							<div className="single-face-upload-card">
								<label className="upload-label-title">
									📸 Ảnh Chân Dung Chính Diện *
								</label>
								{frontPreview ? (
									<div className="preview-box-active">
										<img
											src={frontPreview}
											alt="Front preview"
										/>
										<label className="btn-reselect">
											Đổi ảnh khác
											<input
												type="file"
												accept="image/*,.heic,.heif,.HEIC,.HEIF"
												onChange={(e) => handleImageChange(e)}
												hidden
											/>
										</label>
									</div>
								) : (
									<label className="dropzone-dark single-dropzone">
										<span>Chọn ảnh chính diện (JPG, PNG, HEIC)</span>
										<input
											type="file"
											accept="image/*,.heic,.heif,.HEIC,.HEIF"
											onChange={(e) => handleImageChange(e)}
											hidden
											required
										/>
									</label>
								)}
							</div>

							<div className="modal-footer-glass">
								<button
									type="button"
									className="btn-cancel-dark"
									onClick={() => setShowSingleModal(false)}
								>
									Hủy
								</button>
								<button
									type="submit"
									className="btn-glow-primary"
									disabled={singleSubmitting}
								>
									{singleSubmitting
										? "Đang trích xuất Vector & Lưu..."
										: "💾 Lưu Hồ Sơ Vào Database"}
								</button>
							</div>
						</form>
					</div>
				</div>
			)}

			{/* ================= MODAL 2: NẠP HÀNG LOẠT ZIP ================= */}
			{showBulkModal && (
				<div className="modal-backdrop-blur">
					<div className="modal-glass-container modal-lg">
						<div className="modal-header-glass">
							<h2>📦 Nạp Dataset Khuôn Mặt Hàng Loạt (.ZIP)</h2>
							<button
								className="modal-close-icon"
								onClick={() => setShowBulkModal(false)}
							>
								✕
							</button>
						</div>
						<form
							onSubmit={handleBulkSubmit}
							className="modal-body-form"
						>
							<div className="info-guide-dark">
								<h4>📌 Quy tắc đặt tên file ảnh trong file .ZIP:</h4>
								<p>
									Tên file theo chuẩn: <code>[MÃ_SỐ]_[HỌ_TÊN].jpg</code> hoặc{" "}
									<code>[MÃ_SỐ]_[HỌ_TÊN]_front.jpg</code>
								</p>
								<ul>
									<li>
										<code>SE150001_Nguyen-Van-A.jpg</code> (Chính diện)
									</li>
									<li>
										<code>SE150001_Nguyen-Van-A_front.jpg</code> (Chính diện)
									</li>
								</ul>
							</div>

							<div className="input-group-dark">
								<label>Chọn file nén Dataset (.ZIP) *</label>
								<input
									type="file"
									accept=".zip,application/zip"
									onChange={(e) => setZipFile(e.target.files[0])}
									required
								/>
							</div>

							{bulkResult && (
								<div className="bulk-report-dark">
									<h4>📊 Báo Cáo Xử Lý Dataset:</h4>
									<p>
										Tổng số hồ sơ trong file:{" "}
										<strong>{bulkResult.totalProcessed}</strong>
									</p>
									<p className="text-success-neon">
										Thành công: <strong>{bulkResult.successCount}</strong> người
									</p>
									{bulkResult.failedCount > 0 && (
										<p className="text-danger-neon">
											Thất bại: <strong>{bulkResult.failedCount}</strong> người
										</p>
									)}
									{bulkResult.importedCodes?.length > 0 && (
										<div className="tag-flex-wrap">
											{bulkResult.importedCodes.map((code, i) => (
												<span
													key={i}
													className="neon-code-tag"
												>
													✓ {code}
												</span>
											))}
										</div>
									)}
									{bulkResult.errorMessages?.length > 0 && (
										<div className="error-log-box">
											{bulkResult.errorMessages.map((msg, i) => (
												<div
													key={i}
													className="error-line"
												>
													⚠️ {msg}
												</div>
											))}
										</div>
									)}
								</div>
							)}

							<div className="modal-footer-glass">
								<button
									type="button"
									className="btn-cancel-dark"
									onClick={() => setShowBulkModal(false)}
								>
									Đóng
								</button>
								<button
									type="submit"
									className="btn-glow-primary"
									disabled={bulkSubmitting || !zipFile}
								>
									{bulkSubmitting
										? "Đang giải nén & trích xuất AI..."
										: "🚀 Bắt Đầu Nạp Dataset"}
								</button>
							</div>
						</form>
					</div>
				</div>
			)}
		</div>
	);
}
