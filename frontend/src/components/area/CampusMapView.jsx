import React, {
	useState,
	useMemo,
	useRef,
	useCallback,
	useEffect,
} from "react";
// Đặt tên MapGL để KHÔNG che khuất đối tượng Map chuẩn của JavaScript (new Map()).
import MapGL, {
	NavigationControl,
	FullscreenControl,
	Marker,
} from "react-map-gl/maplibre";
import "maplibre-gl/dist/maplibre-gl.css";
import {
	Compass,
	PanelRightClose,
	PanelRightOpen,
	Building2,
	Layers,
	X,
	MapPin,
	AlertCircle,
	Search,
	Crosshair,
	Save,
	Loader2,
	LocateFixed,
	Globe,
} from "lucide-react";
import {
	getLevelConfig,
	getAccessLevelConfig,
	getErrorMessage,
	AREA_LEVEL_CONFIG,
} from "../../utils/areaHelpers";
import { updateArea } from "../../services/areaService";
import "../../styles/CampusMapView.css";

// FPT University HCMC Campus default center (Saigon Hi-Tech Park, District 9)
const CAMPUS_CENTER = {
	longitude: 106.80988,
	latitude: 10.84113,
	zoom: 16.4,
	pitch: 30,
	bearing: -10,
};

// OpenStreetMap Standard Raster Style with multi-subdomain CDN for speed & reliability
const OSM_STYLE = {
	version: 8,
	sources: {
		"osm-tiles": {
			type: "raster",
			tiles: [
				"https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
				"https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
				"https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
			],
			tileSize: 256,
			attribution: "© OpenStreetMap contributors",
			maxzoom: 19,
		},
	},
	layers: [
		{
			id: "osm-tiles-layer",
			type: "raster",
			source: "osm-tiles",
			minzoom: 0,
			maxzoom: 19,
		},
	],
};

const LABEL_MIN_ZOOM = 18.2;
const SEARCH_RESULT_LIMIT = 8;

/** Chuẩn hoá chuỗi để tìm kiếm không dấu, không phân biệt hoa thường. */
function normalizeText(value) {
	return String(value ?? "")
		.normalize("NFD")
		.replace(/[\u0300-\u036f]/g, "")
		.replace(/đ/g, "d")
		.replace(/Đ/g, "D")
		.toLowerCase()
		.trim();
}

/** Kiểm tra cặp [lng, lat] hợp lệ theo ràng buộc của AreaUpdateRequest. */
function isValidLngLat(coords) {
	if (!Array.isArray(coords) || coords.length !== 2) return false;
	const [lng, lat] = coords;
	return (
		Number.isFinite(lng) &&
		Number.isFinite(lat) &&
		lat >= -90 &&
		lat <= 90 &&
		lng >= -180 &&
		lng <= 180
	);
}

function stopMapEvent(e) {
	e?.originalEvent?.stopPropagation?.();
}

/**
 * Trích xuất tọa độ thực tế từ CSDL.
 * Tuyệt đối không dùng heuristic tên gọi, không bịa tọa độ giả định.
 */
export function getValidCoordinates(area) {
	if (!area || area.centerLatitude == null || area.centerLongitude == null) {
		return null;
	}
	const lat = Number(area.centerLatitude);
	const lng = Number(area.centerLongitude);
	if (
		Number.isFinite(lat) &&
		Number.isFinite(lng) &&
		lat >= -90 &&
		lat <= 90 &&
		lng >= -180 &&
		lng <= 180 &&
		(lat !== 0 || lng !== 0)
	) {
		return [lng, lat];
	}
	return null;
}

