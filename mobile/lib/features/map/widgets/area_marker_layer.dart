import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import '../models/area_pin_model.dart';

class AreaMarkerLayer extends StatelessWidget {
  final List<AreaPinModel> pins;
  final AreaPinModel? selectedPin;
  final ValueChanged<AreaPinModel> onPinTap;

  const AreaMarkerLayer({
    super.key,
    required this.pins,
    this.selectedPin,
    required this.onPinTap,
  });

  @override
  Widget build(BuildContext context) {
    return MarkerLayer(
      markers: pins.map((pin) {
        final isSelected = selectedPin?.id == pin.id;
        final color = pin.levelColor;

        return Marker(
          point: pin.latLng,
          width: isSelected ? 56 : 48,
          height: isSelected ? 56 : 48,
          alignment: Alignment.center,
          child: MouseRegion(
            cursor: SystemMouseCursors.click,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () {
                onPinTap(pin);
              },
              child: Center(
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 250),
                  curve: Curves.easeOutCubic,
                  width: isSelected ? 52 : 42,
                  height: isSelected ? 52 : 42,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: color,
                    border: Border.all(
                      color: isSelected ? Colors.white : Colors.white.withAlpha(220),
                      width: isSelected ? 3.0 : 2.0,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: (isSelected ? color : Colors.black).withAlpha(isSelected ? 160 : 70),
                        blurRadius: isSelected ? 12 : 6,
                        offset: const Offset(0, 3),
                      ),
                    ],
                  ),
                  child: Center(
                    child: Icon(
                      pin.levelIcon,
                      size: isSelected ? 26 : 20,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}
