# 🧬 Axolotl — визуальная оркестрация AI-агентов

> *"Рисуй логику, а не пиши её"*

Axolotl — это платформа для визуального создания и выполнения цепочек AI-агентов. Стройте workflow из узлов на бесконечном канвасе, подключайте LLM-провайдеров, управляйте памятью через MemPalace и запускайте выполнение в реальном времени.

---

## ✨ Возможности

### Визуальный редактор
- 🎨 **Infinite Canvas** на VueFlow — drag-and-drop, зум, панорамирование
- 🧩 **11 типов узлов**: Source, Agent, Output, Condition, Loop, Memory, Guardrail, Human, Fallback, Subagent, Group
- 🔗 **Связи** — типизированные edges (data, condition true/false, loop)
- ↩️ **Undo/Redo** — Cmd+Z / Cmd+Shift+Z
- 📋 **Копирование** — Cmd+C / Cmd+V / Cmd+D
- 🔍 **Поиск** — Cmd+F по имени и типу узла
- 📦 **JSON экспорт/импорт** — полный обмен схемами
- 📊 **Mermaid экспорт** — диаграммы из схемы
- 🐍 **Python экспорт** — генерация исполняемого `.py` скрипта
- 📷 **PNG/SVG** — скриншот канваса
- 📝 **Comments** — текстовые заметки на канвасе
- 📦 **Node grouping** — группировка 2+ узлов (Ctrl+G)

### Выполнение схем
- ⚡ **Параллельное выполнение** — независимые ветки через CompletableFuture
- 📡 **WebSocket real-time** — прогресс, логи, токены, метрики, волны
- 🔄 **Token streaming** — посимвольная отдача LLM-ответа
- 🚦 **Convergence monitoring** — счётчик ошибок, порог 3 → `BLOCKED`
- 🛑 **Cancel execution** — остановка по запросу
- 📊 **Execution history** — записи о прошлых запусках
- 🎭 **Execution Modes**: EXECUTE (full), ANALYZE (read-only), DRY_RUN (simulate)

### LLM интеграция
- 🦙 **Ollama** — локальные модели, NDJSON streaming
- 🤖 **OpenAI** — GPT-4o/mini, SSE streaming
- 🧠 **Anthropic** — Claude Sonnet/Opus/Haiku, SSE streaming
- 🔍 **DeepSeek** — бюджетная модель
- 🔗 **Custom Endpoints** — добавление OpenAI-совместимых провайдеров
- 🎯 **Модель на узел** — каждый AgentNode выбирает свою модель
- 💡 **Key storage** — API ключи в env vars или application.yml
- ✅ **LLM Cross-check** — верификация ответа через вторую LLM

### MemPalace — долговременная память
- 🧠 **Memory Node** — поиск по памяти, фильтрация wing/room
- 💾 **Auto-save** — результаты агентов → MemPalace автоматически
- 🌐 **Graph visualization** — Memory Graph View с крыльями, комнатами, туннелями
- 📊 **Graph context** — structured tree + table → injection в systemPrompt
- 🔎 **Semantic search** — cosine similarity
- 🗂️ **Memory result cards** — результаты поиска как плавающие карточки на канвасе

### Plan / Workspace
- 📋 **Todo-лист** — задачи со статусами и приоритетами
- ✍️ **Batch add** — массовое добавление через textarea
- ✅ **Acceptance criteria** — валидация при переходе в DONE
- 🔗 **Node linking** — привязка задачи к узлу на канвасе
- 🤖 **MCP сервер** — 7 инструментов через JSON-RPC 2.0 на `/mcp`
- 🧩 **Skills** — auto-learning система с отслеживанием использования и success rate

### Remote API & Интеграции
- 🔑 **API Keys** — управление ключами для внешних систем
- 📡 **Remote API** — `/api/remote/*` для триггера workflow
- ⚡ **Rate limiting** — 60 req/min на ключ
- 🔗 **Webhook callbacks** — уведомления о завершении
- 📤 **Share Links** — read-only ссылки на схемы с expiration
- 📊 **Prometheus metrics** — `/actuator/prometheus`
- 📚 **OpenAPI/Swagger** — `/swagger-ui.html`

### Subagent Workflows
- 🤝 **Subagent Node** — вызов вложенных workflow
- 🔄 **Input/Output mapping** — передача данных между workflow
- 🛡️ **Max depth** — защита от рекурсии (5 уровней)
- 📜 **Nested logs** — indented логи в Execution Panel

