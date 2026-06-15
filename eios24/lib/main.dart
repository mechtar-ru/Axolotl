import 'package:flutter/material.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Russian EIOS',
      theme: ThemeData.dark(),
      home: EmotionScreen(),
    );
  }
}

class EmotionScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final databaseService = Provider.of<DatabaseService>(context);
    return Scaffold(
      appBar: AppBar(title: Text('Emotions')),
      body: GridView.builder(
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 3),
        itemCount: 7,
        itemBuilder: (context, index) {
          return Card(
            child: Center(child: Text('Emotion ${index + 1}')));
        },
      ),
    );
  }
}
