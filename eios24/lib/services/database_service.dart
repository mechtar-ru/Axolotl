import 'package:path/path.dart';
import 'package:sqflite/sqflite.dart';
import 'package:provider/provider.dart';
import '../models/emotion_entry.dart';

const String dbName = 'emotions.db';
const String tableName = 'emotions';

class DatabaseService extends ChangeNotifier {
  late Database _database;

  Future<void> initialize() async {
    final databasePath = await getDatabasesPath();
    final path = join(databasePath, dbName);
    _database = await openDatabase(path, version: 1, onCreate: (db, version) {
      return db.execute('CREATE TABLE $tableName(id INTEGER PRIMARY KEY, emotion TEXT NOT NULL, timestamp TEXT NOT NULL)');
    });
  }

  Future<List<EmotionEntry>> getEntries() async {
    final List<Map<String, dynamic>> entries = await _database.query(tableName);
    return entries.map((entry) => EmotionEntry.fromMap(entry)).toList();
  }

  Future<void> insertEntry(EmotionEntry entry) async {
    await _database.insert(tableName, entry.toMap());
    notifyListeners();
  }

  Future<void> updateEntry(EmotionEntry entry) async {
    await _database.update(tableName, entry.toMap(), where: 'id = ?', whereArgs: [entry.id]);
    notifyListeners();
  }

  Future<void> deleteEntry(int id) async {
    await _database.delete(tableName, where: 'id = ?', whereArgs: [id]);
    notifyListeners();
  }
}
