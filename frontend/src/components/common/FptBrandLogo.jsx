import React from "react";

/**
 * Official FPT Tri-Color Brand Mark Component
 * Adheres to FPT Corporation & FPT Telecom Brand Identity Guidelines (fpt.vn)
 *
 * Colors:
 * - FPT Blue:   #034EA2 (Intelligence, Reliability, Technology)
 * - FPT Orange: #F37021 (Energy, Innovation, Core Accent)
 * - FPT Green:  #0DB14B (Sustainability, Security, Vitality)
 */
export default function FptBrandLogo({
	variant = "full", // 'full' | 'symbol' | 'compact'
	height = 28,
	className = "",
	style = {},
}) {
	if (variant === "symbol") {
		return (
			<svg
				width={height}
				height={height}
				viewBox="0 0 36 36"
				fill="none"
				xmlns="http://www.w3.org/2000/svg"
				className={`fpt-logo-symbol ${className}`.trim()}
				style={{ display: "inline-block", verticalAlign: "middle", ...style }}
				aria-label="FPT Logo"
			>
				<g transform="skewX(-16) translate(6, 0)">
					<rect x="3" y="4" width="6.5" height="28" rx="3.2" fill="#034EA2" />
					<rect x="12" y="4" width="6.5" height="28" rx="3.2" fill="#F37021" />
					<rect x="21" y="4" width="6.5" height="28" rx="3.2" fill="#0DB14B" />
				</g>
			</svg>
		);
	}

	// Full FPT 3-color emblem with sharp slanted curved blocks
	const width = Math.round((height * 96) / 36);

	return (
		<svg
			width={width}
			height={height}
			viewBox="0 0 96 36"
			fill="none"
			xmlns="http://www.w3.org/2000/svg"
			className={`fpt-brand-logo ${className}`.trim()}
			style={{ display: "inline-block", verticalAlign: "middle", ...style }}
			aria-label="FPT Corporation Logo"
		>
			<g transform="skewX(-16) translate(6, 0)">
				{/* F - Blue */}
				<rect x="4" y="3" width="22" height="30" rx="6" fill="#034EA2" />
				<text
					x="15"
					y="24"
					fill="#FFFFFF"
					fontSize="17"
					fontWeight="900"
					fontFamily="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
					textAnchor="middle"
					letterSpacing="-0.5px"
				>
					F
				</text>

				{/* P - Orange */}
				<rect x="29" y="3" width="22" height="30" rx="6" fill="#F37021" />
				<text
					x="40"
					y="24"
					fill="#FFFFFF"
					fontSize="17"
					fontWeight="900"
					fontFamily="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
					textAnchor="middle"
					letterSpacing="-0.5px"
				>
					P
				</text>

				{/* T - Green */}
				<rect x="54" y="3" width="22" height="30" rx="6" fill="#0DB14B" />
				<text
					x="65"
					y="24"
					fill="#FFFFFF"
					fontSize="17"
					fontWeight="900"
					fontFamily="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
					textAnchor="middle"
					letterSpacing="-0.5px"
				>
					T
				</text>
			</g>
		</svg>
	);
}

