import 'package:sqflite/sqflite.dart';

class EmotionEntry {
  final int id;
  final String name;

  EmotionEntry({required this.id, required this.name});

  factory EmotionEntry.fromMap(Map<String, dynamic> map) {
    return EmotionEntry(id: map['id'], name: map['name']);
  }

  Map<String, dynamic> toMap() => {'id': id, 'name': name};
}
