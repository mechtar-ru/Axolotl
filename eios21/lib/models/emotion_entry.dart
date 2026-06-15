import 'package:sqflite/sqflite.dart';
import 'package:intl/intl.dart';

class EmotionEntry {
  final int id;
  final String date;
  final String emotion;
  final int intensity;
  final String bodyZone;
  final String note;

  EmotionEntry({required this.id, required this.date, required this.emotion, required this.intensity, required this.bodyZone, required this.note});

  factory EmotionEntry.fromJson(Map<String, dynamic> json) {
    return EmotionEntry(
      id: json['id'],
      date: json['date'],
      emotion: json['emotion'],
      intensity: json['intensity'],
      bodyZone: json['bodyZone'],
      note: json['note'],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'date': date,
      'emotion': emotion,
      'intensity': intensity,
      'bodyZone': bodyZone,
      'note': note,
    };
  }

  factory EmotionEntry.fromMap(Map<String, dynamic> map) {
    return EmotionEntry(
      id: map['id'],
      date: DateFormat('yyyy-MM-dd').format(DateTime.now()),
      emotion: map['emotion'],
      intensity: map['intensity'],
      bodyZone: map['bodyZone'],
      note: map['note'],
    );
  }
}
