/**
 * Parse an JSON string safely without throwing SyntaxError.
 * Handles null, undefined, "undefined", "null", or malformed JSON strings.
 */
export function safeJsonParse(value, fallback = null) {
  if (value === null || value === undefined) return fallback;
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed || trimmed === 'undefined' || trimmed === 'null') return fallback;
  try {
    return JSON.parse(trimmed);
  } catch {
    return fallback;
  }
}
