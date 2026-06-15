import 'package:flutter/material.dart';

class EmotionScreen extends StatelessWidget {
  final List<String> emotions = [
    'Обида', 'Тоска', 'Раздражение', 'Тревожность', 'Опустошение', 'Подавленность', 'Напряжение'
  ];

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2),
      itemCount: emotions.length,
      itemBuilder: (context, index) {
        return Card(
          child: Center(child: Text(emotions[index])),
        );
      },
    );
  }
}