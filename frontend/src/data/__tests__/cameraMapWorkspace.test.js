import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
	buildCameraMapPinGeoJson,
	buildCameraPlacementsMap,
	extractCameraMapPlacement,
	projectEventToFloorLngLat,
	resolveCameraPinStatusKey,
} from "../../utils/cameraMapGeometry.js";

describe("cameraMapWorkspace (API coordinates & 3D floor ray projection)", () => {
	it("resolves pin status keys for active online, active offline, and decommissioned cameras", () => {
		assert.equal(
			resolveCameraPinStatusKey({
				status: "ACTIVE",
				operationalStatus: "ONLINE",
			}),
			"ok",
		);
		assert.equal(
			resolveCameraPinStatusKey({
				status: "ACTIVE",
				operationalStatus: "OFFLINE",
			}),
			"warn",
		);
		assert.equal(
			resolveCameraPinStatusKey({
				status: "DECOMMISSIONED",
				operationalStatus: "ONLINE",
			}),
			"muted",
		);
	});

	it("extracts placements from API cameras and merges optimistic drag/delete overrides", () => {
		const cameras = [
			{
				id: "cam-1",
				cameraCode: "CAM-019",
				name: "ADAD",
				status: "ACTIVE",
				operationalStatus: "ONLINE",
				mapLongitude: 106.8098,
				mapLatitude: 10.8411,
				mapBuilding: "Tòa Epsilon",
				mapFloorOrder: 2,
			},
			{
				id: "cam-2",
				cameraCode: "CAM-020",
				name: "No Pin",
				status: "ACTIVE",
				operationalStatus: "OFFLINE",
				mapLongitude: null,
				mapLatitude: null,
			},
		];

		assert.deepEqual(extractCameraMapPlacement(cameras[0]), {
			cameraId: "cam-1",
			lng: 106.8098,
			lat: 10.8411,
			building: "Tòa Epsilon",
			floorOrder: 2,
		});
		assert.equal(extractCameraMapPlacement(cameras[1]), null);

		const merged = buildCameraPlacementsMap(cameras, {
			"cam-1": {
				cameraId: "cam-1",
				lng: 106.81,
				lat: 10.842,
				building: "Tòa Epsilon",
				floorOrder: 2,
			},
		});
		assert.equal(merged["cam-1"].lng, 106.81);

		const removed = buildCameraPlacementsMap(cameras, { "cam-1": null });
		assert.equal(removed["cam-1"], undefined);
	});

	it("builds GeoJSON pinFeatures directly from API camera coordinates filtered by building & floor", () => {
		const cameras = [
			{
				id: "cam-1",
				cameraCode: "CAM-001",
				name: "Hallway 1",
				status: "ACTIVE",
				operationalStatus: "ONLINE",
				deletedAt: null,
				mapLongitude: 106.8098,
				mapLatitude: 10.8411,
				mapBuilding: "Tòa Epsilon",
				mapFloorOrder: 3,
			},
			{
				id: "cam-2",
				cameraCode: "CAM-002",
				name: "Room 2",
				status: "ACTIVE",
				operationalStatus: "OFFLINE",
				deletedAt: null,
				mapLongitude: 106.8099,
				mapLatitude: 10.8412,
				mapBuilding: "Tòa Epsilon",
				mapFloorOrder: 2,
			},
			{
				id: "cam-deleted",
				cameraCode: "CAM-999",
				name: "Deleted",
				status: "DECOMMISSIONED",
				operationalStatus: "OFFLINE",
				deletedAt: "2026-11-10T00:00:00Z",
				mapLongitude: 106.81,
				mapLatitude: 10.8413,
				mapBuilding: "Tòa Epsilon",
				mapFloorOrder: 3,
			},
		];

		const floor3 = buildCameraMapPinGeoJson({
			cameras,
			building: "Epsilon",
			floorOrder: 3,
			selectedCameraId: "cam-1",
		});

		assert.equal(floor3.pinFeatures.length, 1);
		assert.equal(floor3.pinFeatures[0].properties.cameraId, "cam-1");
		assert.equal(floor3.pinFeatures[0].properties.isSelected, 1);
		assert.equal(floor3.pinFeatures[0].properties.spriteId, "cam-pin::ok:1");
		assert.ok(floor3.pinFeatures[0].properties.pinElevation > 10);
	});

	it("projects pitched 3D mouse events onto elevated floor planes instead of z=0 ground", () => {
		// Mock MercatorCoordinate: linear mapping where z = altitudeMeters * 1e-6
		class MockMercatorCoordinate {
			constructor(x, y, z = 0) {
				this.x = x;
				this.y = y;
				this.z = z;
			}
			static fromLngLat(lngLat, altitude = 0) {
				return new MockMercatorCoordinate(
					lngLat.lng,
					lngLat.lat,
					altitude * 1e-6,
				);
			}
			toLngLat() {
				return { lng: this.x, lat: this.y };
			}
		}

		// Camera sits South (y=10.840) looking North toward ground hit (y=10.842) at z=0
		const mockMap = {
			getFreeCameraOptions: () => ({
				position: new MockMercatorCoordinate(106.81, 10.84, 100 * 1e-6),
			}),
		};
		const evt = { lngLat: { lng: 106.812, lat: 10.842 } };

		// Ground plane (z=0): returns event lngLat unchanged
		const ground = projectEventToFloorLngLat(
			mockMap,
			evt,
			0,
			MockMercatorCoordinate,
		);
		assert.deepEqual(ground, { lng: 106.812, lat: 10.842 });

		// Elevated floor at 10m (10% of camera altitude 100m): ray intersects at t=0.9
		const floor10m = projectEventToFloorLngLat(
			mockMap,
			evt,
			10,
			MockMercatorCoordinate,
		);
		assert.ok(Math.abs(floor10m.lng - (106.81 + 0.9 * 0.002)) < 1e-9);
		assert.ok(Math.abs(floor10m.lat - (10.84 + 0.9 * 0.002)) < 1e-9);

		// MapLibre GL v6 path: uses map.transform.screenPointToLocationAtElevation
		const maplibreV6Mock = {
			transform: {
				screenPointToLocationAtElevation: (pt, elev) => ({
					lng: 106.811 + pt.x * 0.0001,
					lat: 10.8405 + elev * 0.00001,
				}),
			},
		};
		const mlResult = projectEventToFloorLngLat(
			maplibreV6Mock,
			{ point: { x: 20, y: 40 }, lngLat: { lng: 106.81, lat: 10.84 } },
			10.98,
		);
		assert.ok(Math.abs(mlResult.lng - (106.811 + 20 * 0.0001)) < 1e-9);
		assert.ok(Math.abs(mlResult.lat - (10.8405 + 10.98 * 0.00001)) < 1e-9);
	});
});
