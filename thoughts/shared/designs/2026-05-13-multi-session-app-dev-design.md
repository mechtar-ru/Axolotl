---
date: 2026-05-13
topic: "Multi-Session App Development"
status: validated
---

## Problem Statement

Сейчас Axolotl трактует результат выполнения схемы как внутренний артефакт данных — строка текста стримится через WebSocket, отображается в UI и сохраняется в ExecutionRecord. Нет инфраструктуры для генерации внешних приложений и многосессионной разработки, где одна схема создаёт часть приложения, следующая — дополняет его.

**Use case:** Разработчик хочет в несколько запусков схем собрать полноценное приложение (например, игру Сокобан), где каждая сессия создаёт свою часть (scaffold → game engine → level editor), а файлы пишутся в целевую директорию.

## Constraints

- Результаты сессий складываются в `/Users/evgenijtihomirov/git/Axolotl/{schema-name}/`
- `file_write` за пределами targetPath блокируется (с возможностью override в конфиге узла)
- Артефакты исполнения НЕ коммитятся — только список файлов с описанием
- Новый тип узла не нужен — `file_write` достаточно
- Идемпотентность: повторный запуск схемы не должен ломать проект
- Конфликт директории: при существующей директории предложить 3 варианта

## Approach (Chosen: Вариант B — Workspace as Project)

**Используем существующую инфраструктуру Plan + Workspace + Schema, дорабатывая её минимальными изменениями.**

Каждое внешнее приложение = отдельный `workspaceId`. Workspace хранит Plan с иерархией задач. Каждый запуск схемы создаёт Task в Plan, отслеживает его статус и записывает список созданных файлов.

### Почему не A (Manual Handoff) или C (Session-First)

- **A** — 0 инфраструктуры, но нет никакой координации между сессиями. Разработчик — единственный оркестратор. Контекст теряется.
- **C** — полная автоматизация, но требует новой сущности `DevSession`, нового сервиса, нового UI. Риск распухания контекста. Неоправданно для текущих потребностей.
- **B** — 80% пользы при 20% усилий. Всё ключевое уже есть (Plan, Workspace, Schema). Нужна только привязка запуска схемы к Plan и контекстная инъекция.

## Architecture

### 1. Целевая директория

Новое поле `targetPath: String` в `WorkflowSchema.java`.

Формируется при создании схемы через `AppController`, если `appType != CUSTOM`:

```
/Users/evgenijtihomirov/git/Axolotl/{schema-name}/
```

Где `{schema-name}` = `WorkflowSchema.name` как есть (без модификаций).

### 2. Sandbox для file_write

При наличии `targetPath`:
- `allowedPaths` по умолчанию = `[targetPath]`
- `file_write` за пределы `targetPath` блокируется с ошибкой
- Исключение: если в конфиге узла явно указан другой `allowedPaths`
- `file_read` / `directory_read` внутри `targetPath` разрешены всегда

**Где:** `ToolExecutor.java` — при инициализации tool permissions для схемы.

### 3. Plan-linked автотрекинг

При запуске схемы с `targetPath != null`:

1. **SchemaService.execute()** → `PlanService.createTaskForExecution(schemaId)`:
   - Создаёт или находит Plan для workspace
   - Создаёт Task с названием схемы, статус `IN_PROGRESS`
   - Task привязан к schemaId

2. **SchemaService.onExecutionComplete()** → `PlanService.completeTaskForExecution(schemaId, generatedFiles)`:
   - Сканирует targetPath на новые/изменённые файлы
   - Записывает их пути + краткое описание в новое поле `Task.generatedFiles: List<String>`
   - Переводит Task в `DONE`

**Новое поле в Task.java:**
```java
List<GeneratedFile> generatedFiles;

class GeneratedFile {
    String path;          // относительный путь от targetPath
    String description;   // что это за файл (от агента)
}
```

### 4. Контекстная инъекция в systemPrompt

Перед запуском AgentNode, если у схемы есть `targetPath`:

**ProjectContextBuilder** сканирует targetPath и форматирует tree view:
```
Current project state (target: /path/to/Sokoban/):

├── src/
│   ├── main.ts
│   ├── App.vue
│   └── components/
│       └── SokobanGame.vue  ← created in session #2
├── package.json
├── index.html
└── tsconfig.json

Previous sessions completed:
  [1] "Scaffold project" → package.json, tsconfig, vite config
  [2] "Create Game Engine" → SokobanGame.vue
```

**Где:** `NodeExecutor.executeAgentNode()` — добавляет project context в начало systemPrompt.

### 5. Обработка конфликта директории

При создании схемы, если `targetPath` уже существует:

1. Backend проверяет существование директории
2. Возвращает клиенту статус с вариантами:
   - **CONTINUE** — дописывать в существующую директорию
   - **OVERWRITE** — очистить директорию и начать заново
   - **CHANGE_PATH** — пользователь вводит другой путь
3. Выбор сохраняется в `WorkflowSchema.targetPathConflictAction`

**Где:** `AppController.createApp()` — новая логика перед созданием схемы.

**UI:** Модальное окно при создании схемы с appType, если директория существует.

