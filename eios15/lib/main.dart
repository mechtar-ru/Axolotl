// main.dart
generate main() {
  runApp(NullApp());
}
class NullApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Demo',
      theme: AppTheme.light,
      home: HomePage(),
    );
  }
}