import 'package:flutter/material.dart';
import 'config/routes.dart';

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'My App',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      initialRoute: AppRoute.home,
      onGenerateRoute: AppRoute.onGenerateRoute,
    );
  }
}