import React, { useMemo, useState, useEffect, useCallback } from "react";
import { RotateCcw } from "lucide-react";
import Select from "../ui/Select";
import {
	formatBuilding,
	formatFloor,
	formatLocation,
} from "../../utils/formatLocation";
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

/**
 * AreaSelectWithFilter — Select khu vực có bộ lọc Tòa nhà & Tầng gọn gàng phía trên.
 * Tự động ẩn thanh lọc khi danh sách chỉ có 1 tòa và 1 tầng.
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
}) {
	const [buildingFilter, setBuildingFilter] = useState("ALL");
	const [floorFilter, setFloorFilter] = useState("ALL");

	const normalizedAreas = useMemo(
		() =>
			areas.map((area) => ({
				area,
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
			if (
				buildingFilter !== "ALL" &&
				item.buildingLabel !== buildingFilter
			) {
				continue;
			}
			if (item.floorLabel) set.add(item.floorLabel);
		}
		return Array.from(set).sort(compareFloors);
	}, [normalizedAreas, buildingFilter]);

	// Đồng bộ khi danh sách areas thay đổi (VD: đổi tab Cá nhân <-> Nhóm)
	useEffect(() => {
		if (
			buildingFilter !== "ALL" &&
			!buildingList.includes(buildingFilter)
		) {
			setBuildingFilter("ALL");
		}
	}, [buildingFilter, buildingList]);

	useEffect(() => {
		if (
			floorFilter !== "ALL" &&
			!floorListForBuilding.includes(floorFilter)
		) {
			setFloorFilter("ALL");
		}
	}, [floorFilter, floorListForBuilding]);

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
						buildingFilter !== "ALL" &&
						item.buildingLabel !== buildingFilter
					) {
						return false;
					}
					if (
						floorFilter !== "ALL" &&
						item.floorLabel !== floorFilter
					) {
						return false;
					}
					return true;
				})
				.sort((a, b) => {
					const bCmp = a.buildingLabel.localeCompare(
						b.buildingLabel,
						"vi",
					);
					if (bCmp !== 0) return bCmp;
					const fCmp = compareFloors(a.floorLabel, b.floorLabel);
					if (fCmp !== 0) return fCmp;
					return (a.area.name || "").localeCompare(
						b.area.name || "",
						"vi",
					);
				}),
		[normalizedAreas, buildingFilter, floorFilter],
	);

	const formatOptionLabel = useCallback(
		(item) => {
			const { area, buildingLabel, floorLabel } = item;
			const isBuildingActive = buildingFilter !== "ALL";
			const isFloorActive = floorFilter !== "ALL";

			if (isBuildingActive && isFloorActive) {
				return area.name;
			}
			if (isBuildingActive && !isFloorActive) {
				return floorLabel ? `${floorLabel} — ${area.name}` : area.name;
			}
			if (!isBuildingActive && isFloorActive) {
				return buildingLabel
					? `${buildingLabel} — ${area.name}`
					: area.name;
			}
			const loc = formatLocation(area.building, area.floor, " · ");
			return loc ? `${loc} — ${area.name}` : area.name;
		},
		[buildingFilter, floorFilter],
	);

	const areaOptions = useMemo(() => {
		const opts = [
			{
				value: "",
				label: placeholder,
				disabled: placeholderDisabled,
			},
		];

		const hasValue =
			value !== "" && value !== null && value !== undefined;
		const valueInFiltered =
			hasValue &&
			filteredItems.some(
				(item) => String(item.area.id) === String(value),
			);

		if (hasValue && !valueInFiltered) {
			const currentItem = normalizedAreas.find(
				(item) => String(item.area.id) === String(value),
			);
			if (currentItem) {
				const loc = formatLocation(
					currentItem.area.building,
					currentItem.area.floor,
					" · ",
				);
				opts.push({
					value: currentItem.area.id,
					label: `Đang chọn: ${loc ? `${loc} — ` : ""}${currentItem.area.name}`,
				});
			}
		}

		for (const item of filteredItems) {
			opts.push({
				value: item.area.id,
				label: formatOptionLabel(item),
			});
		}

		return opts;
	}, [
		filteredItems,
		normalizedAreas,
		placeholder,
		placeholderDisabled,
		value,
		formatOptionLabel,
	]);

	const hasActiveFilter =
		buildingFilter !== "ALL" || floorFilter !== "ALL";

	const handleBuildingChange = (e) => {
		setBuildingFilter(e.target.value);
	};

	const handleFloorChange = (e) => {
		setFloorFilter(e.target.value);
	};

	const handleResetFilters = () => {
		setBuildingFilter("ALL");
		setFloorFilter("ALL");
	};

	return (
		<div className={`area-select-filter ${className}`.trim()}>
			{showFilterBar && (
				<>
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
									{ value: "ALL", label: "Tất cả tòa" },
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
									{ value: "ALL", label: "Tất cả tầng" },
									...floorListForBuilding.map((f) => ({
										value: f,
										label: f,
									})),
								]}
							/>
						)}
					</div>

					{hasActiveFilter && (
						<div className="area-select-filter__meta">
							<span className="area-select-filter__count">
								Hiển thị {filteredItems.length}/{areas.length}{" "}
								khu vực
							</span>
							<button
								type="button"
								className="area-select-filter__reset"
								onClick={handleResetFilters}
								disabled={disabled}
							>
								<RotateCcw size={12} />
								<span>Xóa lọc</span>
							</button>
						</div>
					)}
				</>
			)}

			<Select
				id={id}
				name={name}
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
