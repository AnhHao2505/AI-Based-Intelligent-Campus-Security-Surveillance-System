---
name: FPTU Campus Security Surveillance System
description: Institutional Security Operations Center & Restricted Area Access Control
colors:
  primary: "#ea580c"
  primary-hover: "#c2410c"
  primary-dark: "#f97316"
  bg-page-light: "#f8fafc"
  bg-page-dark: "#080c14"
  surface-light: "#ffffff"
  surface-dark: "#0e1422"
  surface-elevated-dark: "#151d30"
  border-light: "#e2e8f0"
  border-dark: "rgba(148, 163, 184, 0.14)"
  success: "#059669"
  success-dark: "#34d399"
  warning: "#d97706"
  warning-dark: "#fbbf24"
  danger: "#dc2626"
  danger-dark: "#f87171"
  info: "#0284c7"
  info-dark: "#38bdf8"
typography:
  display:
    fontFamily: "'IBM Plex Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: "1.75rem"
    fontWeight: 700
    lineHeight: "1.2"
    letterSpacing: "-0.02em"
  body:
    fontFamily: "'IBM Plex Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 400
    lineHeight: "1.55"
    letterSpacing: "-0.011em"
  telemetry:
    fontFamily: "'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
    fontSize: "0.8125rem"
    fontWeight: 500
    lineHeight: "1.4"
rounded:
  sm: "6px"
  md: "8px"
  lg: "12px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "10px 16px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
---

# Design System

## Overview
The FPTU Campus Security Surveillance System (FA26SE040) is an institutional command-and-control platform engineered for high-density campus surveillance, restricted area access enforcement, and sub-3-second intrusion triage. The visual language embodies operational calm, mission-critical authority, and high scanability.

## Colors & Atmospheres
- **Primary Accent:** FPT Security Orange (`#ea580c` in light mode, `#f97316` in dark mode). Applied strictly for primary actions, selection states, and live camera indicators.
- **Dual Mode Background Wave System:**
  - **Dark Mode (`/images/bg-user-original-4k.jpg`):** Exact user-provided original graphic rendered at 4K resolution (3840 × 2560) with unsharp mask sharpening, styled via CSS filters: `hue-rotate(150deg) saturate(1.3) contrast(1.22) brightness(0.92)` with `image-rendering: -webkit-optimize-contrast`.
  - **Light Mode (`/images/bg-light.png`):** 4K inverted counterpart of the exact graphic, cleanly rendering subtle orange wave dots over the `#f8fafc` canvas.
- **Dark Mode Surface Hierarchy (Command Center Obsidian):**
  - Page Canvas: `#080c14`
  - Base Surface: `#0e1422` (Translucent `rgba(14, 20, 34, 0.90)` with `backdrop-filter: blur(16px)`)
  - Elevated Cards & Popovers: `#151d30`
  - Precision Steel Borders: `rgba(148, 163, 184, 0.14)`
- **Light Mode Surface Hierarchy:**
  - Page Canvas: `#f8fafc`
  - Surface: `#ffffff` (Translucent `rgba(255, 255, 255, 0.92)` with `backdrop-filter: blur(16px)`)
  - Elevated Surface: `#f1f5f9`
  - Border: `#e2e8f0`
- **Telemetry & Incident Severity:**
  - Normal / Verified / Online: `#059669` (Light) / `#34d399` (Dark)
  - Warning / Loitering / Pending: `#d97706` (Light) / `#fbbf24` (Dark)
  - Critical / Intrusion / Unauthorized: `#dc2626` (Light) / `#f87171` (Dark)
  - Information / Diagnostic / Camera Stream: `#0284c7` (Light) / `#38bdf8` (Dark)

## Typography
- **Primary Interface Font:** `IBM Plex Sans` (400, 500, 600, 700). High legibility at small sizes, neutral geometry, and authoritative character suited for control rooms.
- **Telemetry & Measurement Font:** `IBM Plex Mono` (400, 500, 600). Used exclusively for timestamps, camera IDs (`CAM-001`), IP addresses, latency (ms), and incident metrics.
- **Numeric Setting:** Universal `font-variant-numeric: tabular-nums` to eliminate jitter during real-time surveillance updates.

## Layout
- **Fixed Sidebar Architecture:** 260px wide (76px collapsed), anchored to the left with quick collapse and smooth transition.
- **Max Content Width:** Constrained to 1440px on administrative views; full-viewport grid for the Security Operations Center (SOC) camera matrix.
- **Rhythm & Grid:** 8px base grid system with structured gap increments (12px, 16px, 24px).

## Elevation & Depth
- **Restrained Shadows:** Shadows feature soft ambient blur with an offset (`0 4px 16px -2px rgba(0, 0, 0, 0.5)`). Zero hard, blocky offset shadows.
- **Micro-Highlight Inset:** Critical alert cards and selected elements use subtle top border insets (`inset 0 2px 0 0 <color>`) rather than asymmetric side borders.

## Shapes
- **Corner Radii:**
  - Micro Badges & Chips: 6px
  - Form Inputs, Buttons, & Table Rows: 8px
  - Telemetry Cards & Modals: 12px-14px
- **Symmetry:** All card elements maintain uniform perimeter border radii without broken corners.

## Components
- **Buttons:** Solid primary blue with 8px radius; high-contrast white text (WCAG AAA >= 5.1:1).
- **Navigation Links:** Subtle hover tint; active state features a soft primary pill (`--theme-primary-light`) with solid icon color.
- **Incident Cards:** Clean 1px semantic border with subtle background tint and dedicated severity chip.
- **Browser Surfaces:** Themed custom scrollbars (6px rounded), native focus-visible rings with 2px offset, and brand selection highlights.

## Motion & Transitions
- **Easing Standard:** Exponential deceleration curve `cubic-bezier(0.16, 1, 0.3, 1)` across all interactive elements. Zero bounce or elastic overshoot.
- **Modal Backdrops:** 0.22s fade-in with smooth backdrop-filter blur escalation.
- **Modal Dialogs & Popups:** 0.26s scale-and-drift entrance (`translateY(10px) scale(0.975) -> translateY(0) scale(1)`).
- **Tab Panels & Content:** 0.22s smooth fade-and-glide entrance (`translateY(5px) -> translateY(0)`).
- **Tab Navigation & Segmented Buttons:** 0.2s color, background, and scale transitions with tactile active press (`scale(0.98)`).
- **Page Transitions:** 0.24s view fade-and-glide for seamless route navigation.
- **Reduced Motion:** Automatic suppression to `0.01ms` when `prefers-reduced-motion: reduce` is active.

## Do's and Don'ts
- **DO** use tabular numerals (`tnum`) for camera feeds, timecodes, and incident counts.
- **DO** maintain high contrast ratios (>= 4.5:1 for body copy, >= 3:1 for headers and telemetry).
- **DO** keep normal monitoring calm and restrained so red intrusion alarms command immediate, visceral attention.
- **DON'T** use 3px/4px asymmetric `border-left` strips on cards (AI-tell).
- **DON'T** use playful candy gradients, floating anime blur orbs, or neon text glow.
- **DON'T** use bounce/elastic transition easings; use smooth exponential deceleration (`cubic-bezier(0.16, 1, 0.3, 1)`).
