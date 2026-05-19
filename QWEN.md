# Axolotl — Context for Qwen Code

## Проект
**Axolotl** — визуальная платформа оркестрации AI-агентов. Пользователь создаёт схемы (workflow) из узлов (Source, Agent, Memory, Condition, Loop, Output и др.) и запускает их выполнение.

## Структура проекта
```
Axolotl/
├── backend/                          # Spring Boot 3.2, Java 21
│   ├── src/main/java/com/agent/orchestrator/
│   │   ├── controller/               # REST: AgentController, PlanController, AuthController, SettingsController
│   │   ├── service/                  # Business: SchemaService, PlanService, AgentService, SettingsService
│   │   ├── repository/               # Neo4j DAO: SchemaRepository, PlanRepository, UserRepository
│   │   ├── model/                    # Domain: Node, Edge, WorkflowSchema, Task, Plan, AppUser, Priority, TaskStatus
│   │   ├── mcp/                      # MCP Server: PlanMcpServer, PlanTools
│   │   ├── llm/                      # LLM: LlmService, LlmProvider, OllamaProvider, OpenAiProvider,
│   │   │   │                         #       AnthropicProvider, DeepSeekProvider, MemPalaceClient
│   │   ├── config/                   # Security: JwtAuthFilter, JwtUtil, SecurityConfig, WebSocketConfig
│   │   └── websocket/                # ExecutionWebSocketHandler
│   ├── src/test/java/                # Unit + integration tests (90 total, 90 pass ✅)
│   └── neo4j                         # Neo4j (граф: schemas, plans, users, execution)
├── frontend/                         # Vue 3 + TypeScript + Vite
│   └── src/
│       ├── components/
│       │   ├── canvas/               # WorkflowCanvas.vue (VueFlow)
│       │   ├── nodes/                # AgentNode, SourceNode, MemoryNode, MemoryResultCard,
│       │   │   │                     # ConditionNode, LoopNode, OutputNode, GuardrailNode,
│       │   │   │                     # HumanNode, FallbackNode
│       │   ├── execution/            # ExecutionPanel.vue
│       │   ├── plan/                 # PlanPanel.vue (batch textarea, acceptance criteria, node linking)
│       │   ├── memory/               # MemoryGraphView.vue
│       │   └── ui/                   # CommandPalette.vue (Cmd+K), OnboardingModal.vue
│       ├── views/                    # HomeView.vue (onboarding), SettingsView.vue, LoginView.vue
│       ├── stores/                   # Pinia: schemaStore, authStore
│       ├── composables/              # useWebSocket.ts, useToast.ts
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
- **Neo4j**: `bolt://localhost:7687` (граф: schemas, plans, users, provider settings, execution)

## Архитектура

### Схема выполнения (SchemaService)
1. Вычисление уровней (topological sort по edges)
2. Параллельное выполнение узлов одного уровня (CompletableFuture)
3. WebSocket события: progress, log, error, nodeTime, token, **wave**, nodeBlocked
4. **Convergence monitoring**: счётчик ошибок на узел, порог 3 → `BLOCKED`
5. **Wave events**: WebSocket `{"type":"wave","waveNumber":N,"nodeIds":[...],"status":"pending|running|completed"}`
6. **Context management**: `collectPredecessorResults` + `buildContextBlock` (LLM-суммаризация при >4000 символов)
7. **Variable interpolation**: `{{input}}`, `{{prev_result}}`, `{{node:Name}}`, `{{schema_name}}`
8. **Python export**: `exportToPython()` — генерация исполняемого .py скрипта с топологической сортировкой

### Plan (PlanService)
- Хранится в Neo4j (узлы Plan, поле tasksJson — JSON массив задач)
- MCP инструменты: `read_plan`, `add_task`, **`add_tasks`** (batch), `update_task_status`, `move_task`, `delete_task`, `update_task_priority`
- `read_plan` поддерживает `status_filter` (TODO/IN_PROGRESS/DONE/BLOCKED)
- REST: `GET /api/plan`, `POST /api/plan/tasks`, **`POST /api/plan/tasks/batch`**, `PUT /api/plan/tasks/{id}/status`
- **Acceptance criteria**: массив criteria + met, валидация при переходе в DONE
- **Node linking**: task.nodeId → подсветка на канвасе

### LLM провайдеры
- **Интерфейс**: `LlmProvider` — `chat()`, `streamingChat()`, `supportsStreaming()`, `isAvailable()`, `listModels()`
- **Ollama**: локальные модели, NDJSON streaming (по умолчанию `gemma4:e2b`)
- **OpenAI**: GPT-4o/mini, SSE streaming
- **Anthropic**: Claude Sonnet/Opus/Haiku, SSE streaming
- **DeepSeek**: бюджетная модель, non-streaming
- **Key storage**: приоритет — SettingsService DB → env vars → application.yml