### Безопасность
- 🔐 **JWT авторизация** — регистрация/вход
- 👥 **Multi-tenancy** — изоляция схем по пользователям
- 🔑 **Settings API** — CRUD API ключей провайдеров
- 🛡️ **Guardrail Node** — валидация/трансформация данных
- 👤 **Human Node** — ожидание подтверждения человека

### UI/UX
- 🌙 **Тёмная тема** — #1e1e2e фон, #6c63ff акцент
- ⌨️ **Command Palette** — Cmd+K быстрый доступ
- 🎓 **Onboarding** — 2-step wizard при первом визите
- 🎬 **Анимации** — пульсация running, свечение completed, тряска failed
- 🔔 **Toast notifications** — feedback на действия
- 💾 **Auto-save indicator** — визуальный индикатор сохранения

### Desktop App (Electron)
- 🖥️ **Native window** — 1400x900, min 1024x700
- 🧭 **System tray** — show/hide, new workflow, quit
- 📋 **Application menu** — File, Edit, View, Window, Help
- ⌨️ **Global shortcut** — Cmd/Ctrl+Shift+A toggle visibility
- 🔔 **Native notifications** — execution complete, errors
- 💾 **File dialogs** — native open/save for workflows
- 🔄 **Auto-update** — electron-updater с GitHub releases
- ⚡ **Fast startup** — preload script with secure IPC

---

## 🛠️ Технологии

### Бэкенд
| Технология | Версия |
|-----------|--------|
| Java | 21 |
| Spring Boot | 3.2 |
| SQLite | 3.x |
| PostgreSQL | (Docker) |
| WebSocket | Spring |
| Micrometer | Prometheus metrics |
| springdoc | OpenAPI 3.0 |

### Фронтенд
| Технология | Версия |
|-----------|--------|
| Vue | 3 (Composition API) |
| TypeScript | 5.x |
| VueFlow | 1.x |
| Vite | 5.x |
| Pinia | State management |
| Playwright | E2E testing |
| Electron | 34.x |

### Инфраструктура
- Docker Compose — backend + frontend + postgres + mempalace + nginx
- GitHub Actions — CI/CD pipeline
- MCP Server — JSON-RPC 2.0 протокол
- MemPalace MCP — knowledge graph + semantic search

---

## 🚀 Быстрый старт

### Требования
- Java 21+
- Node.js 18+
- (опционально) Ollama для локальных LLM

### Запуск бэкенда
```bash
cd backend
mvn spring-boot:run
# http://localhost:8080
```

### Запуск фронтенда
```bash
cd frontend
npm install
npm run dev
# http://localhost:5173
```

### Docker Compose (полный стек)
```bash
docker-compose up -d
```

### Desktop App
```bash
npm run build      # Build Electron app
npm run electron:preview  # Preview built app
```

---

## 📐 Архитектура

### Выполнение схем
```
Source → Agent → Condition → Loop → Output
         ↓
      Memory (MemPalace)
         ↓
      Subagent (nested workflow)
```

