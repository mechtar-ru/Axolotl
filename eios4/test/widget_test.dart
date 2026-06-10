// widget_test.dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:eios4/main.dart';

void main() {
  group('MyApp', () {
    testWidgets('should render HomeScreen', (WidgetTester tester) async {
      await tester.pumpWidget(MyApp());

      expect(find.text('EIOS Home'), findsOneWidget);
      expect(find.text('Welcome to the EIOS App!'), findsOneWidget);
    });
  });
}