# Axolotl — Context for Qwen Code

## Проект
**Axolotl** — визуальная платформа оркестрации AI-агентов. Пользователь создаёт схемы (workflow) из узлов (Source, Agent, Memory, Condition, Loop, Output и др.) и запускает их выполнение.

## Структура проекта
```
Axolotl/
├── backend/                          # Spring Boot 3.2, Java 21
│   ├── src/main/java/com/agent/orchestrator/
│   │   ├── controller/               # REST endpoints: AgentController, PlanController, SchemaController
│   │   ├── service/                  # Business logic: SchemaService, PlanService
│   │   ├── repository/               # SQLite DAO: SchemaRepository, PlanRepository
│   │   ├── model/                    # Domain: Node, Edge, WorkflowSchema, Task, Plan
│   │   ├── mcp/                      # MCP Server: PlanMcpServer, PlanTools
│   │   ├── llm/                      # LLM clients: LlmService, MemPalaceClient
│   │   ├── config/                   # Security, JWT, WebSocket
│   │   └── websocket/                # ExecutionWebSocketHandler
│   ├── src/test/java/                # Unit + integration tests (90 total, 86 pass)
│   └── schema.db                     # SQLite (таблицы: schemas, plans)
├── frontend/                         # Vue 3 + TypeScript + Vite
│   └── src/
│       ├── components/
│       │   ├── canvas/               # WorkflowCanvas.vue (VueFlow)
│       │   ├── nodes/                # AgentNode, SourceNode, MemoryNode, MemoryResultCard
│       │   ├── execution/            # ExecutionPanel.vue
│       │   ├── plan/                 # PlanPanel.vue (batch textarea, acceptance criteria)
│       │   ├── memory/               # MemoryGraphView.vue
│       │   └── ui/                   # CommandPalette.vue (Cmd+K)
│       ├── views/                    # HomeView.vue (empty state onboarding)
│       ├── stores/                   # Pinia: schemaStore, authStore
│       ├── composables/              # useWebSocket.ts
│       └── services/api.ts           # Axios wrapper
└── pom.xml                           # Maven parent
```

## Сборка и тесты

### Backend
```bash
cd backend
mvn compile -q              # Компиляция
mvn test                    # 90 тестов (все проходят ✅)
mvn spring-boot:run         # Запуск на :8080
```

### Frontend
```bash
cd frontend
npm run build               # Production build
npm run dev                 # Dev server
```

### Сервисы
- **Backend**: `http://localhost:8080`
- **MCP прокси**: `node /Users/evgenijtihomirov/axolotl-mcp-proxy.js` (stdio bridge)
- **MemPalace MCP**: `python -m mempalace.mcp_server` (порт 5890)
- **SQLite**: `backend/schema.db` (таблицы: schemas, plans)

## Архитектура

### Схема выполнения (SchemaService)
1. Вычисление уровней (topological sort по edges)
2. Параллельное выполнение узлов одного уровня (CompletableFuture)
3. WebSocket события: progress, log, error, nodeTime, token, **wave**, nodeBlocked
4. **Convergence monitoring**: счётчик ошибок на узел, порог 3 → `BLOCKED`
5. **Wave events**: WebSocket `{"type":"wave","waveNumber":N,"nodeIds":[...],"status":"pending|running|completed"}`

### Plan (PlanService)
- Хранится в SQLite (таблица plans, поле tasks_json — JSON массив задач)
- MCP инструменты: `read_plan`, `add_task`, **`add_tasks`** (batch), `update_task_status`, `move_task`, `delete_task`, `update_task_priority`
- `read_plan` поддерживает `status_filter` (TODO/IN_PROGRESS/DONE/BLOCKED)
- REST: `GET /api/plan`, `POST /api/plan/tasks`, **`POST /api/plan/tasks/batch`**, `PUT /api/plan/tasks/{id}/status`
- **Acceptance criteria**: массив criteria + met, валидация при переходе в DONE
- **Node linking**: task.nodeId → подсветка на канвасе

### Безопасность
- `/mcp` и `/api/plan/**` — без авторизации
- Остальные endpoints требуют JWT

## Правила разработки

1. **Бэкенд**: Java 21, Spring Boot 3.2, SLF4J + System.out (legacy), SQLite
2. **Фронтенд**: Vue 3 Composition API, TypeScript, `<script setup lang="ts">`
3. **Стиль**: Тёмная тема (#1e1e2e фон, #6c63ff акцент)
4. **WebSocket**: Execution events — real-time updates
5. **MCP**: Все изменения плана проходят через MCP tools или REST API
6. **БД**: `backend/schema.db` — оба репозитория (SchemaRepository, PlanRepository) используют абсолютный путь

## Ключевые файлы

| Файл | Назначение |
|------|-----------|
| `backend/.../service/SchemaService.java` | Выполнение схем, convergence monitoring, wave events |
| `backend/.../service/PlanService.java` | CRUD плана, batch add, acceptance criteria |
| `backend/.../mcp/PlanTools.java` | MCP инструменты (add_tasks, read_plan со status_filter) |
| `backend/.../websocket/ExecutionWebSocketHandler.java` | WebSocket события (wave, nodeBlocked) |
| `backend/.../model/Node.java` | NodeStatus: IDLE, RUNNING, COMPLETED, FAILED, **BLOCKED** |
| `frontend/.../components/plan/PlanPanel.vue` | Batch textarea, acceptance criteria editor, node picker |
| `frontend/.../components/execution/ExecutionPanel.vue` | Waves section, compact/verbose toggle (TODO) |
| `frontend/.../components/ui/CommandPalette.vue` | Cmd+K palette (10 команд) |
| `frontend/.../views/HomeView.vue` | Empty state onboarding, command handler |
| `frontend/.../composables/useWebSocket.ts` | WebSocket клиент, обработка wave событий |
