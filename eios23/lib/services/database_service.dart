import 'package:sqflite/sqflite.dart';
import 'dart:async';
import 'package:path/path.dart';
import '../models/emotion_entry.dart';

class DatabaseService {
  static final DatabaseService _instance = DatabaseService._internal();
  factory DatabaseService() => _instance;
  DatabaseService._internal();

  late Database _database;

  Future<Database> get database async {
    if (_database != null) return _database;
    _database = await _initDB();
    return _database;
  }

  Future<Database> _initDB() async {
    final databasesPath = await getDatabasesPath();
    final path = join(databasesPath, 'emotions.db');

    return await openDatabase(path,
        version: 1,
        onCreate: (Database db, int newerVersion) async {
          await db.execute(
              'CREATE TABLE emotions(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)');
        });
  }

  Future<List<EmotionEntry>> getEmotions() async {
    final db = await database;
    List<Map<String, dynamic>> maps = await db.query('emotions');
    return List.generate(maps.length, (i) {
      return EmotionEntry.fromMap(maps[i]);
    });
  }

  Future<void> insertEmotion(EmotionEntry emotion) async {
    final db = await database;
    await db.insert('emotions', emotion.toMap());
  }

  Future<void> updateEmotion(EmotionEntry emotion) async {
    final db = await database;
    await db.update('emotions', emotion.toMap(), where: 'id = ?', whereArgs: [emotion.id]);
  }

  Future<void> deleteEmotion(int id) async {
    final db = await database;
    await db.delete('emotions', where: 'id = ?', whereArgs: [id]);
  }
}
