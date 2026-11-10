import { setWorkerUrl } from "maplibre-gl";
import maplibreWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";

if (
	typeof window !== "undefined" &&
	typeof setWorkerUrl === "function" &&
	maplibreWorkerUrl
) {
	setWorkerUrl(maplibreWorkerUrl);
}
