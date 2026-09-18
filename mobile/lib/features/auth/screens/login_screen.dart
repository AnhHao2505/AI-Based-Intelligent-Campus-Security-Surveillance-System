import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/constants/app_colors.dart';
import '../../../core/theme/theme_provider.dart';
import '../../../core/utils/storage_helper.dart';
import '../providers/auth_provider.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController(text: 'guard.an@fpt.edu.vn');
  final _passwordController = TextEditingController(text: '123456');
  bool _obscurePassword = true;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _handleLogin() async {
    if (!_formKey.currentState!.validate()) return;
    FocusScope.of(context).unfocus();

    final authProvider = context.read<AuthProvider>();
    final success = await authProvider.login(
      _emailController.text,
      _passwordController.text,
    );

    if (!success && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(authProvider.errorMessage ?? 'Đăng nhập thất bại'),
          backgroundColor: AppColors.danger,
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  // Dialog to change server URL (useful for real device over Wi-Fi)
  void _showServerConfigDialog() {
    final controller = TextEditingController(text: ApiEndpoints.baseUrl);
    bool isTesting = false;
    String? testResult;
    bool? testSuccess;

    showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setModalState) => AlertDialog(
          backgroundColor: AppColors.crd(context),
          title: Text(
            'Cấu hình Máy chủ (Backend URL)',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppColors.txtPrimary(context)),
          ),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Chọn cấu hình nhanh hoặc nhập URL:',
                  style: TextStyle(fontSize: 12, color: AppColors.txtSecondary(context)),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    ActionChip(
                      label: const Text('Cáp USB (127.0.0.1)', style: TextStyle(fontSize: 11)),
                      backgroundColor: AppColors.primary.withAlpha(40),
                      side: const BorderSide(color: AppColors.primaryLight, width: 0.8),
                      onPressed: () {
                        setModalState(() {
                          controller.text = 'http://127.0.0.1:8080';
                          testResult = null;
                        });
                      },
                    ),
                    ActionChip(
                      label: const Text('Wi-Fi (192.168.1.7)', style: TextStyle(fontSize: 11)),
                      backgroundColor: AppColors.surfLight(context),
                      onPressed: () {
                        setModalState(() {
                          controller.text = 'http://192.168.1.7:8080';
                          testResult = null;
                        });
                      },
                    ),
                    ActionChip(
                      label: const Text('Emulator (10.0.2.2)', style: TextStyle(fontSize: 11)),
                      backgroundColor: AppColors.surfLight(context),
                      onPressed: () {
                        setModalState(() {
                          controller.text = 'http://10.0.2.2:8080';
                          testResult = null;
                        });
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: controller,
                  style: TextStyle(fontSize: 14, color: AppColors.txtPrimary(context)),
                  decoration: const InputDecoration(
                    hintText: 'http://127.0.0.1:8080',
                    labelText: 'Server URL',
                  ),
                ),
                const SizedBox(height: 8),
                if (testResult != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 4, bottom: 4),
                    child: Text(
                      testResult!,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: testSuccess == true ? AppColors.success : AppColors.danger,
                      ),
                    ),
                  ),
                OutlinedButton.icon(
                  onPressed: isTesting
                      ? null
                      : () async {
                          setModalState(() {
                            isTesting = true;
                            testResult = null;
                          });
                          final url = controller.text.trim();
                          try {
                            final dio = Dio(BaseOptions(
                              connectTimeout: const Duration(milliseconds: 2500),
                              receiveTimeout: const Duration(milliseconds: 2500),
                            ));
                            await dio.get('$url/api/auth/me');
                            setModalState(() {
                              isTesting = false;
                              testSuccess = true;
                              testResult = '✓ Kết nối máy chủ thành công!';
                            });
                          } on DioException catch (e) {
                            setModalState(() {
                              isTesting = false;
                              if (e.response != null) {
                                testSuccess = true;
                                testResult = '✓ Kết nối máy chủ thành công! (${e.response?.statusCode})';
                              } else {
                                testSuccess = false;
                                testResult = '✗ Không kết nối được. Vui lòng kiểm tra lại.';
                              }
                            });
                          } catch (_) {
                            setModalState(() {
                              isTesting = false;
                              testSuccess = false;
                              testResult = '✗ Không kết nối được.';
                            });
                          }
                        },
                  icon: isTesting
                      ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.wifi_tethering, size: 16),
                  label: Text(isTesting ? 'Đang kiểm tra...' : 'Kiểm tra kết nối'),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: Text('Hủy', style: TextStyle(color: AppColors.txtSecondary(context))),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(minimumSize: const Size(80, 40)),
              onPressed: () async {
                final newUrl = controller.text.trim();
                if (newUrl.isNotEmpty) {
                  await StorageHelper.saveBaseUrl(newUrl);
                  if (!ctx.mounted) return;
                  Navigator.pop(ctx);
                  if (mounted) {
                    setState(() {});
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('Đã cập nhật Server URL: $newUrl')),
                    );
                  }
                }
              },
              child: const Text('Lưu'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final authProvider = context.watch<AuthProvider>();
    final themeProvider = context.watch<ThemeProvider>();

    return Scaffold(
      backgroundColor: AppColors.bg(context),
      body: SafeArea(
        child: Stack(
          children: [
            // Top Right: Theme toggle button
            Positioned(
              top: 8,
              right: 12,
              child: IconButton(
                icon: Icon(
                  themeProvider.isDarkMode ? Icons.light_mode_outlined : Icons.dark_mode_outlined,
                  color: AppColors.txtSecondary(context),
                  size: 22,
                ),
                tooltip: themeProvider.isDarkMode ? 'Chuyển sang chế độ Sáng' : 'Chuyển sang chế độ Tối',
                onPressed: () => themeProvider.toggleTheme(),
              ),
            ),
            Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
                child: Form(
                  key: _formKey,
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // App Badge / Shield Icon
                      Center(
                        child: Container(
                          width: 80,
                          height: 80,
                          decoration: BoxDecoration(
                            color: AppColors.primary.withAlpha(50),
                            borderRadius: BorderRadius.circular(24),
                            border: Border.all(color: AppColors.primaryLight.withAlpha(100), width: 1.5),
                            boxShadow: [
                              BoxShadow(
                                color: AppColors.primary.withAlpha(60),
                                blurRadius: 24,
                                offset: const Offset(0, 8),
                              ),
                            ],
                          ),
                          child: const Icon(
                            Icons.shield_outlined,
                            color: AppColors.primaryLight,
                            size: 44,
                          ),
                        ),
                      ),
                      const SizedBox(height: 24),

                      // Title
                      Text(
                        'Campus Security App',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 24,
                          fontWeight: FontWeight.bold,
                          color: AppColors.txtPrimary(context),
                        ),
                      ),
                      const SizedBox(height: 32),

                  // Email Field
                  TextFormField(
                    controller: _emailController,
                    keyboardType: TextInputType.emailAddress,
                    textInputAction: TextInputAction.next,
                    style: TextStyle(color: AppColors.txtPrimary(context), fontSize: 14),
                    decoration: InputDecoration(
                      labelText: 'Email bảo vệ',
                      prefixIcon: Icon(Icons.email_outlined, color: AppColors.txtSecondary(context), size: 20),
                    ),
                    validator: (value) {
                      if (value == null || value.trim().isEmpty) {
                        return 'Vui lòng nhập email';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),

                  // Password Field
                  TextFormField(
                    controller: _passwordController,
                    obscureText: _obscurePassword,
                    textInputAction: TextInputAction.done,
                    onFieldSubmitted: (_) => _handleLogin(),
                    style: TextStyle(color: AppColors.txtPrimary(context), fontSize: 14),
                    decoration: InputDecoration(
                      labelText: 'Mật khẩu',
                      prefixIcon: Icon(Icons.lock_outline, color: AppColors.txtSecondary(context), size: 20),
                      suffixIcon: IconButton(
                        icon: Icon(
                          _obscurePassword ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                          color: AppColors.txtSecondary(context),
                          size: 20,
                        ),
                        onPressed: () {
                          setState(() => _obscurePassword = !_obscurePassword);
                        },
                      ),
                    ),
                    validator: (value) {
                      if (value == null || value.isEmpty) {
                        return 'Vui lòng nhập mật khẩu';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 24),

                  // Submit Button
                  ElevatedButton(
                    onPressed: authProvider.isLoading ? null : _handleLogin,
                    child: authProvider.isLoading
                        ? const SizedBox(
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                          )
                        : const Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(Icons.login, size: 18),
                              SizedBox(width: 8),
                              Text('ĐĂNG NHẬP'),
                            ],
                          ),
                  ),
                  const SizedBox(height: 20),

                  // Server URL configuration helper
                  Center(
                    child: TextButton.icon(
                      onPressed: _showServerConfigDialog,
                      icon: Icon(Icons.settings_ethernet, size: 16, color: AppColors.txtMuted(context)),
                      label: Text(
                        'Máy chủ: ${ApiEndpoints.baseUrl}',
                        style: TextStyle(fontSize: 11, color: AppColors.txtMuted(context)),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    ),
  ),
);
  }
}
