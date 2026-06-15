import 'package:flutter/foundation.dart';

abstract class AppEnv {
  static Future<void> init() async {
    if (kDebugMode) {
      // Load environment variables from .env.example in debug mode
    } else {
      // Load environment variables from secure storage in production mode
    }
  }
}
