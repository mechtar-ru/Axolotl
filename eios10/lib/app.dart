import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'core/config/env.dart';
import 'core/router/app_router.dart';
import 'core/theme/app_theme.dart';

class App extends StatelessWidget {
  const App({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: AppConfig.providers,
      child: MaterialApp.router(
        routerConfig: goRouter,
        theme: AppTheme.light,
        localizationsDelegates: const [GlobalMaterialLocalizations.delegate],
      ),
    );
  }
}
