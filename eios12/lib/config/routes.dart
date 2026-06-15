import 'package:flutter/material.dart';

enum AppRoute {
  home,
}

class RouteGuard {
  static bool canAccess() {
    // Implement access control logic here
    return true;
  }
}

PageRouteInfo<dynamic> onGenerateRoute(RouteSettings settings) {
  switch (settings.name) {
    case AppRoute.home.name:
      if (!RouteGuard.canAccess()) {
        return MaterialPageRoute(builder: (_) => const Scaffold(body: Center(child: Text('Access Denied'))));
      }
      return MaterialPageRoute(builder: (_) => const HomePage());
    default:
      return MaterialPageRoute(builder: (_) => const Scaffold(body: Center(child: Text('Page Not Found'))));
  }
}