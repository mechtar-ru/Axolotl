import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart' show join;

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
