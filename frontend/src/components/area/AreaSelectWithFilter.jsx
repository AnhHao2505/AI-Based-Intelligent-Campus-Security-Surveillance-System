import React, { useMemo, useState, useEffect, useCallback } from "react";
import { Filter, RotateCcw } from "lucide-react";
import Select from "../ui/Select";
import {
	formatBuilding,
	formatFloor,
	formatLocation,
} from "../../utils/formatLocation";
import {
	countRestrictedAreasByLevel,
	extractAreaLevelKey,
} from "../../utils/campusCameraHelpers";
import "../../styles/AreaSelectWithFilter.css";

function getFloorSortWeight(floorLabel) {
	const lower = floorLabel.toLowerCase();
	if (lower.includes("trệt") || lower === "g") return 0;
	if (lower.includes("hầm") || /^b\d+$/i.test(lower)) {
		const num = parseInt(lower.replace(/\D/g, ""), 10);
		return Number.isFinite(num) ? -num : -1;
	}
	const numMatch = lower.match(/\d+/);
	if (numMatch) {
		return parseInt(numMatch[0], 10);
	}
	return 10000;
}

function compareFloors(a, b) {
	const wa = getFloorSortWeight(a);
	const wb = getFloorSortWeight(b);
	if (wa !== wb) return wa - wb;
	return a.localeCompare(b, "vi");
}

function getLevelShortTag(area) {
	const key = extractAreaLevelKey(area);
	if (key === "HIGHLY_CONFIDENTIAL") return "[Cấp 3]";
	if (key === "CONFIDENTIAL") return "[Cấp 2]";
	if (key === "PUBLIC") return "[Cấp 1]";
	return "";
}

/**
 * AreaSelectWithFilter — Bộ chọn khu vực thống nhất cho Create & Edit:
 * - Segmented pill lọc Cấp độ (khi showLevelFilter=true) kèm số lượng thực tế
 * - Thanh "Lọc nhanh" Tòa & Tầng tách biệt thị giác với ô chọn Khu vực chính
 * - Nhóm danh sách phòng theo <optgroup> Tòa · Tầng để quét nhanh, không lặp tiền tố
 */
