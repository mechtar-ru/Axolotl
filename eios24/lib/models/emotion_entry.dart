import 'package:sqflite/sqflite.dart';

class EmotionEntry {
  final int id;
  final String emotion;
  final DateTime timestamp;

  EmotionEntry({required this.id, required this.emotion, required this.timestamp});

  factory EmotionEntry.fromMap(Map<String, dynamic> map) {
    return EmotionEntry(
      id: map['id'],
      emotion: map['emotion'],
      timestamp: DateTime.parse(map['timestamp']),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'emotion': emotion,
      'timestamp': timestamp.toString(),
    };
  }
}