1. **Topological sort** (Kahn's algorithm) — вычисление уровней
2. **Параллельное выполнение** узлов одного уровня
3. **WebSocket события**: progress, log, error, nodeTime, token, wave
4. **Context management**: сбор результатов upstream-узлов + LLM-суммаризация
5. **Variable interpolation**: `{{input}}`, `{{prev_result}}`, `{{node:Name}}`
6. **Execution Modes**: EXECUTE/ANALYZE/DRY_RUN

### LLM Provider Interface
```java
interface LlmProvider {
    String chat(String model, String system, String user, Map config);
    String streamingChat(..., Consumer<String> onToken);
    boolean supportsStreaming();
    boolean isAvailable();
    List<String> listModels();
}
```

### API Endpoints
| Endpoint | Description |
|----------|-------------|
| `GET/POST/PUT/DELETE /api/schemas` | Schema CRUD |
| `POST /api/schemas/{id}/execute?mode=` | Execute with mode |
| `GET /api/schemas/{id}/export/mermaid` | Mermaid diagram |
| `POST /api/remote/workflows/{id}/run` | Remote trigger |
| `GET /api/skills` | Skills management |
| `POST /api/crosscheck` | LLM verification |
| `GET /api/share/t/{token}` | Shared schema |
| `GET /actuator/prometheus` | Metrics |

---

## 📊 Статус реализации

| Категория | Реализовано | Всего | Процент |
|-----------|:-----------:|:-----:|:-------:|
| Визуальный редактор | ✅ | 14 | 100% |
| Выполнение схем | ✅ | 10 | 100% |
| LLM провайдеры | ✅ | 7 | 100% |
| MemPalace | ✅ | 6 | 100% |
| Plan / MCP | ✅ | 7 | 100% |
| Skills System | ✅ | 5 | 100% |
| Remote API | ✅ | 6 | 100% |
| Безопасность | ✅ | 6 | 100% |
| UI/UX | ✅ | 8 | 100% |
| Desktop App | ✅ | 8 | 100% |
| Инфраструктура | ✅ | 5 | 100% |
| **Всего** | | **100** | **97%** |

---

## 🧪 Тесты

```bash
# Backend tests
cd backend
mvn test

# Frontend unit tests
cd frontend
npm run test:unit

# Frontend E2E tests
npm run test:e2e
```

| Тип | Инструменты |
|-----|------------|
| Unit | JUnit 5 + Mockito |
| Integration | SpringBootTest + Testcontainers |
| E2E | Playwright |
| Frontend | Vitest + Vue Test Utils |

---

## 📁 Структура проекта

```
Axolotl/
├── backend/                          # Spring Boot 3.2, Java 21
│   ├── src/main/java/com/agent/orchestrator/
│   │   ├── controller/               # REST endpoints
│   │   │   ├── AgentController.java
│   │   │   ├── SchemaController.java
│   │   │   ├── RemoteApiController.java
│   │   │   ├── SkillController.java
│   │   │   ├── ShareController.java
│   │   │   └── CrossCheckController.java
│   │   ├── service/                 # Business logic
│   │   │   ├── SchemaService.java
│   │   │   ├── SkillService.java
│   │   │   ├── CrossCheckService.java
│   │   │   └── MetricsService.java
│   │   ├── llm/                     # LLM providers
│   │   │   ├── LlmService.java
│   │   │   ├── OllamaProvider.java
│   │   │   ├── OpenAiProvider.java
│   │   │   ├── AnthropicProvider.java
│   │   │   └── CustomLlmProvider.java
│   │   ├── model/                   # Domain objects
│   │   │   ├── WorkflowSchema.java
│   │   │   ├── Node.java
│   │   │   ├── Skill.java
│   │   │   ├── ApiKey.java
│   │   │   └── ShareLink.java
│   │   ├── config/                  # Security, JWT, WebSocket
│   │   └── websocket/               # Execution handler
├── frontend/                         # Vue 3 + TypeScript + Vite
│   └── src/
│       ├── components/
│       │   ├── canvas/              # WorkflowCanvas, CustomEdge
│       │   ├── nodes/               # AgentNode, SourceNode, etc.
│       │   ├── execution/           # ExecutionPanel, ExecutionHistory
│       │   └── memory/             # MemoryGraphView
│       ├── stores/                  # Pinia stores
│       └── composables/            # WebSocket, Toast, Electron
├── electron/                         # Electron desktop app
│   ├── main.ts                      # Main process
│   └── preload.ts                   # Secure IPC bridge
├── .github/workflows/                # CI/CD
│   └── ci.yml
├── e2e/                             # Playwright tests
└── docker-compose.yml               # Full stack deployment
```

---

## 🎯 Key Differentiators

| Feature | Axolotl | n8n | LangFlow |
|---------|:--------:|:---:|:--------:|
| Infinite canvas to chat | ✅ | ❌ | ❌ |
| Real-time token streaming | ✅ | ❌ | ❌ |
| Memory as graph | ✅ | ❌ | ❌ |
| Execution modes | ✅ | ❌ | ❌ |
| Built-in Plan/Todo | ✅ | ❌ | ❌ |
| Subagent workflows | ✅ | ❌ | ❌ |
| Auto-learning Skills | ✅ | ❌ | ❌ |
| Human-in-the-loop core | ✅ | basic | basic |
| LLM Cross-check | ✅ | ❌ | ❌ |
| Local-first privacy | ✅ | ❌ | ❌ |
| Desktop App | ✅ | ✅ | ❌ |

---

## 📝 Лицензия

MIT
