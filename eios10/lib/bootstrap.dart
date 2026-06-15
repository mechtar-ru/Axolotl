import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'app.dart';
import 'core/config/env.dart';
import 'core/router/app_router.dart';

Future<void> runAppWithDependencies() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppEnv.init();
  runApp(ProviderScope(
    child: MaterialApp.router(
      routerConfig: goRouter,
      theme: AppTheme.light,
      localizationsDelegates: const [GlobalMaterialLocalizations.delegate],
    ),
  ));
}
