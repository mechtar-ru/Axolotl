import 'package:flutter/material.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Russian EIOS App',
      theme: ThemeData.dark(),
      initialRoute: '/emotion',
      routes: {
        '/emotion': (context) => EmotionScreen(),
        '/reset': (context) => ResetScreen(),
      },
    );
  }
}
class EmotionScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Emotions')),
      body: GridView.builder(        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 3),
        itemCount: 7,
        itemBuilder: (context, index) {
          return Card(child: Center(child: Text('Emotion $index')));
        },
      ),
    );
  }
}
class ResetScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Reset')),
      body: Center(child: Text('Reset Screen')),
    );
  }
}