### MemPalace Integration
- **MemPalaceClient**: search, add drawer, taxonomy, tunnels, buildGraphContext
- **Memory Node**: поиск по памяти, фильтрация по wing/room
- **Auto-save**: результаты агентов автоматически сохраняются в MemPalace
- **Memory as context**: graph-structured context injection в systemPrompt

### Безопасность и Multi-tenancy
- **JWT авторизация**: JwtAuthFilter, JwtUtil, SecurityConfig
- **Auth endpoints**: `/api/auth/login`, `/api/auth/register`, `/api/auth/me`
- **UserRepository**: Neo4j узлы User
- **Multi-tenancy**: `WorkflowSchema.userId` — изоляция схем по пользователям
- **Settings API**: `/api/settings` — CRUD API ключей провайдеров
- `/mcp` и `/api/plan/**` — без авторизации
- Остальные endpoints требуют JWT

### Onboarding
- **OnboardingModal**: 2-step wizard (выбор провайдера → confirmation)
- Сохраняет выбор в localStorage (`axolotl:onboarding`, `axolotl:default-model`)
- Показывается при первом визите, есть опция пропуска

## Правила разработки

1. **Бэкенд**: Java 21, Spring Boot 3.2, **SLF4J** logging (все System.out/err заменены), Neo4j
2. **Фронтенд**: Vue 3 Composition API, TypeScript, `<script setup lang="ts">`
3. **Стиль**: Тёмная тема (#1e1e2e фон, #6c63ff акцент)
4. **WebSocket**: Execution events — real-time updates
5. **MCP**: Все изменения плана проходят через MCP tools или REST API
6. **БД**: Neo4j (bolt://localhost:7687) — все репозитории используют Spring Data Neo4j
7. **LLM streaming**: все провайдеры поддерживают `streamingChat()` → WebSocket token events

## Ключевые файлы

| Файл | Назначение |
|------|-----------|
| `backend/.../service/SchemaService.java` | Выполнение схем, context management, Python export, convergence monitoring |
| `backend/.../service/PlanService.java` | CRUD плана, batch add, acceptance criteria |
| `backend/.../service/SettingsService.java` | Хранение API ключей провайдеров в Neo4j |
| `backend/.../service/AgentService.java` | Управление агентами, сессии |
| `backend/.../mcp/PlanTools.java` | MCP инструменты (7 tools: add_tasks, read_plan со status_filter, ...) |
| `backend/.../mcp/PlanMcpServer.java` | JSON-RPC 2.0 MCP сервер на /mcp |
| `backend/.../llm/LlmService.java` | Роутинг запросов к провайдерам |
| `backend/.../llm/OllamaProvider.java` | Ollama: чат + NDJSON streaming |
| `backend/.../llm/OpenAiProvider.java` | OpenAI: чат + SSE streaming |
| `backend/.../llm/AnthropicProvider.java` | Anthropic: чат + SSE streaming |
| `backend/.../llm/DeepSeekProvider.java` | DeepSeek: чат |
| `backend/.../llm/MemPalaceClient.java` | MemPalace: search, add, taxonomy, tunnels, graph context |
| `backend/.../websocket/ExecutionWebSocketHandler.java` | WebSocket события (wave, nodeBlocked, token) |
| `backend/.../controller/AuthController.java` | /api/auth/login, /register, /me |
| `backend/.../controller/SettingsController.java` | /api/settings — CRUD настроек провайдеров |
| `backend/.../config/SecurityConfig.java` | Spring Security + JWT |
| `backend/.../model/Node.java` | NodeStatus: IDLE, RUNNING, COMPLETED, FAILED, **BLOCKED** |
| `backend/.../model/WorkflowSchema.java` | userId для multi-tenancy |
| `frontend/.../components/plan/PlanPanel.vue` | Batch textarea, acceptance criteria, node linking |
| `frontend/.../components/execution/ExecutionPanel.vue` | Waves, metrics, execution history |
| `frontend/.../components/nodes/MemoryNode.vue` | Поиск по MemPalace, фильтрация wing/room |
| `frontend/.../components/ui/OnboardingModal.vue` | 2-step wizard для выбора модели |
| `frontend/.../components/ui/CommandPalette.vue` | Cmd+K palette |
| `frontend/.../views/HomeView.vue` | Onboarding, export (Mermaid + Python), import |
| `frontend/.../views/SettingsView.vue` | Настройки провайдеров (статус, модели) |
| `frontend/.../composables/useWebSocket.ts` | WebSocket клиент, обработка wave/token событий |
