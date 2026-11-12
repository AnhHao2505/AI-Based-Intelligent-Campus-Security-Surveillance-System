import 'package:flutter/material.dart';
import '../constants/app_colors.dart';

/// Paints subtle translucent FPT-brand color bubbles over the web-matched base background.
class WebAmbientBackground extends StatelessWidget {
  const WebAmbientBackground({super.key});

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return IgnorePointer(
      child: Container(
        color: isDark ? AppColors.darkBackground : AppColors.lightBackground,
        child: Stack(
          fit: StackFit.expand,
          children: isDark
              ? const [
                  _ColorBubble(
                    top: -70,
                    right: -60,
                    size: 240,
                    color: AppColors.primary,
                    fillAlpha: 26,
                    edgeAlpha: 44,
                  ),
                  _ColorBubble(
                    top: 70,
                    left: -80,
                    size: 210,
                    color: AppColors.fptBlue,
                    fillAlpha: 34,
                    edgeAlpha: 52,
                  ),
                  _ColorBubble(
                    bottom: -65,
                    right: -45,
                    size: 190,
                    color: AppColors.success,
                    fillAlpha: 20,
                    edgeAlpha: 36,
                  ),
                  _ColorBubble(
                    bottom: 90,
                    left: -40,
                    size: 110,
                    color: AppColors.primaryLight,
                    fillAlpha: 18,
                    edgeAlpha: 30,
                  ),
                ]
              : const [
                  _ColorBubble(
                    top: -70,
                    right: -60,
                    size: 240,
                    color: AppColors.primary,
                    fillAlpha: 38,
                    edgeAlpha: 64,
                  ),
                  _ColorBubble(
                    top: 70,
                    left: -80,
                    size: 210,
                    color: AppColors.fptBlue,
                    fillAlpha: 32,
                    edgeAlpha: 54,
                  ),
                  _ColorBubble(
                    bottom: -65,
                    right: -45,
                    size: 190,
                    color: AppColors.success,
                    fillAlpha: 26,
                    edgeAlpha: 44,
                  ),
                  _ColorBubble(
                    bottom: 90,
                    left: -40,
                    size: 110,
                    color: AppColors.primary,
                    fillAlpha: 22,
                    edgeAlpha: 38,
                  ),
                ],
        ),
      ),
    );
  }
}

class _ColorBubble extends StatelessWidget {
  const _ColorBubble({
    this.top,
    this.bottom,
    this.left,
    this.right,
    required this.size,
    required this.color,
    required this.fillAlpha,
    required this.edgeAlpha,
  });

  final double? top;
  final double? bottom;
  final double? left;
  final double? right;
  final double size;
  final Color color;
  final int fillAlpha;
  final int edgeAlpha;

  @override
  Widget build(BuildContext context) {
    return Positioned(
      top: top,
      bottom: bottom,
      left: left,
      right: right,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            center: const Alignment(-0.25, -0.25),
            radius: 0.9,
            colors: [
              color.withAlpha(fillAlpha),
              color.withAlpha((fillAlpha * 0.35).round()),
            ],
          ),
          border: Border.all(
            color: color.withAlpha(edgeAlpha),
            width: 1.2,
          ),
        ),
      ),
    );
  }
}