export default function AreaSelectWithFilter({
	areas = [],
	value = "",
	onChange,
	id,
	name,
	placeholder = "-- Chọn khu vực --",
	placeholderDisabled = false,
	disabled = false,
	required = false,
	className = "",
	error,
	hint,
	defaultBuilding = "ALL",
	defaultFloor = "ALL",
	onFilterChange = null,
	showLevelFilter = false,
	levelFilter: controlledLevelFilter,
	onLevelFilterChange = null,
	showLevelTag = false,
	groupByLocation = true,
}) {
	const [buildingFilter, setBuildingFilter] = useState(() => {
		if (!defaultBuilding || defaultBuilding === "ALL") return "ALL";
		return formatBuilding(defaultBuilding) || defaultBuilding;
	});
	const [floorFilter, setFloorFilter] = useState(() => {
		if (!defaultFloor || defaultFloor === "ALL") return "ALL";
		return formatFloor(defaultFloor) || defaultFloor;
	});
	const [internalLevelFilter, setInternalLevelFilter] = useState("ALL");

	const activeLevelFilter =
		controlledLevelFilter !== undefined ? controlledLevelFilter : internalLevelFilter;

	const levelCounts = useMemo(
		() => (showLevelFilter ? countRestrictedAreasByLevel(areas) : null),
		[areas, showLevelFilter],
	);

	const normalizedAreas = useMemo(
		() =>
			areas.map((area) => ({
				area,
				levelKey: extractAreaLevelKey(area),
				buildingLabel: formatBuilding(area.building),
				floorLabel: formatFloor(area.floor),
			})),
		[areas],
	);

	const buildingList = useMemo(() => {
		const set = new Set();
		for (const item of normalizedAreas) {
			if (item.buildingLabel) set.add(item.buildingLabel);
		}
		return Array.from(set).sort((a, b) => a.localeCompare(b, "vi"));
	}, [normalizedAreas]);

	const floorListForAll = useMemo(() => {
		const set = new Set();
		for (const item of normalizedAreas) {
			if (item.floorLabel) set.add(item.floorLabel);
		}
		return Array.from(set).sort(compareFloors);
	}, [normalizedAreas]);

	const floorListForBuilding = useMemo(() => {
		const set = new Set();
		for (const item of normalizedAreas) {
			if (buildingFilter !== "ALL" && item.buildingLabel !== buildingFilter) {
				continue;
			}
			if (item.floorLabel) set.add(item.floorLabel);
		}
		return Array.from(set).sort(compareFloors);
	}, [normalizedAreas, buildingFilter]);

	useEffect(() => {
		if (buildingFilter !== "ALL" && !buildingList.includes(buildingFilter)) {
			setBuildingFilter("ALL");
		}
	}, [buildingFilter, buildingList]);

	useEffect(() => {
		if (floorFilter !== "ALL" && !floorListForBuilding.includes(floorFilter)) {
			setFloorFilter("ALL");
		}
	}, [floorFilter, floorListForBuilding]);

	useEffect(() => {
		if (defaultBuilding && defaultBuilding !== "ALL") {
			const formatted = formatBuilding(defaultBuilding) || defaultBuilding;
			if (buildingList.includes(formatted)) {
				setBuildingFilter(formatted);
			}
		}
	}, [defaultBuilding, buildingList]);

	useEffect(() => {
		if (defaultFloor && defaultFloor !== "ALL") {
			const formatted = formatFloor(defaultFloor) || defaultFloor;
			if (floorListForBuilding.includes(formatted)) {
				setFloorFilter(formatted);
			}
		}
	}, [defaultFloor, floorListForBuilding]);

	const showBuildingFilter = buildingList.length >= 2;
	const showFloorFilter = floorListForAll.length >= 2;
	const showFilterBar = showBuildingFilter || showFloorFilter;
	const isSingleFilter =
		(showBuildingFilter && !showFloorFilter) ||
		(!showBuildingFilter && showFloorFilter);

	const filteredItems = useMemo(
		() =>
			normalizedAreas
				.filter((item) => {
					if (
						showLevelFilter &&
						activeLevelFilter !== "ALL" &&
						item.levelKey !== activeLevelFilter
					) {
						return false;
					}
					if (
						buildingFilter !== "ALL" &&
						item.buildingLabel !== buildingFilter
					) {
						return false;
					}
					if (floorFilter !== "ALL" && item.floorLabel !== floorFilter) {
						return false;
					}
					return true;
				})
				.sort((a, b) => {
					const bCmp = a.buildingLabel.localeCompare(b.buildingLabel, "vi");
					if (bCmp !== 0) return bCmp;
					const fCmp = compareFloors(a.floorLabel, b.floorLabel);
					if (fCmp !== 0) return fCmp;
					return (a.area.name || "").localeCompare(b.area.name || "", "vi");
				}),
		[normalizedAreas, showLevelFilter, activeLevelFilter, buildingFilter, floorFilter],
	);

	const formatFlatLabel = useCallback(
		(item) => {
			const { area, buildingLabel, floorLabel } = item;
			const tag = showLevelTag ? getLevelShortTag(area) : "";
			const prefix = tag ? `${tag} ` : "";
			const isBuildingActive = buildingFilter !== "ALL";
			const isFloorActive = floorFilter !== "ALL";

			if (isBuildingActive && isFloorActive) {
				return `${prefix}${area.name}`;
			}
			if (isBuildingActive && !isFloorActive) {
				return floorLabel ? `${prefix}${floorLabel} — ${area.name}` : `${prefix}${area.name}`;
			}
			if (!isBuildingActive && isFloorActive) {
				return buildingLabel
					? `${prefix}${buildingLabel} — ${area.name}`
					: `${prefix}${area.name}`;
			}
			const loc = formatLocation(area.building, area.floor, " · ");
			return loc ? `${prefix}${loc} — ${area.name}` : `${prefix}${area.name}`;
		},
		[buildingFilter, floorFilter, showLevelTag],
	);

	const areaOptions = useMemo(() => {
		const hasValue = value !== "" && value !== null && value !== undefined;
		const emptyListPlaceholder =
			filteredItems.length === 0
				? "Không có phòng phù hợp bộ lọc"
				: placeholder;

		const opts = [
			{
				value: "",
				label: emptyListPlaceholder,
				disabled: placeholderDisabled,
			},
		];

		const valueInFiltered =
			hasValue &&
			filteredItems.some((item) => String(item.area.id) === String(value));

		if (hasValue && !valueInFiltered) {
			const currentItem = normalizedAreas.find(
				(item) => String(item.area.id) === String(value),
			);
			if (currentItem) {
				const tag = showLevelTag ? getLevelShortTag(currentItem.area) : "";
				const loc = formatLocation(
					currentItem.area.building,
					currentItem.area.floor,
					" · ",
				);
				opts.push({
					value: currentItem.area.id,
					label: `Đang chọn: ${tag ? `${tag} ` : ""}${loc ? `${loc} — ` : ""}${currentItem.area.name}`,
				});
			}
		}

		if (groupByLocation && filteredItems.length > 0) {
			const groupsMap = new Map();
			for (const item of filteredItems) {
				const groupLabel =
					formatLocation(item.area.building, item.area.floor, " · ") ||
					"Khu vực khác";
				if (!groupsMap.has(groupLabel)) {
					groupsMap.set(groupLabel, []);
				}
				const tag = showLevelTag ? getLevelShortTag(item.area) : "";
				groupsMap.get(groupLabel).push({
					value: item.area.id,
					label: tag ? `${tag} ${item.area.name}` : item.area.name,
				});
			}
			for (const [label, children] of groupsMap.entries()) {
				opts.push({ label, options: children });
			}
		} else {
			for (const item of filteredItems) {
				opts.push({
					value: item.area.id,
					label: formatFlatLabel(item),
				});
			}
		}

		return opts;
	}, [
		filteredItems,
		normalizedAreas,
		placeholder,
		placeholderDisabled,
		value,
		formatFlatLabel,
		groupByLocation,
		showLevelTag,
	]);

	const hasActiveFilter =
		buildingFilter !== "ALL" ||
		floorFilter !== "ALL" ||
		(showLevelFilter && activeLevelFilter !== "ALL");

	const handleLevelChange = (nextLevel) => {
		if (controlledLevelFilter === undefined) {
			setInternalLevelFilter(nextLevel);
		}
		onLevelFilterChange?.(nextLevel);
	};

	const handleBuildingChange = (e) => {
		const nextBuilding = e.target.value;
		setBuildingFilter(nextBuilding);
		onFilterChange?.({ building: nextBuilding, floor: floorFilter });
	};

	const handleFloorChange = (e) => {
		const nextFloor = e.target.value;
		setFloorFilter(nextFloor);
		onFilterChange?.({ building: buildingFilter, floor: nextFloor });
	};

	const handleResetFilters = () => {
		setBuildingFilter("ALL");
		setFloorFilter("ALL");
		if (showLevelFilter) {
			handleLevelChange("ALL");
		}
		onFilterChange?.({ building: "ALL", floor: "ALL" });
	};

	return (
		<div className={`area-select-filter ${className}`.trim()}>
			{showLevelFilter && (
				<div
					className="area-level-segmented"
					role="group"
					aria-label="Lọc cấp độ phòng hạn chế"
				>
					{[
						{
							value: "ALL",
							label: `Tất cả (${levelCounts?.ALL ?? areas.length})`,
						},
						{
							value: "CONFIDENTIAL",
							label: `Cấp 2 (${levelCounts?.CONFIDENTIAL ?? 0})`,
							title: "Cấp 2 · Bảo mật",
						},
						{
							value: "HIGHLY_CONFIDENTIAL",
							label: `Cấp 3 (${levelCounts?.HIGHLY_CONFIDENTIAL ?? 0})`,
							title: "Cấp 3 · Bảo mật cao",
						},
					].map((opt) => (
						<button
							key={opt.value}
							type="button"
							title={opt.title || opt.label}
							className={`area-level-segmented__btn ${
								activeLevelFilter === opt.value
									? "area-level-segmented__btn--active"
									: ""
							}`}
							aria-pressed={activeLevelFilter === opt.value}
							onClick={() => handleLevelChange(opt.value)}
							disabled={disabled}
						>
							{opt.label}
						</button>
					))}
				</div>
			)}

			{(showFilterBar || hasActiveFilter) && (
				<div className="area-select-filter__toolbar">
					{showFilterBar && (
						<div className="area-select-filter__bar-wrap">
							<span className="area-select-filter__bar-label">
								<Filter size={11} />
								<span>Lọc:</span>
							</span>
							<div
								className={`area-select-filter__bar ${isSingleFilter ? "area-select-filter__bar--single" : ""}`}
							>
								{showBuildingFilter && (
									<Select
										className={`area-select-filter__sub ${buildingFilter !== "ALL" ? "area-select-filter__sub--active" : ""}`}
										aria-label="Lọc theo tòa nhà"
										value={buildingFilter}
										onChange={handleBuildingChange}
										disabled={disabled}
										options={[
											{ value: "ALL", label: "Mọi tòa nhà" },
											...buildingList.map((b) => ({
												value: b,
												label: b,
											})),
										]}
									/>
								)}

								{showFloorFilter && (
									<Select
										className={`area-select-filter__sub ${floorFilter !== "ALL" ? "area-select-filter__sub--active" : ""}`}
										aria-label="Lọc theo tầng"
										value={floorFilter}
										onChange={handleFloorChange}
										disabled={disabled}
										options={[
											{ value: "ALL", label: "Mọi tầng" },
											...floorListForBuilding.map((f) => ({
												value: f,
												label: f,
											})),
										]}
									/>
								)}
							</div>
						</div>
					)}

					<div className="area-select-filter__meta">
						<span className="area-select-filter__count">
							{filteredItems.length}/{areas.length} phòng phù hợp
						</span>
						{hasActiveFilter && (
							<button
								type="button"
								className="area-select-filter__reset"
								onClick={handleResetFilters}
								disabled={disabled}
							>
								<RotateCcw size={11} />
								<span>Xóa lọc</span>
							</button>
						)}
					</div>
				</div>
			)}

			<Select
				id={id}
				name={name}
				className="area-select-filter__primary"
				value={value}
				onChange={onChange}
				disabled={disabled}
				required={required}
				error={error}
				hint={hint}
				options={areaOptions}
			/>
		</div>
	);
}
