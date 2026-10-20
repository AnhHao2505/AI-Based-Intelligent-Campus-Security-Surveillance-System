import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart';

class AreaPinModel {
  final String id;
  final String name;
  final String areaLevel;
  final int? areaAccessLevel;
  final String? building;
  final String? floor;
  final double centerLatitude;
  final double centerLongitude;
  final bool isActive;

  const AreaPinModel({
    required this.id,
    required this.name,
    required this.areaLevel,
    this.areaAccessLevel,
    this.building,
    this.floor,
    required this.centerLatitude,
    required this.centerLongitude,
    required this.isActive,
  });

  factory AreaPinModel.fromJson(Map<String, dynamic> json) {
    return AreaPinModel(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? 'Khu vực',
      areaLevel: json['areaLevel']?.toString() ?? 'PUBLIC',
      areaAccessLevel: json['areaAccessLevel'] != null
          ? int.tryParse(json['areaAccessLevel'].toString())
          : null,
      building: json['building']?.toString(),
      floor: json['floor']?.toString(),
      centerLatitude: (json['centerLatitude'] as num?)?.toDouble() ?? 0.0,
      centerLongitude: (json['centerLongitude'] as num?)?.toDouble() ?? 0.0,
      isActive: json['isActive'] == true,
    );
  }

  LatLng get latLng => LatLng(centerLatitude, centerLongitude);

  // V83 (gộp loại 19/10): PUBLIC / CONFIDENTIAL / HIGHLY_CONFIDENTIAL, nhãn trùng web.
  // INTERNAL_CONFIDENTIAL và CONFIDENTIAL_CONTACT_REQUIRED là tên cũ, hiển thị như Bảo mật.
  String get levelDisplayName {
    switch (areaLevel) {
      case 'PUBLIC':
        return 'Công khai (Cấp 1)';
      case 'CONFIDENTIAL':
      case 'INTERNAL_CONFIDENTIAL':
      case 'CONFIDENTIAL_CONTACT_REQUIRED':
        return 'Bảo mật (Cấp 2)';
      case 'HIGHLY_CONFIDENTIAL':
        return 'Tuyệt mật (Cấp 3)';
      default:
        return 'Cấp $areaLevel';
    }
  }

  Color get levelColor {
    switch (areaLevel) {
      case 'PUBLIC':
        return const Color(0xFF10B981); // Emerald Green
      case 'CONFIDENTIAL':
      case 'INTERNAL_CONFIDENTIAL':
      case 'CONFIDENTIAL_CONTACT_REQUIRED':
        return const Color(0xFF3B82F6); // Blue (trùng màu Bảo mật trên web)
      case 'HIGHLY_CONFIDENTIAL':
        return const Color(0xFFEF4444); // Red
      default:
        return const Color(0xFF6B7280); // Gray
    }
  }

  IconData get levelIcon {
    switch (areaLevel) {
      case 'PUBLIC':
        return Icons.lock_open_rounded;
      case 'CONFIDENTIAL':
      case 'INTERNAL_CONFIDENTIAL':
      case 'CONFIDENTIAL_CONTACT_REQUIRED':
        return Icons.security_rounded;
      case 'HIGHLY_CONFIDENTIAL':
        return Icons.shield_rounded;
      default:
        return Icons.location_on_rounded;
    }
  }

  String get locationSubtitle {
    final parts = <String>[];
    if (building != null && building!.isNotEmpty) parts.add('Tòa $building');
    if (floor != null && floor!.isNotEmpty) parts.add('Tầng $floor');
    return parts.isEmpty ? 'Khuôn viên ngoài trời' : parts.join(' • ');
  }
}
