const STORAGE_KEY_PREFIX = "headbar_tab_order";

/**
 * Builds the localStorage key used to persist tab ordering for a user/role.
 *
 * @param {string|null|undefined} userId
 * @param {string|null|undefined} role
 * @returns {string}
 */
export function buildHeadbarStorageKey(userId, role) {
	const scope = userId || role || "default";
	return `${STORAGE_KEY_PREFIX}:${scope}`;
}

/**
 * Reads a persisted tab key array from storage safely.
 *
 * @param {string} storageKey
 * @param {Storage|null|undefined} storage
 * @returns {string[]}
 */
export function readStoredTabOrder(storageKey, storage = typeof localStorage !== "undefined" ? localStorage : null) {
	if (!storage || !storageKey) return [];
	try {
		const raw = storage.getItem(storageKey);
		if (!raw) return [];
		const parsed = JSON.parse(raw);
		return Array.isArray(parsed) ? parsed.filter((item) => typeof item === "string") : [];
	} catch {
		return [];
	}
}

/**
 * Writes a tab key array to storage safely.
 *
 * @param {string} storageKey
 * @param {string[]} keys
 * @param {Storage|null|undefined} storage
 */
export function writeStoredTabOrder(storageKey, keys, storage = typeof localStorage !== "undefined" ? localStorage : null) {
	if (!storage || !storageKey || !Array.isArray(storage?.setItem ? keys : null)) return;
	try {
		storage.setItem(storageKey, JSON.stringify(keys));
	} catch {
		// Ignore quota or restricted storage errors
	}
}

/**
 * Orders navigation items according to a saved list of route keys (`to`),
 * filtering out obsolete keys and appending any newly available tabs at the end.
 *
 * @template {{ to: string }} T
 * @param {T[]} items
 * @param {string[]} savedOrder
 * @returns {T[]}
 */
export function applyTabOrder(items, savedOrder) {
	if (!Array.isArray(items) || items.length === 0) return [];
	if (!Array.isArray(savedOrder) || savedOrder.length === 0) return [...items];

	const byKey = new Map(items.map((item) => [item.to, item]));
	const ordered = [];
	const seen = new Set();

	for (const key of savedOrder) {
		const found = byKey.get(key);
		if (found && !seen.has(key)) {
			ordered.push(found);
			seen.add(key);
		}
	}

	for (const item of items) {
		if (!seen.has(item.to)) {
			ordered.push(item);
			seen.add(item.to);
		}
	}

	return ordered;
}

/**
 * Swaps the positions of two tabs identified by `sourceKey` and `targetKey`
 * inside `currentKeys`, returning a new array without mutating the input.
 *
 * @param {string[]} currentKeys
 * @param {string} sourceKey
 * @param {string} targetKey
 * @returns {string[]}
 */
export function swapTabKeys(currentKeys, sourceKey, targetKey) {
	if (!Array.isArray(currentKeys) || !sourceKey || !targetKey || sourceKey === targetKey) {
		return Array.isArray(currentKeys) ? [...currentKeys] : [];
	}

	const fromIndex = currentKeys.indexOf(sourceKey);
	const toIndex = currentKeys.indexOf(targetKey);
	if (fromIndex === -1 || toIndex === -1) {
		return [...currentKeys];
	}

	const next = [...currentKeys];
	next[fromIndex] = currentKeys[toIndex];
	next[toIndex] = currentKeys[fromIndex];
	return next;
}