### 6. UI: Dashboard — секция Generated Apps

Новая секция на Dashboard:
```
My Generated Apps
┌──────────────────────────────────────┐
│ 📦 Sokoban                           │
│ 📂 /Users/.../Axolotl/Sokoban/      │
│ ✅ 2/5 sessions complete             │
│ [Open] [Continue Development]        │
└──────────────────────────────────────┘
```

**При клике "Continue Development":** открывается Studio с последней схемой из workspace.

### 7. Отображение результатов в ExecutionPanel

После завершения схемы — показывать список созданных файлов:
```
Target: /Users/.../Axolotl/Sokoban/
Files from this run:
  ✓ Created: src/components/LevelEditor.vue
    Description: Component for editing Sokoban levels
  ✓ Modified: src/App.vue
    Description: Added LevelEditor route
```

## Components

### Backend

| Компонент | Изменения |
|-----------|-----------|
| `WorkflowSchema.java` | Новое поле `targetPath: String` |
| `Task.java` | Новое поле `generatedFiles: List<GeneratedFile>` |
| `AppController.java` | Логика проверки targetPath при создании; конфликт-диалог |
| `SchemaService.java` | Вызов `createTaskForExecution()` при старте, `completeTaskForExecution()` при финише |
| `PlanService.java` | Новые методы `createTaskForExecution`, `completeTaskForExecution`; сканирование файлов |
| `ToolExecutor.java` | Sandbox default allowed paths для схем с targetPath |
| `NodeExecutor.java` | Инъекция project tree + session history в systemPrompt |
| `ProjectContextBuilder.java` | **Новый класс** — сканирует targetPath, форматирует tree view + session history |

### Frontend

| Компонент | Изменения |
|-----------|-----------|
| `DashboardView.vue` | Новая секция Generated Apps |
| `ExecutionPanel.vue` | Отображение списка созданных файлов |
| `AppCreateModal.vue` | Диалог конфликта директории (CONTINUE/OVERWRITE/CHANGE_PATH) |

## Data Flow

```
1. Создание схемы (AppController):
   POST /api/app { name: "Sokoban", appType: "GAME" }
   → targetPath = /Users/.../Axolotl/Sokoban/
   → если директория существует → спросить CONTINUE/OVERWRITE/CHANGE_PATH
   → создать WorkflowSchema + workspaceId

2. Запуск схемы (SchemaService.execute):
   → PlanService.createTaskForExecution(schemaId)
     → Task { status: IN_PROGRESS, schemaId, name: "Sokoban" }
   → NodeExecutor: inject project context into agent's systemPrompt

3. Агент пишет файлы (AgentNode → file_write tool):
   → ToolExecutor проверяет: path внутри targetPath?
   → если нет → BLOCKED (или warning, если override)
   → файл записан

4. Завершение схемы (SchemaService.onComplete):
   → PlanService.completeTaskForExecution(schemaId)
     → сканировать targetPath (рекурсивно, с mtime)
     → diff с предыдущим снимком (хранить в Task.generatedFiles)
     → новые/изменённые файлы → GeneratedFile[] с путями + описанием
     → Task.status = DONE

5. Следующая сессия:
   → Dashboard → "Continue Development"
   → открыть схему в Studio (или создать новую в том же workspace)
   → при запуске: ProjectContextBuilder читает targetPath + Task history
   → инъекция в systemPrompt
```

## Error Handling

| Ситуация | Поведение |
|----------|-----------|
| targetPath не существует | Создать директорию при первом file_write |
| file_write за targetPath | BLOCKED (кроме явно разрешённых в конфиге узла) |
| Директория существует при создании схемы | CONTINUE / OVERWRITE / CHANGE_PATH (выбор пользователя) |
| Schema name совпадает с существующей | Зависит от выбора пользователя (continue/overwrite/change) |
| file_write не удался | Ошибка пишется в ExecutionRecord, Task остаётся BLOCKED |
| Контекст слишком большой (много файлов) | Обрезать tree до 1000 tokens, добавить "[... truncated]" |

## Testing Strategy

- **Unit:** `ProjectContextBuilder` — сканирование директории, форматирование tree
- **Unit:** `PlanService.createTaskForExecution` — создание Task, связывание
- **Unit:** `ToolExecutor` — sandbox блокировка за пределами targetPath
- **Integration:** Полный цикл: создать схему → запустить → проверить файлы на диске
- **Integration:** Повторный запуск той же схемы → проверить идемпотентность

## Open Questions

1. **Хранить ли снимок файловой структуры** (mtime + hash) для diff между сессиями? Или достаточно показывать "все файлы, которые были созданы любой сессией" (текущие на диске)?
2. **Описания файлов** — откуда агенту брать описание того, что он создал? Добавлять в результат узла парсингом? Или агент должен явно возвращать JSON с описаниями?
3. **Сколько сессий показывать в контексте** — все или только последние N (например, 5)?
4. **Многосессионный Plan на Dashboard** — нужна ли визуализация прогресса (5/8 sessions) или достаточно списка приложений?
5. **Старт новой сессии** — кнопка "Continue Development" открывает существующую схему или создаёт новую в том же workspace?
