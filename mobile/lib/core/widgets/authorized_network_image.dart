import 'package:flutter/material.dart';
import '../constants/api_endpoints.dart';
import '../utils/storage_helper.dart';

/// Chuẩn hoá đường dẫn ảnh MinIO / Backend sang URL tuyệt đối có thể truy cập được từ thiết bị di động
String? formatEvidenceImageUrl(String? rawUrl) {
  if (rawUrl == null || rawUrl.trim().isEmpty) return null;
  final url = rawUrl.trim();
  if (url.startsWith('data:')) return url;

  final base = ApiEndpoints.baseUrl.replaceAll(RegExp(r'/+$'), '');

  // Nếu URL đã là /api/media/...
  if (url.startsWith('/api/media/')) {
    return '$base$url';
  }

  // Nếu là dạng tương đối: security-evidence/... hoặc face-profiles/...
  if (url.startsWith('security-evidence/') || url.startsWith('face-profiles/')) {
    return '$base/api/media/$url';
  }

  // Nếu là URL MinIO dạng http://...:9000/bucket/path hoặc http://...:9000/security-evidence/...
  final minioMatch = RegExp(r'^https?://[^/]+(?::\d+)?/([^/]+)/(.+)$').firstMatch(url);
  if (minioMatch != null) {
    final bucket = minioMatch.group(1);
    final path = minioMatch.group(2);
    return '$base/api/media/$bucket/$path';
  }

  return url;
}

/// Widget hiển thị ảnh bằng chứng bảo mật từ Backend với Header Authorization Bearer JWT Token
class AuthorizedNetworkImage extends StatefulWidget {
  final String? imageUrl;
  final double? width;
  final double? height;
  final BoxFit fit;
  final BorderRadius? borderRadius;
  final bool enableZoomOnTap;

  const AuthorizedNetworkImage({
    super.key,
    required this.imageUrl,
    this.width,
    this.height,
    this.fit = BoxFit.cover,
    this.borderRadius,
    this.enableZoomOnTap = true,
  });

  @override
  State<AuthorizedNetworkImage> createState() => _AuthorizedNetworkImageState();
}

class _AuthorizedNetworkImageState extends State<AuthorizedNetworkImage> {
  String? _token;
  bool _isLoadingToken = true;

  @override
  void initState() {
    super.initState();
    _loadToken();
  }

  Future<void> _loadToken() async {
    final token = await StorageHelper.getToken();
    if (mounted) {
      setState(() {
        _token = token;
        _isLoadingToken = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final formattedUrl = formatEvidenceImageUrl(widget.imageUrl);

    if (formattedUrl == null) {
      return _buildPlaceholder(
        icon: Icons.broken_image_outlined,
        message: 'Không có ảnh',
      );
    }

    if (_isLoadingToken) {
      return _buildLoading();
    }

    final headers = <String, String>{};
    if (_token != null && _token!.isNotEmpty) {
      headers['Authorization'] = 'Bearer $_token';
    }

    Widget imageWidget = Image.network(
      formattedUrl,
      headers: headers,
      width: widget.width,
      height: widget.height,
      fit: widget.fit,
      loadingBuilder: (context, child, loadingProgress) {
        if (loadingProgress == null) return child;
        return _buildLoading(
          progress: loadingProgress.expectedTotalBytes != null
              ? loadingProgress.cumulativeBytesLoaded /
                  loadingProgress.expectedTotalBytes!
              : null,
        );
      },
      errorBuilder: (context, error, stackTrace) {
        return _buildPlaceholder(
          icon: Icons.error_outline,
          message: 'Không tải được ảnh',
        );
      },
    );

    if (widget.borderRadius != null) {
      imageWidget = ClipRRect(
        borderRadius: widget.borderRadius!,
        child: imageWidget,
      );
    }

    if (widget.enableZoomOnTap) {
      return GestureDetector(
        onTap: () => _showFullscreenImage(context, formattedUrl, headers),
        child: imageWidget,
      );
    }

    return imageWidget;
  }

  Widget _buildLoading({double? progress}) {
    return Container(
      width: widget.width,
      height: widget.height,
      decoration: BoxDecoration(
        color: const Color(0xFF1E293B),
        borderRadius: widget.borderRadius,
      ),
      child: Center(
        child: SizedBox(
          width: 20,
          height: 20,
          child: CircularProgressIndicator(
            value: progress,
            strokeWidth: 2,
            color: const Color(0xFF38BDF8),
          ),
        ),
      ),
    );
  }

  Widget _buildPlaceholder({required IconData icon, required String message}) {
    return Container(
      width: widget.width,
      height: widget.height,
      decoration: BoxDecoration(
        color: const Color(0xFF1E293B),
        borderRadius: widget.borderRadius,
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 22, color: const Color(0xFF64748B)),
          const SizedBox(height: 3),
          Text(
            message,
            style: const TextStyle(
              color: Color(0xFF64748B),
              fontSize: 10,
            ),
          ),
        ],
      ),
    );
  }

  void _showFullscreenImage(
    BuildContext context,
    String url,
    Map<String, String> headers,
  ) {
    showDialog(
      context: context,
      barrierColor: Colors.black.withAlpha(230),
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        insetPadding: const EdgeInsets.all(12),
        child: Stack(
          alignment: Alignment.center,
          children: [
            InteractiveViewer(
              panEnabled: true,
              minScale: 0.5,
              maxScale: 4.0,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.network(
                  url,
                  headers: headers,
                  fit: BoxFit.contain,
                  errorBuilder: (_, _, _) => const Center(
                    child: Text(
                      'Không thể tải ảnh bằng chứng',
                      style: TextStyle(color: Colors.white70),
                    ),
                  ),
                ),
              ),
            ),
            Positioned(
              top: 8,
              right: 8,
              child: IconButton(
                icon: const Icon(Icons.close_rounded, color: Colors.white, size: 28),
                onPressed: () => Navigator.of(ctx).pop(),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
