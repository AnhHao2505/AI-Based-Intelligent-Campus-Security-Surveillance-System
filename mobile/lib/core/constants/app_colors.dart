import 'package:flutter/material.dart';

class AppColors {
  // Dark Palette (Command Center Obsidian - Matching Web Dark Mode)
  static const Color darkBackground = Color(0xFF080C14); // --theme-bg-page: #080c14
  static const Color darkSurface = Color(0xFF0E1422);    // --theme-bg-surface
  static const Color darkSurfaceLight = Color(0xFF111A2E); // --theme-bg-input: #111a2e
  static const Color darkCard = Color(0xFF151D30);       // --theme-bg-surface-elevated
  static const Color darkCardBorder = Color(0xFF233047); // --theme-border
  static const Color darkTextPrimary = Color(0xFFF8FAFC);   // --theme-text-primary: #f8fafc
  static const Color darkTextSecondary = Color(0xFFCBD5E1); // --theme-text-secondary: #cbd5e1
  static const Color darkTextMuted = Color(0xFF94A3B8);     // --theme-text-muted: #94a3b8

  // Light Palette (Standardized Web Design System)
  static const Color lightBackground = Color(0xFFF8FAFC); // --theme-bg-page: #f8fafc
  static const Color lightSurface = Color(0xFFFFFFFF);    // --theme-bg-surface: #ffffff
  static const Color lightSurfaceLight = Color(0xFFF1F5F9); // Slate 100
  static const Color lightCard = Color(0xFFFFFFFF);       // --theme-bg-surface-elevated: #ffffff
  static const Color lightCardBorder = Color(0xFFE2E8F0); // --theme-border: #e2e8f0
  static const Color lightTextPrimary = Color(0xFF0F172A); // --theme-text-primary: #0f172a
  static const Color lightTextSecondary = Color(0xFF475569); // --theme-text-secondary: #475569
  static const Color lightTextMuted = Color(0xFF64748B);     // --theme-text-muted: #64748b

  // Backward compatibility static constants (default dark)
  static const Color background = darkBackground;
  static const Color surface = darkSurface;
  static const Color surfaceLight = darkSurfaceLight;
  static const Color card = darkCard;
  static const Color cardBorder = darkCardBorder;
  static const Color textPrimary = darkTextPrimary;
  static const Color textSecondary = darkTextSecondary;
  static const Color textMuted = darkTextMuted;

  // Accents & Actions (Official FPT Brand Palette - Matching Web)
  static const Color primary = Color(0xFFF37021);      // --theme-primary: #f37021
  static const Color primaryLight = Color(0xFFF97316); // --theme-primary (dark): #f97316
  static const Color primaryHover = Color(0xFFE05E10); // --theme-primary-hover: #e05e10
  static const Color fptBlue = Color(0xFF034EA2);      // --brand-fpt-blue: #034ea2
  static const Color success = Color(0xFF0DB14B);      // --theme-success: #0db14b
  static const Color successLight = Color(0xFF34D399); // --theme-success (dark): #34d399
  static const Color warning = Color(0xFFF59E0B);      // --theme-warning: #f59e0b
  static const Color warningLight = Color(0xFFFBBF24); // --theme-warning (dark): #fbbf24
  static const Color danger = Color(0xFFEF4444);       // --theme-danger: #ef4444
  static const Color dangerLight = Color(0xFFF87171);  // --theme-danger (dark): #f87171

  // Shift Colors
  static const Color morningShift = Color(0xFF0284C7);
  static const Color afternoonShift = Color(0xFFD97706);
  static const Color nightShift = Color(0xFF6366F1);
  static const Color offShift = Color(0xFF64748B);

  // Walkie-talkie Radio
  static const Color radioPill = Color(0xFF78350F);
  static const Color radioText = Color(0xFFFDE68A);

  // Dynamic resolution helpers
  static bool isDark(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark;

  static Color bg(BuildContext context) =>
      isDark(context) ? darkBackground : lightBackground;

  static Color surf(BuildContext context) =>
      isDark(context) ? darkSurface : lightSurface;

  static Color surfLight(BuildContext context) =>
      isDark(context) ? darkSurfaceLight : lightSurfaceLight;

  static Color crd(BuildContext context) =>
      isDark(context) ? darkCard : lightCard;

  static Color crdBorder(BuildContext context) =>
      isDark(context) ? darkCardBorder : lightCardBorder;

  static Color txtPrimary(BuildContext context) =>
      isDark(context) ? darkTextPrimary : lightTextPrimary;

  static Color txtSecondary(BuildContext context) =>
      isDark(context) ? darkTextSecondary : lightTextSecondary;

  static Color txtMuted(BuildContext context) =>
      isDark(context) ? darkTextMuted : lightTextMuted;
}
