import React, { useState, useMemo, useRef, useCallback } from "react";
import Map, {
	NavigationControl,
	FullscreenControl,
	Marker,
} from "react-map-gl/maplibre";
import "maplibre-gl/dist/maplibre-gl.css";
import {
	Compass,
	Maximize2,
	PanelRightClose,
	PanelRightOpen,
	Building2,
	Layers,
} from "lucide-react";
import {
	getLevelConfig,
	getAccessLevelConfig,
	AREA_LEVEL_CONFIG,
} from "../../utils/areaHelpers";
import "../../styles/CampusMapView.css";

// FPT University HCMC Campus default center (Saigon Hi-Tech Park, District 9)
const CAMPUS_CENTER = {
	longitude: 106.80988,
	latitude: 10.84113,
	zoom: 16.2,
	pitch: 32,
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

// Mức zoom từ đó hiện đủ nhãn khu vực. Toạ độ các khu vực trong cùng toà nhà rất sát nhau:
// ở 17.5 (mức bay tới một khu vực) nhãn vẫn đè nhau, nên chỉ hiện đủ khi gần mức tối đa (18.8);
// dưới ngưỡng này nhãn hiện khi hover hoặc khu vực đang được chọn.
const LABEL_MIN_ZOOM = 18.5;

// Preset GPS marker locations for campus zones and landmarks
const LANDMARK_LOCATIONS = [
	{ matches: ["cổng", "gate"], coords: [106.80922, 10.84175] },
	{ matches: ["hồ sen", "lotus", "hồ"], coords: [106.80973, 10.84105] },
	{ matches: ["thư viện", "library", "lib"], coords: [106.81008, 10.84148] },
	{ matches: ["y tế", "med", "medical"], coords: [106.80952, 10.84165] },
	{
		matches: ["thể thao", "sân bóng", "sport", "gym", "khu_the_thao"],
		coords: [106.81145, 10.8417],
	},
	{ matches: ["alpha"], coords: [106.81015, 10.84165] },
	{ matches: ["beta"], coords: [106.81065, 10.841] },
	{ matches: ["căn tin", "nhà ăn", "canteen"], coords: [106.8109, 10.8413] },
	{ matches: ["bãi xe", "nhà xe", "parking"], coords: [106.8088, 10.8418] },
	{ matches: ["lb01"], coords: [106.8103, 10.8418] },
	{ matches: ["lb02"], coords: [106.81045, 10.84185] },
	{ matches: ["server", "máy chủ"], coords: [106.81025, 10.84155] },
];

const DEFAULT_CAMPUS_MARKERS = {
	"Khu thể thao": [106.81145, 10.8417],
	"Tòa Alpha": [106.81015, 10.84165],
	"Tòa Beta": [106.81065, 10.841],
};

// Calculate approximate centroid of polygon coordinates
function computePolygonCentroid(coords) {
	if (!coords || coords.length === 0)
		return [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude];
	let sumLng = 0;
	let sumLat = 0;
	const count =
		coords.length > 1 && coords[0][0] === coords[coords.length - 1][0]
			? coords.length - 1
			: coords.length;
	for (let i = 0; i < count; i++) {
		sumLng += coords[i][0];
		sumLat += coords[i][1];
	}
	return [sumLng / count, sumLat / count];
}

function getAreaBaseCoords(area) {
	if (
		area.geometry &&
		Array.isArray(area.geometry.coordinates) &&
		area.geometry.coordinates.length > 0
	) {
		return computePolygonCentroid(area.geometry.coordinates[0]);
	}
	if (
		area.geometry &&
		Array.isArray(area.geometry.vertices) &&
		area.geometry.vertices.length >= 3 &&
		area.geometry.vertices[0].x > 1.0
	) {
		const ring = area.geometry.vertices.map((v) => [v.x, v.y]);
		return computePolygonCentroid(ring);
	}

	const nameLower = (area.name || "").toLowerCase().trim();
	const buildingLower = (area.building || "").toLowerCase().trim();

	// 1. Khớp địa danh theo tên khu vực
	for (const lm of LANDMARK_LOCATIONS) {
		if (lm.matches.some((m) => nameLower.includes(m))) {
			return lm.coords;
		}
	}

	// 2. Khớp địa danh theo toà nhà
	for (const lm of LANDMARK_LOCATIONS) {
		if (lm.matches.some((m) => buildingLower.includes(m))) {
			return lm.coords;
		}
	}

	if (area.building && DEFAULT_CAMPUS_MARKERS[area.building]) {
		return DEFAULT_CAMPUS_MARKERS[area.building];
	}

	return [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude];
}

export default function CampusMapView({
	areas = [],
	selectedAreaId,
	cameraCounts = {},
	onSelectArea,
}) {
	const mapRef = useRef(null);
	const [railCollapsed, setRailCollapsed] = useState(false);
	// UI-B B4: dưới mức zoom này các khu vực quá sát nhau -> chỉ hiện chấm, nhãn hiện khi hover/được chọn
	const [mapZoom, setMapZoom] = useState(CAMPUS_CENTER.zoom);
	const compactLabels = mapZoom < LABEL_MIN_ZOOM;

	// Selected area object
	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	// Marker list for all areas, with de-overlapping (radial spread) for coincident coordinates
	const areaMarkers = useMemo(() => {
		const rawMarkers = areas.map((area) => {
			const levelConfig = getLevelConfig(area.areaLevel || area.level);
			const baseCoords = getAreaBaseCoords(area);
			return {
				id: area.id,
				name: area.name,
				building: area.building,
				color: levelConfig.color || "#3b82f6",
				baseCoords,
			};
		});

		// Nhóm các marker có toạ độ trùng hoặc gần trùng nhau (~1-2m)
		const groups = {};
		rawMarkers.forEach((m) => {
			const key = `${m.baseCoords[0].toFixed(5)},${m.baseCoords[1].toFixed(5)}`;
			if (!groups[key]) groups[key] = [];
			groups[key].push(m);
		});

		// Tự động phân tán các marker cùng toạ độ theo vòng tròn (spiderfier) để không bị overlay đè lên nhau
		const SPREAD_RADIUS = 0.00018; // ~18-20 mét, cách nhau rõ ràng trên bản đồ
		const result = [];

		Object.values(groups).forEach((group) => {
			if (group.length === 1) {
				result.push({
					...group[0],
					coords: group[0].baseCoords,
				});
			} else {
				const n = group.length;
				group.forEach((m, idx) => {
					const angle = (2 * Math.PI * idx) / n;
					const lngOffset = SPREAD_RADIUS * Math.cos(angle);
					const latOffset = SPREAD_RADIUS * Math.sin(angle) * 0.85;
					result.push({
						...m,
						coords: [m.baseCoords[0] + lngOffset, m.baseCoords[1] + latOffset],
					});
				});
			}
		});

		return result;
	}, [areas]);

	// Jump to campus center
	const handleResetCampusView = useCallback(() => {
		if (mapRef.current) {
			mapRef.current.flyTo({
				center: [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude],
				zoom: CAMPUS_CENTER.zoom,
				pitch: CAMPUS_CENTER.pitch,
				bearing: CAMPUS_CENTER.bearing,
				duration: 1200,
			});
		}
	}, []);

	// Jump to specific area
	const handleFlyToArea = useCallback(
		(areaId) => {
			const marker = areaMarkers.find((m) => m.id === areaId);
			if (marker && mapRef.current) {
				mapRef.current.flyTo({
					center: marker.coords,
					zoom: 17.5,
					duration: 900,
				});
			}
			if (onSelectArea) {
				onSelectArea(areaId);
			}
		},
		[areaMarkers, onSelectArea],
	);

	return (
		<div
			className={`campus-map-layout ${railCollapsed ? "campus-map-layout--expanded" : ""}`}
		>
			{/* LEFT: MAIN MAPLIBRE CANVAS */}
			<div className="campus-map-card">
				{/* Floating Top Controls */}
				<div className="campus-map-floating-bar">
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

				{/* Map Viewport */}
				<div
					className={`campus-map-viewport ${compactLabels ? "campus-map-viewport--compact" : ""}`}
				>
					<Map
						ref={mapRef}
						initialViewState={CAMPUS_CENTER}
						onZoomEnd={(e) => setMapZoom(e.viewState.zoom)}
						mapStyle={OSM_STYLE}
						style={{ width: "100%", height: "100%" }}
						minZoom={12}
						maxZoom={18.8}
					>
						{/* Native Map Controls */}
						<NavigationControl
							position="top-right"
							showCompass
							showZoom
						/>
						<FullscreenControl position="top-right" />

						{/* Outdoor Area Markers */}
						{areaMarkers.map((item) => {
							const isSelected = selectedAreaId === item.id;
							return (
								<Marker
									key={item.id}
									longitude={item.coords[0]}
									latitude={item.coords[1]}
									anchor="center"
									onClick={(e) => {
										e.originalEvent.stopPropagation();
										handleFlyToArea(item.id);
									}}
								>
									<div
										className={`campus-zone-pin ${isSelected ? "campus-zone-pin--selected" : ""}`}
										style={{ borderColor: item.color }}
										title={item.name}
										aria-label={item.name}
									>
										<span
											className="campus-zone-pin__dot"
											style={{ backgroundColor: item.color }}
										/>
										<span className="campus-zone-pin__label">{item.name}</span>
									</div>
								</Marker>
							);
						})}
					</Map>
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
							Khu vực khuôn viên ({areas.length})
						</span>
					</div>

					<div className="campus-rail-list">
						{areas.length === 0 ? (
							<div className="campus-detail-empty">
								Chưa có khu vực nào trong hệ thống
							</div>
						) : (
							areas.map((area) => {
								const isSelected = selectedAreaId === area.id;
								const levelKey =
									typeof area.areaLevel === "string"
										? area.areaLevel.toLowerCase()
										: (area.level?.code || "PUBLIC").toLowerCase();

								return (
									<div
										key={area.id}
										className={`campus-rail-item ${isSelected ? "campus-rail-item--selected" : ""}`}
										onClick={() => handleFlyToArea(area.id)}
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
											<span
												className={
													getAccessLevelConfig(area.areaAccessLevel).className
												}
												title={
													area.explicitAuthorizationRequired
														? "Cấp tối thiểu để được gửi đơn xin vào (không cho vào tự do)"
														: "Cấp tối thiểu để được vào tự do"
												}
											>
												{getAccessLevelConfig(area.areaAccessLevel).label}
											</span>
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
								{cameraCounts[selectedArea.id] !== undefined && (
									<div className="campus-detail-meta-row">
										<span className="campus-detail-meta-label">Camera</span>
										<span className="campus-detail-meta-val">
											{cameraCounts[selectedArea.id]}
										</span>
									</div>
								)}
							</div>
						</div>
					)}
				</div>
			</div>
		</div>
	);
}
