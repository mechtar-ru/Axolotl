import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart' show join;

class EmotionEntry {
  final int id;
  final DateTime date;
  final String emotion;
  final int intensity;
  final String bodyZone;
  final String note;

  EmotionEntry({required this.id, required this.date, required this.emotion, required this.intensity, required this.bodyZone, required this.note});

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'date': date.toIso8601String(),
      'emotion': emotion,
      'intensity': intensity,
      'bodyZone': bodyZone,
      'note': note,
    };
  }

  factory EmotionEntry.fromMap(Map<String, dynamic> map) {
    return EmotionEntry(
      id: map['id'] as int,
      date: DateTime.parse(map['date'] as String),
      emotion: map['emotion'] as String,
      intensity: map['intensity'] as int,
      bodyZone: map['bodyZone'] as String,
      note: map['note'] as String,
    );
  }
}

class DatabaseService {
  static final DatabaseService _instance = DatabaseService._internal();

  factory DatabaseService() => _instance;

  DatabaseService._internal();

  late Database _database;

  Future<void> initDatabase() async {
    final databasesPath = await getDatabasesPath();
    final path = join(databasesPath, 'emotions.db');

    _database = await openDatabase(
      path,
      version: 1,
      onCreate: (Database db, int version) async {
        await db.execute('''
          CREATE TABLE emotions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            date TEXT NOT NULL,
            emotion TEXT NOT NULL,
            intensity INTEGER NOT NULL,
            bodyZone TEXT NOT NULL,
            note TEXT NOT NULL
          )
        ''');
      },
    );
  }

  Future<List<EmotionEntry>> getEntries() async {
    final List<Map<String, dynamic>> maps = await _database.query('emotions');
    return List.generate(maps.length, (i) => EmotionEntry.fromMap(maps[i]));
  }

  Future<int> insert(EmotionEntry entry) async {
    return await _database.insert('emotions', entry.toMap());
  }

  Future<void> update(EmotionEntry entry) async {
    await _database.update(
      'emotions',
      entry.toMap(),
      where: 'id = ?',
      whereArgs: [entry.id],
    );
  }

  Future<void> delete(int id) async {
    await _database.delete('emotions', where: 'id = ?', whereArgs: [id]);
  }
}
