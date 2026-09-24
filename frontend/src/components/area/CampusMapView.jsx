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
} from "lucide-react";
import { getLevelConfig, AREA_LEVEL_CONFIG } from "../../utils/areaHelpers";
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

// Preset GPS marker locations for campus zones
const DEFAULT_CAMPUS_MARKERS = {
	"FPTA-G-GATE": [106.80922, 10.84175],
	"FPTA-G-LOTUS": [106.80973, 10.84105],
	KHU_THE_THAO: [106.81145, 10.8417],
	TOA_ALPHA: [106.81015, 10.84165],
	TOA_BETA: [106.81065, 10.8410],
	"FPTA-G-LIB": [106.81008, 10.84148],
	"FPTA-G-MED": [106.80952, 10.84165],
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

function getAreaMarkerCoords(area) {
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
	if (DEFAULT_CAMPUS_MARKERS[area.code]) {
		return DEFAULT_CAMPUS_MARKERS[area.code];
	}
	if (DEFAULT_CAMPUS_MARKERS[area.building]) {
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

	// Selected area object
	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	// Marker list for all areas
	const areaMarkers = useMemo(() => {
		return areas.map((area) => {
			const levelConfig = getLevelConfig(area.areaLevel || area.level);
			const coords = getAreaMarkerCoords(area);
			return {
				id: area.id,
				name: area.name,
				code: area.code,
				color: levelConfig.color || "#3b82f6",
				coords: coords,
			};
		});
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
					<div className="campus-map-badge">
						<Compass size={13} />
						<span>Bản đồ Khuôn viên FPT</span>
					</div>

					<button
						type="button"
						className="campus-map-btn-icon"
						onClick={handleResetCampusView}
						title="Quay lại trung tâm Khuôn viên FPT"
					>
						<Maximize2 size={14} />
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

				{/* Map Viewport */}
				<div className="campus-map-viewport">
					<Map
						ref={mapRef}
						initialViewState={CAMPUS_CENTER}
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
							Khu vực Khuôn viên ({areas.length})
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
												className="zone-card__pill-level"
												style={{ fontSize: "10px", padding: "1px 5px" }}
											>
												Level {area.areaAccessLevel ?? 1}
											</span>
										</div>
									</div>
								);
							})
						)}
					</div>
				</div>

				{/* Card 2: Selected Area Detail */}
				<div className="campus-rail-card campus-rail-card--detail">
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
								<span className="zone-detail-code">{selectedArea.code}</span>
							</div>

							<div className="campus-detail-meta">
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Mức an ninh</span>
									<span className="campus-detail-meta-val">
										{
											getLevelConfig(
												selectedArea.areaLevel || selectedArea.level?.code,
											).name
										}
									</span>
								</div>

								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">
										Cấp truy cập tối thiểu
									</span>
									<span className="campus-detail-meta-val">
										Level {selectedArea.areaAccessLevel ?? 1}
									</span>
								</div>

								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">
										Camera giám sát
									</span>
									<span className="campus-detail-meta-val">
										{cameraCounts[selectedArea.id] !== undefined
											? `${cameraCounts[selectedArea.id]} camera`
											: "..."}
									</span>
								</div>
							</div>
						</div>
					)}
				</div>
			</div>
		</div>
	);
}
