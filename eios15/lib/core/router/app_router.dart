// app_router.dart
import 'package:flutter/material.dart';

final class AppRouter extends GoRouter {
  AppRouter() : super(
    routes: [
      GoRoute(
        path: '/',
        builder: (context, state) => HomePage(),
      ),
    ],
  );
}