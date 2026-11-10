import React from "react";

/**
 * Official FPT Tri-Color Brand Logo Component
 * Uses the official transparent PNG brand asset (/images/fpt-logo.png).
 */
export default function FptBrandLogo({
	variant = "full", // 'full' | 'symbol' | 'compact'
	height = 28,
	className = "",
	style = {},
}) {
	const resolvedHeight = variant === "symbol" ? Math.min(height, 18) : height;
	const width = Math.round((resolvedHeight * 1024) / 632);

	return (
		<img
			src="/images/fpt-logo.png"
			alt="FPT Logo"
			width={width}
			height={resolvedHeight}
			className={`${variant === "symbol" ? "fpt-logo-symbol" : "fpt-brand-logo"} ${className}`.trim()}
			style={{
				display: "inline-block",
				verticalAlign: "middle",
				objectFit: "contain",
				width: "auto",
				height: `${resolvedHeight}px`,
				maxWidth: "100%",
				...style,
			}}
		/>
	);
}