export default function CampusMapView({
	areas = [],
	selectedAreaId,
	cameraCounts = {},
	onSelectArea,
	isAdmin = false,
	onAreaUpdated,
}) {
	const mapRef = useRef(null);
	const [railCollapsed, setRailCollapsed] = useState(false);
	const [mapZoom, setMapZoom] = useState(CAMPUS_CENTER.zoom);
	const compactLabels = mapZoom < LABEL_MIN_ZOOM;

	// Tìm kiếm khu vực & địa điểm toàn cầu
	const [searchTerm, setSearchTerm] = useState("");
	const [searchOpen, setSearchOpen] = useState(false);
	const [searchActiveIdx, setSearchActiveIdx] = useState(0);
	const [globalResults, setGlobalResults] = useState([]);
	const [isSearchingGlobal, setIsSearchingGlobal] = useState(false);
	const [searchedPlace, setSearchedPlace] = useState(null);

	// Chế độ đặt tọa độ trực tiếp trên bản đồ (chỉ ADMIN)
	const [editingAreaId, setEditingAreaId] = useState(null);
	const [draftCoords, setDraftCoords] = useState(null); // [lng, lat]
	const [savingCoords, setSavingCoords] = useState(false);
	const [locating, setLocating] = useState(false);
	const [coordError, setCoordError] = useState(null);
	const [coordSuccess, setCoordSuccess] = useState(null);
	const isEditingCoords = Boolean(editingAreaId);

	// Khu vực đang chọn
	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	const editingArea = useMemo(() => {
		return areas.find((a) => a.id === editingAreaId) || null;
	}, [areas, editingAreaId]);

	// Kết quả tìm kiếm theo tên / tòa nhà / tầng (không dấu)
	const searchMatches = useMemo(() => {
		const term = normalizeText(searchTerm);
		if (!term) return areas;
		return areas.filter((a) =>
			[a.name, a.building, a.floor].some((v) =>
				normalizeText(v).includes(term),
			),
		);
	}, [areas, searchTerm]);

	const localSearchResults = useMemo(
		() =>
			searchTerm.trim() ? searchMatches.slice(0, SEARCH_RESULT_LIMIT) : [],
		[searchMatches, searchTerm],
	);

	// Tìm kiếm địa điểm toàn cầu (Photon / OpenStreetMap Geocoding)
	useEffect(() => {
		const q = searchTerm.trim();
		if (q.length < 2) {
			setGlobalResults([]);
			setIsSearchingGlobal(false);
			return;
		}

		const abortCtrl = new AbortController();
		setIsSearchingGlobal(true);

		const timer = setTimeout(async () => {
			try {
				const res = await fetch(
					`https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&limit=5&lat=10.84113&lon=106.80988`,
					{ signal: abortCtrl.signal },
				);
				if (!res.ok) throw new Error("Photon query failed");
				const data = await res.json();
				const places = (data.features || []).map((f, idx) => {
					const p = f.properties || {};
					const name = p.name || p.street || p.city || q;
					const details = [p.street, p.district, p.city, p.state, p.country]
						.filter(Boolean)
						.join(", ");
					return {
						id: `global-${idx}-${f.geometry.coordinates.join(",")}`,
						name,
						fullAddress: details || name,
						coords: f.geometry.coordinates, // [lng, lat]
						isGlobal: true,
					};
				});
				setGlobalResults(places);
			} catch (err) {
				if (err.name !== "AbortError") {
					try {
						const nomRes = await fetch(
							`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(q)}&limit=5`,
							{ signal: abortCtrl.signal },
						);
						const nomData = await nomRes.json();
						const places = (nomData || []).map((item, idx) => ({
							id: `nom-${idx}-${item.place_id}`,
							name: item.display_name.split(",")[0],
							fullAddress: item.display_name,
							coords: [parseFloat(item.lon), parseFloat(item.lat)],
							isGlobal: true,
						}));
						setGlobalResults(places);
					} catch {
						setGlobalResults([]);
					}
				}
			} finally {
				setIsSearchingGlobal(false);
			}
		}, 350);

		return () => {
			clearTimeout(timer);
			abortCtrl.abort();
		};
	}, [searchTerm]);

	// Thoát chế độ chỉnh tọa độ nếu khu vực đang sửa bị lọc khỏi danh sách
	useEffect(() => {
		if (editingAreaId && !editingArea) {
			setEditingAreaId(null);
			setDraftCoords(null);
		}
	}, [editingAreaId, editingArea]);

	// Tự ẩn thông báo thành công
	useEffect(() => {
		if (!coordSuccess) return undefined;
		const t = setTimeout(() => setCoordSuccess(null), 3000);
		return () => clearTimeout(t);
	}, [coordSuccess]);

	// Tính toán danh sách khu vực có tọa độ hiển thị (tự dịch pixel trên màn hình khi trùng hoặc gần tọa độ CSDL)
	const { positionedAreas, unpositionedCount } = useMemo(() => {
		const groups = [];
		let unpositioned = 0;

		function getDistanceMeters(c1, c2) {
			const dLat = (c2[1] - c1[1]) * 111320;
			const dLng =
				(c2[0] - c1[0]) * (111320 * Math.cos((c1[1] * Math.PI) / 180));
			return Math.sqrt(dLat * dLat + dLng * dLng);
		}

		areas.forEach((area) => {
			const baseCoords = getValidCoordinates(area);
			if (!baseCoords) {
				unpositioned++;
				return;
			}

			const levelConfig = getLevelConfig(area.areaLevel || area.level);
			const decorated = {
				...area,
				baseCoords,
				color: levelConfig.color || "#3b82f6",
				levelConfig,
			};

			// Gom nhóm các khu vực trùng hoặc cách nhau dưới 12 mét
			let matchedGroup = groups.find(
				(g) => getDistanceMeters(g.centerCoords, baseCoords) < 12,
			);
			if (matchedGroup) {
				matchedGroup.items.push(decorated);
			} else {
				groups.push({
					centerCoords: baseCoords,
					items: [decorated],
				});
			}
		});

		// Tính toán độ lệch pixel (pixelOffset) đảm bảo các pin không bao giờ bị che khuất ở mọi mức zoom
		const result = [];
		groups.forEach((group) => {
			const n = group.items.length;
			if (n === 1) {
				result.push({
					...group.items[0],
					pixelOffset: [0, 0],
					isShifted: false,
					idx: 0,
				});
			} else if (n === 2) {
				// Tách ngang rõ ràng sang 2 bên (cách nhau 40px)
				result.push({
					...group.items[0],
					pixelOffset: [-20, 0],
					isShifted: true,
					idx: 0,
				});
				result.push({
					...group.items[1],
					pixelOffset: [20, 0],
					isShifted: true,
					idx: 1,
				});
			} else if (n === 3) {
				result.push({
					...group.items[0],
					pixelOffset: [-22, 8],
					isShifted: true,
					idx: 0,
				});
				result.push({
					...group.items[1],
					pixelOffset: [22, 8],
					isShifted: true,
					idx: 1,
				});
				result.push({
					...group.items[2],
					pixelOffset: [0, -22],
					isShifted: true,
					idx: 2,
				});
			} else if (n === 4) {
				result.push({
					...group.items[0],
					pixelOffset: [-22, -14],
					isShifted: true,
					idx: 0,
				});
				result.push({
					...group.items[1],
					pixelOffset: [22, -14],
					isShifted: true,
					idx: 1,
				});
				result.push({
					...group.items[2],
					pixelOffset: [-22, 14],
					isShifted: true,
					idx: 2,
				});
				result.push({
					...group.items[3],
					pixelOffset: [22, 14],
					isShifted: true,
					idx: 3,
				});
			} else {
				// Đa giác đều / vòng tròn mở rộng theo số lượng pin
				const radius = Math.min(50, 26 + n * 3);
				group.items.forEach((item, idx) => {
					const angle = (2 * Math.PI * idx) / n - Math.PI / 2;
					const dx = Math.round(radius * Math.cos(angle));
					const dy = Math.round(radius * 0.75 * Math.sin(angle));
					result.push({
						...item,
						pixelOffset: [dx, dy],
						isShifted: true,
						idx,
					});
				});
			}
		});

		return {
			positionedAreas: result,
			unpositionedCount: unpositioned,
		};
	}, [areas]);

	// Di chuyển khung nhìn bản đồ về tọa độ
	const handleFlyToCoords = useCallback((coords, zoomLevel = 17.5) => {
		if (mapRef.current && coords) {
			mapRef.current.flyTo({
				center: coords,
				zoom: zoomLevel,
				duration: 800,
			});
		}
	}, []);

	// Chọn một khu vực cụ thể
	const handleSelectAreaItem = useCallback(
		(areaId, coords) => {
			if (coords) {
				handleFlyToCoords(coords);
			}
			if (onSelectArea) {
				onSelectArea(areaId);
			}
		},
		[handleFlyToCoords, onSelectArea],
	);

	// Về toàn cảnh khuôn viên
	const handleResetCampusView = useCallback(() => {
		if (mapRef.current) {
			mapRef.current.flyTo({
				center: [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude],
				zoom: CAMPUS_CENTER.zoom,
				pitch: CAMPUS_CENTER.pitch,
				bearing: CAMPUS_CENTER.bearing,
				duration: 1000,
			});
		}
	}, []);

	// ===== Tìm kiếm =====
	const handlePickSearchResult = useCallback(
		(area) => {
			if (!area) return;
			const pos = positionedAreas.find((p) => p.id === area.id);
			const coords = pos ? pos.baseCoords : getValidCoordinates(area);
			if (coords) handleFlyToCoords(coords, 18);
			if (onSelectArea && area.id !== selectedAreaId) onSelectArea(area.id);
			setSearchOpen(false);
		},
		[handleFlyToCoords, onSelectArea, selectedAreaId, positionedAreas],
	);

	const handlePickGlobalResult = useCallback(
		(place) => {
			if (!place || !place.coords) return;
			handleFlyToCoords(place.coords, 16);
			setSearchedPlace(place);
			setSearchOpen(false);
			if (isEditingCoords) {
				setDraftCoords(place.coords);
			}
		},
		[handleFlyToCoords, isEditingCoords],
	);

	const allCombinedResults = useMemo(
		() => [...localSearchResults, ...globalResults],
		[localSearchResults, globalResults],
	);

	const handleSearchKeyDown = (e) => {
		if (e.key === "Escape") {
			setSearchTerm("");
			setSearchOpen(false);
			return;
		}
		if (allCombinedResults.length === 0) return;
		if (e.key === "ArrowDown") {
			e.preventDefault();
			setSearchActiveIdx((i) => (i + 1) % allCombinedResults.length);
		} else if (e.key === "ArrowUp") {
			e.preventDefault();
			setSearchActiveIdx(
				(i) => (i - 1 + allCombinedResults.length) % allCombinedResults.length,
			);
		} else if (e.key === "Enter") {
			e.preventDefault();
			const picked =
				allCombinedResults[
					Math.min(searchActiveIdx, allCombinedResults.length - 1)
				];
			if (picked) {
				if (picked.isGlobal) {
					handlePickGlobalResult(picked);
				} else {
					handlePickSearchResult(picked);
				}
			}
		}
	};

	// ===== Đặt tọa độ trên bản đồ (ADMIN) =====
	const handleStartEditCoords = useCallback(() => {
		if (!isAdmin || !selectedArea) return;
		const current = getValidCoordinates(selectedArea);
		let initial = current;
		if (!initial && mapRef.current) {
			const c = mapRef.current.getCenter();
			initial = [c.lng, c.lat];
		}
		setEditingAreaId(selectedArea.id);
		setDraftCoords(
			initial || [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude],
		);
		setCoordError(null);
		setCoordSuccess(null);
		if (current) handleFlyToCoords(current, 18);
	}, [isAdmin, selectedArea, handleFlyToCoords]);

	const handleCancelEditCoords = useCallback(() => {
		setEditingAreaId(null);
		setDraftCoords(null);
		setCoordError(null);
	}, []);

	const handleUseCurrentLocation = useCallback(() => {
		if (!navigator.geolocation) {
			setCoordError("Trình duyệt không hỗ trợ định vị GPS.");
			return;
		}
		setLocating(true);
		setCoordError(null);
		navigator.geolocation.getCurrentPosition(
			(pos) => {
				const coords = [pos.coords.longitude, pos.coords.latitude];
				setDraftCoords(coords);
				handleFlyToCoords(coords, 18);
				setLocating(false);
			},
			(err) => {
				setLocating(false);
				setCoordError(
					err?.code === 1
						? "Bạn đã từ chối quyền truy cập vị trí."
						: "Không lấy được vị trí hiện tại. Vui lòng thử lại.",
				);
			},
			{ enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
		);
	}, [handleFlyToCoords]);

	const handleSaveCoords = useCallback(async () => {
		if (!editingArea || !isValidLngLat(draftCoords)) {
			setCoordError("Tọa độ không hợp lệ (vĩ độ -90..90, kinh độ -180..180).");
			return;
		}
		if (editingArea.version == null) {
			setCoordError("Thiếu phiên bản dữ liệu khu vực. Vui lòng tải lại trang.");
			return;
		}
		const [lng, lat] = draftCoords;
		setSavingCoords(true);
		setCoordError(null);
		try {
			// Giữ nguyên tên, loại, tầng hiện tại — chỉ đổi tọa độ (BR-TC-13: gửi kèm version)
			const res = await updateArea(editingArea.id, {
				name: editingArea.name,
				areaLevel:
					typeof editingArea.areaLevel === "string"
						? editingArea.areaLevel
						: editingArea.areaLevel?.code,
				building: null,
				floor: null,
				floorId: null,
				centerLatitude: Number(lat.toFixed(7)),
				centerLongitude: Number(lng.toFixed(7)),
				version: editingArea.version,
			});
			onAreaUpdated?.(editingArea.id, res);
			setEditingAreaId(null);
			setDraftCoords(null);
			setCoordSuccess("Đã cập nhật tọa độ khu vực.");
		} catch (err) {
			setCoordError(getErrorMessage(err));
		} finally {
			setSavingCoords(false);
		}
	}, [editingArea, draftCoords, onAreaUpdated]);

	const handleMapClick = useCallback(
		(e) => {
			if (isEditingCoords && e?.lngLat) {
				setDraftCoords([e.lngLat.lng, e.lngLat.lat]);
				setCoordError(null);
			}
		},
		[isEditingCoords],
	);

	return (
		<div
			className={`campus-map-layout ${railCollapsed ? "campus-map-layout--expanded" : ""}`}
		>
			{/* LEFT: MAIN MAPLIBRE CANVAS */}
			<div className="campus-map-card">
				{/* Floating Top Controls */}
				<div className="campus-map-floating-bar">
					{/* Ô tìm kiếm khu vực */}
					<div className="campus-map-search">
						<Search
							size={14}
							className="campus-map-search__icon"
						/>
						<input
							id="campus-map-search-input"
							type="text"
							className="campus-map-search__input"
							placeholder="Tìm khu vực, tòa nhà, tầng..."
							value={searchTerm}
							onChange={(e) => {
								setSearchTerm(e.target.value);
								setSearchOpen(true);
								setSearchActiveIdx(0);
							}}
							onFocus={() => setSearchOpen(true)}
							onBlur={() => setTimeout(() => setSearchOpen(false), 150)}
							onKeyDown={handleSearchKeyDown}
							autoComplete="off"
							aria-label="Tìm kiếm khu vực trên bản đồ"
						/>
						{searchTerm && (
							<button
								type="button"
								className="campus-map-search__clear"
								onClick={() => {
									setSearchTerm("");
									setSearchOpen(false);
								}}
								title="Xoá tìm kiếm"
							>
								<X size={12} />
							</button>
						)}

						{searchOpen && searchTerm.trim() && (
							<div className="campus-map-search__results">
								{/* Khu vực trong khuôn viên */}
								{localSearchResults.length > 0 && (
									<div className="campus-map-search__section">
										<div className="campus-map-search__section-title">
											<Building2 size={12} />
											<span>Khu vực khuôn viên</span>
										</div>
										{localSearchResults.map((area, idx) => {
											const hasCoords = Boolean(getValidCoordinates(area));
											return (
												<button
													type="button"
													key={area.id}
													className={`campus-map-search__item ${idx === searchActiveIdx ? "campus-map-search__item--active" : ""}`}
													onMouseDown={(e) => e.preventDefault()}
													onMouseEnter={() => setSearchActiveIdx(idx)}
													onClick={() => handlePickSearchResult(area)}
												>
													<MapPin
														size={13}
														className={
															hasCoords
																? "campus-map-search__pin"
																: "campus-map-search__pin campus-map-search__pin--muted"
														}
													/>
													<span className="campus-map-search__text">
														<span className="campus-map-search__name">
															{area.name}
														</span>
														<span className="campus-map-search__sub">
															{[area.building, area.floor]
																.filter(Boolean)
																.join(" · ") || "—"}
															{!hasCoords && " · Chưa có GPS"}
														</span>
													</span>
												</button>
											);
										})}
									</div>
								)}

								{/* Địa điểm toàn cầu / OpenStreetMap */}
								{(globalResults.length > 0 || isSearchingGlobal) && (
									<div className="campus-map-search__section">
										<div className="campus-map-search__section-title">
											<Globe size={12} />
											<span>Địa điểm thế giới (OpenStreetMap)</span>
											{isSearchingGlobal && (
												<Loader2
													size={11}
													className="animate-spin"
													style={{ marginLeft: "auto" }}
												/>
											)}
										</div>
										{globalResults.map((place, idx) => {
											const globalIdx = localSearchResults.length + idx;
											return (
												<button
													type="button"
													key={place.id}
													className={`campus-map-search__item ${globalIdx === searchActiveIdx ? "campus-map-search__item--active" : ""}`}
													onMouseDown={(e) => e.preventDefault()}
													onMouseEnter={() => setSearchActiveIdx(globalIdx)}
													onClick={() => handlePickGlobalResult(place)}
												>
													<Globe
														size={13}
														className="campus-map-search__pin text-primary"
													/>
													<span className="campus-map-search__text">
														<span className="campus-map-search__name">
															{place.name}
														</span>
														<span className="campus-map-search__sub">
															{place.fullAddress}
														</span>
													</span>
												</button>
											);
										})}
									</div>
								)}

								{localSearchResults.length === 0 &&
									globalResults.length === 0 &&
									!isSearchingGlobal && (
										<div className="campus-map-search__empty">
											Không tìm thấy khu vực hoặc địa điểm phù hợp
										</div>
									)}
							</div>
						)}
					</div>

					<button
						type="button"
						className="campus-map-btn-icon"
						onClick={handleResetCampusView}
						title="Về toàn cảnh khuôn viên"
					>
						<Compass size={14} />
					</button>

					<button
						type="button"
						className="campus-map-btn-icon"
						onClick={() => setRailCollapsed(!railCollapsed)}
						title={railCollapsed ? "Mở danh sách khu vực" : "Thu gọn danh sách"}
					>
						{railCollapsed ? (
							<PanelRightOpen size={14} />
						) : (
							<PanelRightClose size={14} />
						)}
					</button>
				</div>

				{/* Thanh công cụ đặt tọa độ (ADMIN) */}
				{isEditingCoords && editingArea && (
					<div className="campus-coord-editor">
						<div className="campus-coord-editor__head">
							<Crosshair size={14} />
							<span>
								Đặt vị trí: <strong>{editingArea.name}</strong>
							</span>
						</div>
						<p className="campus-coord-editor__hint">
							Nhấn lên bản đồ hoặc kéo ghim để chọn vị trí.
						</p>
						<div className="campus-coord-editor__values">
							<span>
								Vĩ độ{" "}
								<code>{draftCoords ? draftCoords[1].toFixed(6) : "—"}</code>
							</span>
							<span>
								Kinh độ{" "}
								<code>{draftCoords ? draftCoords[0].toFixed(6) : "—"}</code>
							</span>
						</div>
						{coordError && (
							<div className="campus-coord-editor__error">
								<AlertCircle size={12} />
								<span>{coordError}</span>
							</div>
						)}
						<div className="campus-coord-editor__actions">
							<button
								type="button"
								id="campus-coord-locate-btn"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleUseCurrentLocation}
								disabled={locating || savingCoords}
								title="Dùng vị trí GPS hiện tại của thiết bị"
							>
								{locating ? (
									<Loader2
										size={13}
										className="animate-spin"
									/>
								) : (
									<LocateFixed size={13} />
								)}
								Vị trí của tôi
							</button>
							<button
								type="button"
								id="campus-coord-cancel-btn"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleCancelEditCoords}
								disabled={savingCoords}
							>
								Huỷ
							</button>
							<button
								type="button"
								id="campus-coord-save-btn"
								className="campus-coord-btn campus-coord-btn--primary"
								onClick={handleSaveCoords}
								disabled={savingCoords || !isValidLngLat(draftCoords)}
							>
								{savingCoords ? (
									<Loader2
										size={13}
										className="animate-spin"
									/>
								) : (
									<Save size={13} />
								)}
								Lưu tọa độ
							</button>
						</div>
					</div>
				)}

				{coordSuccess && (
					<div className="campus-coord-toast">{coordSuccess}</div>
				)}

				{/* Map Viewport */}
				<div
					className={`campus-map-viewport ${compactLabels ? "campus-map-viewport--compact" : ""} ${isEditingCoords ? "campus-map-viewport--editing" : ""}`}
				>
					<MapGL
						ref={mapRef}
						initialViewState={CAMPUS_CENTER}
						onZoomEnd={(e) => setMapZoom(e.viewState.zoom)}
						onClick={handleMapClick}
						mapStyle={OSM_STYLE}
						style={{ width: "100%", height: "100%" }}
						minZoom={1}
						maxZoom={20}
					>
						{/* Native Map Controls */}
						<NavigationControl
							position="top-right"
							showCompass
							showZoom
						/>
						<FullscreenControl position="top-right" />

						{/* Ghim nháp khi đang đặt tọa độ (kéo thả được) */}
						{isEditingCoords && isValidLngLat(draftCoords) && (
							<Marker
								longitude={draftCoords[0]}
								latitude={draftCoords[1]}
								anchor="bottom"
								draggable
								onDragEnd={(e) => {
									if (e?.lngLat) {
										setDraftCoords([e.lngLat.lng, e.lngLat.lat]);
									}
								}}
								style={{ zIndex: 50 }}
							>
								<div
									className="campus-draft-pin"
									title="Kéo để điều chỉnh vị trí"
								>
									<svg
										width="34"
										height="44"
										viewBox="0 0 24 32"
										aria-hidden="true"
									>
										<path
											d="M12 0C5.4 0 0 5.3 0 11.9 0 20.8 12 32 12 32s12-11.2 12-20.1C24 5.3 18.6 0 12 0z"
											fill="#ea4335"
											stroke="#b31412"
											strokeWidth="1"
										/>
										<circle
											cx="12"
											cy="11.5"
											r="4.2"
											fill="#7a0c0c"
										/>
									</svg>
								</div>
							</Marker>
						)}

						{/* Ghim địa điểm toàn cầu vừa tìm kiếm */}
						{searchedPlace && (
							<Marker
								longitude={searchedPlace.coords[0]}
								latitude={searchedPlace.coords[1]}
								anchor="bottom"
								style={{ zIndex: 30 }}
							>
								<div
									className="campus-pin-marker campus-pin-marker--global"
									title={`${searchedPlace.name} - ${searchedPlace.fullAddress}`}
								>
									<span className="campus-pin-marker__label">
										{searchedPlace.name}
									</span>
									<div className="campus-pin-marker__icon-wrap">
										<svg
											className="campus-pin-marker__svg"
											width="24"
											height="32"
											viewBox="0 0 24 32"
											aria-hidden="true"
										>
											<path
												d="M12 0C5.373 0 0 5.373 0 12c0 8.5 12 20 12 20s12-11.5 12-20c0-6.627-5.373-12-12-12z"
												fill="#2563eb"
												stroke="#ffffff"
												strokeWidth="1.5"
											/>
											<circle
												cx="12"
												cy="11"
												r="4"
												fill="#ffffff"
											/>
										</svg>
									</div>
								</div>
							</Marker>
						)}

						{/* Google-Style Real Coordinate Markers with Auto-Shift for Overlaps */}
						{positionedAreas.map((item) => {
							const isSelected = selectedAreaId === item.id;

							return (
								<Marker
									key={item.id}
									longitude={item.baseCoords[0]}
									latitude={item.baseCoords[1]}
									offset={item.pixelOffset}
									anchor="bottom"
									onClick={(e) => {
										stopMapEvent(e);
										handleSelectAreaItem(item.id, item.baseCoords);
									}}
									style={{ zIndex: isSelected ? 50 : 10 + (item.idx || 0) }}
								>
									<div
										className={`campus-pin-marker ${isSelected ? "campus-pin-marker--selected" : ""}`}
										title={`${item.name} (${[item.building, item.floor].filter(Boolean).join(" · ") || ""})${item.isShifted ? " · Đã tự dịch vị trí do trùng toạ độ" : ""}`}
										aria-label={item.name}
									>
										<span className="campus-pin-marker__label">
											{item.name}
										</span>
										<div className="campus-pin-marker__icon-wrap">
											<svg
												className="campus-pin-marker__svg"
												width="24"
												height="32"
												viewBox="0 0 24 32"
												aria-hidden="true"
											>
												<path
													d="M12 0C5.373 0 0 5.373 0 12c0 8.5 12 20 12 20s12-11.5 12-20c0-6.627-5.373-12-12-12z"
													fill={item.color}
													stroke="#ffffff"
													strokeWidth="1.5"
												/>
												<circle
													cx="12"
													cy="11"
													r="4"
													fill="#ffffff"
												/>
											</svg>
										</div>
									</div>
								</Marker>
							);
						})}
					</MapGL>
				</div>

				{/* Floating Colour Legend */}
				<div className="campus-map-legend">
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--public" />
						<span>{AREA_LEVEL_CONFIG.PUBLIC.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--internal" />
						<span>{AREA_LEVEL_CONFIG.INTERNAL_CONFIDENTIAL.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--contact" />
						<span>
							{AREA_LEVEL_CONFIG.CONFIDENTIAL_CONTACT_REQUIRED.badgeLabel}
						</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--private" />
						<span>{AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.badgeLabel}</span>
					</div>
				</div>
			</div>

			{/* RIGHT RAIL: AREAS LIST & DETAILS */}
			<div className="campus-rail">
				{/* Card 1: Area List */}
				<div className="campus-rail-card campus-rail-card--list">
					<div className="campus-rail-header">
						<span className="campus-rail-title">
							Khu vực khuôn viên (
							{searchTerm.trim()
								? `${searchMatches.length}/${areas.length}`
								: areas.length}
							)
						</span>
						{unpositionedCount > 0 && (
							<span
								className="campus-rail-unpositioned-note"
								title="Các khu vực chưa được cập nhật tọa độ GPS trong CSDL"
							>
								{unpositionedCount} chưa định vị
							</span>
						)}
					</div>

					<div className="campus-rail-list">
						{searchMatches.length === 0 ? (
							<div className="campus-detail-empty">
								{areas.length === 0
									? "Chưa có khu vực nào trong hệ thống"
									: "Không có khu vực khớp từ khoá tìm kiếm"}
							</div>
						) : (
							searchMatches.map((area) => {
								const isSelected = selectedAreaId === area.id;
								const levelKey =
									typeof area.areaLevel === "string"
										? area.areaLevel.toLowerCase()
										: (area.level?.code || "PUBLIC").toLowerCase();
								const validCoords = getValidCoordinates(area);
								const pos = positionedAreas.find((p) => p.id === area.id);
								const targetCoords = pos ? pos.baseCoords : validCoords;

								return (
									<div
										key={area.id}
										className={`campus-rail-item ${isSelected ? "campus-rail-item--selected" : ""}`}
										onClick={() => handleSelectAreaItem(area.id, targetCoords)}
									>
										<div className="campus-rail-item__left">
											<span
												className={`campus-level-dot campus-level-dot--${levelKey}`}
											/>
											<span className="campus-rail-item__name">
												{area.name}
											</span>
										</div>

										<div className="campus-rail-item__right">
											{!validCoords ?? (
												<span className="campus-tag-unpositioned">
													Chưa có GPS
												</span>
												/* Do not add level */
											)}
										</div>
									</div>
								);
							})
						)}
					</div>
				</div>

				{/* Card 2: Selected Area Detail */}
				<div
					className={`campus-rail-card campus-rail-card--detail ${!selectedArea ? "campus-rail-card--detail-empty" : ""}`}
				>
					{!selectedArea ? (
						<div className="campus-detail-empty">
							<p>
								Chọn một khu vực trên bản đồ hoặc danh sách để xem chi tiết.
							</p>
						</div>
					) : (
						<div className="campus-detail-content">
							<div className="campus-detail-header">
								<h3 className="campus-detail-title">{selectedArea.name}</h3>
								{(selectedArea.building || selectedArea.floor) && (
									<div className="campus-detail-location">
										{selectedArea.building && (
											<span className="campus-detail-location__item">
												<Building2 size={12} />
												{selectedArea.building}
											</span>
										)}
										{selectedArea.building && selectedArea.floor && (
											<span className="campus-detail-location__sep">·</span>
										)}
										{selectedArea.floor && (
											<span className="campus-detail-location__item">
												<Layers size={12} />
												{selectedArea.floor}
											</span>
										)}
									</div>
								)}
							</div>

							<div className="campus-detail-meta">
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Loại</span>
									<span className="campus-detail-meta-val">
										{
											getLevelConfig(
												selectedArea.areaLevel || selectedArea.level,
											).name
										}
									</span>
								</div>
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Cấp truy cập</span>
									<span
										className="campus-detail-meta-val"
										title={
											selectedArea.explicitAuthorizationRequired
												? "Cấp tối thiểu để được gửi đơn xin vào (không cho vào tự do)"
												: "Cấp tối thiểu để được vào tự do"
										}
									>
										{getAccessLevelConfig(selectedArea.areaAccessLevel).label}
									</span>
								</div>
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Tọa độ GPS</span>
									{selectedArea.centerLatitude != null &&
									selectedArea.centerLongitude != null ? (
										<span className="campus-detail-meta-val campus-detail-meta-val--coords">
											{Number(selectedArea.centerLatitude).toFixed(5)},{" "}
											{Number(selectedArea.centerLongitude).toFixed(5)}
										</span>
									) : (
										<span className="campus-detail-meta-val text-warning">
											Chưa có tọa độ
										</span>
									)}
								</div>
								{/* Do not add camera count */}
							</div>

							{isAdmin && editingAreaId !== selectedArea.id && (
								<button
									type="button"
									id="campus-coord-edit-btn"
									className="campus-coord-btn campus-coord-btn--primary campus-coord-btn--block"
									onClick={handleStartEditCoords}
								>
									<Crosshair size={13} />
									{getValidCoordinates(selectedArea)
										? "Chỉnh vị trí trên bản đồ"
										: "Đặt vị trí trên bản đồ"}
								</button>
							)}
						</div>
					)}
				</div>
			</div>
		</div>
	);
}
