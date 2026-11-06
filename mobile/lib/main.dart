import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'core/constants/app_colors.dart';
import 'core/constants/app_theme.dart';
import 'core/network/api_client.dart';
import 'core/notifications/local_notification_service.dart';
import 'core/notifications/guard_duty_service.dart';
import 'core/session/logout_cleanup.dart';
import 'features/incident/widgets/guard_duty_watcher.dart';
import 'core/theme/theme_provider.dart';
import 'core/utils/storage_helper.dart';
import 'features/auth/providers/auth_provider.dart';
import 'features/auth/screens/login_screen.dart';
import 'features/incident/providers/incident_provider.dart';
import 'features/incident/services/incident_service.dart';
import 'features/incident/services/stomp_service.dart';
import 'features/map/providers/map_provider.dart';
import 'features/incident/screens/incident_list_screen.dart';
import 'features/incident/widgets/incident_offer_banner.dart';
import 'features/incident/widgets/peer_claim_notice_listener.dart';
import 'features/shift/providers/shift_provider.dart';
import 'features/shift/screens/guard_schedule_screen.dart';

import 'package:intl/date_symbol_data_local.dart';

/// Dùng để mở màn danh sách sự cố khi bảo vệ bấm vào thông báo sự cố (không có BuildContext lúc đó).
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>();

/// DEF-17: hiện snackbar "Sự cố đã được … tiếp nhận." trên màn hình đang mở, từ ngoài cây Navigator.
final GlobalKey<ScaffoldMessengerState> appScaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();

/// Về màn gốc rồi mở màn hình danh sách sự cố sau khi nhận lời mời (cùng cách với khi bấm thông báo).
void openIncidentListScreen() {
  final nav = appNavigatorKey.currentState;
  debugPrint('[NotificationTap] navigator ready=${nav != null}');
  if (nav == null) return;
  if (IncidentListScreen.switchToTab(IncidentFilterTab.claimed)) {
    return;
  }
  nav.popUntil((route) => route.isFirst);
  nav.push(MaterialPageRoute(builder: (_) => const IncidentListScreen(initialTab: IncidentFilterTab.claimed)));
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('vi', null);
  await StorageHelper.loadBaseUrl();
  LocalNotificationService.instance.onIncidentTap = (_) {
    final nav = appNavigatorKey.currentState;
    debugPrint('[NotificationTap] navigator ready=${nav != null}');
    if (nav == null) return;
    // Về màn gốc rồi mở màn hình danh sách sự cố
    nav.popUntil((route) => route.isFirst);
    nav.push(MaterialPageRoute(builder: (_) => const IncidentListScreen()));
  };
  await LocalNotificationService.instance.init();
  GuardDutyService.instance.init();

  final apiClient = ApiClient();
  final stompService = StompService();
  final incidentService = IncidentService(apiClient);
  // Tạo sẵn để AuthProvider dọn được ca + sự cố khi đăng xuất (DEF-16).
  final shiftProvider = ShiftProvider(apiClient);
  final incidentProvider = IncidentProvider(
    incidentService: incidentService,
    stompService: stompService,
  );

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider(
          create: (_) => ThemeProvider(),
        ),
        ChangeNotifierProvider(
          create: (_) => AuthProvider(
            apiClient,
            onLogout: () => runLogoutCleanup(
              shiftProvider: shiftProvider,
              incidentProvider: incidentProvider,
              stopDutyService: GuardDutyService.instance.stop,
            ),
          )..restoreSession(),
        ),
        ChangeNotifierProvider(
          create: (_) => shiftProvider,
        ),
        ChangeNotifierProvider(
          create: (_) => MapProvider(apiClient),
        ),
        ChangeNotifierProvider(
          create: (_) => incidentProvider,
        ),
      ],
      child: const GuardMobileApp(),
    ),
  );
}

class GuardMobileApp extends StatelessWidget {
  const GuardMobileApp({super.key});

  @override
  Widget build(BuildContext context) {
    final themeProvider = context.watch<ThemeProvider>();

    return MaterialApp(
      navigatorKey: appNavigatorKey,
      scaffoldMessengerKey: appScaffoldMessengerKey,
      title: 'Guard Patrol Mobile',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: themeProvider.themeMode,
      home: const AuthGate(),
      // Banner lời mời xử lý sự cố nằm trên mọi màn hình; không có lời mời thì không hiện gì.
      builder: (context, child) => Stack(
        children: [
          child ?? const SizedBox.shrink(),
          IncidentOfferBanner(onClaimed: (_) => openIncidentListScreen()),
          PeerClaimNoticeListener(messengerKey: appScaffoldMessengerKey),
        ],
      ),
    );
  }
}

class AuthGate extends StatelessWidget {
  const AuthGate({super.key});

  @override
  Widget build(BuildContext context) {
    final authProvider = context.watch<AuthProvider>();

    // Initial session restoration splash
    if (!authProvider.isInitialized) {
      return Scaffold(
        backgroundColor: AppColors.bg(context),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const SizedBox(
                width: 32,
                height: 32,
                child: CircularProgressIndicator(
                  strokeWidth: 3,
                  color: AppColors.primaryLight,
                ),
              ),
              const SizedBox(height: 16),
              Text(
                'Khôi phục phiên đăng nhập...',
                style: TextStyle(fontSize: 13, color: AppColors.txtSecondary(context)),
              ),
            ],
          ),
        ),
      );
    }

    if (authProvider.isAuthenticated) {
      return const GuardDutyWatcher(child: GuardScheduleScreen());
    }

    return const LoginScreen();
  }
}
