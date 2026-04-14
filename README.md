# 🧬 Axolotl — визуальная оркестрация AI-агентов

> *"Рисуй логику, а не пиши её"*

Axolotl — это платформа для визуального создания и выполнения цепочек AI-агентов. Стройте workflow из узлов на бесконечном канвасе, подключайте LLM-провайдеров, управляйте памятью через MemPalace и запускайте выполнение в реальном времени.

---

## ✨ Возможности

### Визуальный редактор
- 🎨 **Infinite Canvas** на VueFlow — drag-and-drop, зум, панорамирование
- 🧩 **9 типов узлов**: Source, Agent, Output, Condition, Loop, Memory, Guardrail, Human, Fallback
- 🔗 **Связи** — типизированные edges (data, condition true/false, loop)
- ↩️ **Undo/Redo** — Cmd+Z / Cmd+Shift+Z
- 📋 **Копирование** — Cmd+C / Cmd+V / Cmd+D
- 🔍 **Поиск** — Cmd+F по имени и типу узла
- 📦 **JSON экспорт/импорт** — полный обмен схемами
- 📊 **Mermaid экспорт/импорт** — диаграммы + парсинг
- 🐍 **Python экспорт** — генерация исполняемого `.py` скрипта
- 📷 **PNG/SVG** — скриншот канваса

### Выполнение схем
- ⚡ **Параллельное выполнение** — независимые ветки через CompletableFuture
- 📡 **WebSocket real-time** — прогресс, логи, токены, метрики, волны
- 🔄 **Token streaming** — посимвольная отдача LLM-ответа
- 🚦 **Convergence monitoring** — счётчик ошибок, порог 3 → `BLOCKED`
- 🛑 **Cancel execution** — остановка по запросу
- 📊 **Execution history** — записи о прошлых запусках

### LLM интеграция
- 🦙 **Ollama** — локальные модели, NDJSON streaming
- 🤖 **OpenAI** — GPT-4o/mini, SSE streaming
- 🧠 **Anthropic** — Claude Sonnet/Opus/Haiku, SSE streaming
- 🔍 **DeepSeek** — бюджетная модель
- 🎯 **Модель на узел** — каждый AgentNode выбирает свою модель
- 💡 **Key storage** — API ключи: SQLite → env vars → application.yml

### MemPalace — долговременная память
- 🧠 **Memory Node** — поиск по памяти, фильтрация wing/room
- 💾 **Auto-save** — результаты агентов → MemPalace автоматически
- 🔗 **Graph context** — taxonomy + tunnels → injection в systemPrompt
- 🔎 **Semantic search** — cosine similarity

### Plan / Workspace
- 📋 **Todo-лист** — задачи со статусами и приоритетами
- ✍️ **Batch add** — массовое добавление через textarea
- ✅ **Acceptance criteria** — валидация при переходе в DONE
- 🔗 **Node linking** — привязка задачи к узлу на канвасе
- 🤖 **MCP сервер** — 7 инструментов через JSON-RPC 2.0 на `/mcp`

### Безопасность
- 🔐 **JWT авторизация** — регистрация/вход
- 👥 **Multi-tenancy** — изоляция схем по пользователям
- 🔑 **Settings API** — CRUD API ключей провайдеров

### UI/UX
- 🌙 **Тёмная тема** — #1e1e2e фон, #6c63ff акцент
- ⌨️ **Command Palette** — Cmd+K быстрый доступ
- 🎓 **Onboarding** — 2-step wizard при первом визите
- 🎬 **Анимации** — пульсация running, свечение completed, тряска failed

---

## 🛠️ Технологии

### Бэкенд
| Технология | Версия |
|-----------|--------|
| Java | 21 |
| Spring Boot | 3.2 |
| SQLite | 3.x |
| WebSocket | Spring |
| GraalJS | JS engine для Condition Node |
| SLF4J | Logging |

### Фронтенд
| Технология | Версия |
|-----------|--------|
| Vue | 3 (Composition API) |
| TypeScript | 5.x |
| VueFlow | 1.x |
| Vite | 5.x |
| Pinia | State management |

### Инфраструктура
- Docker Compose — backend + frontend + postgres + mempalace + nginx
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

---

## 📐 Архитектура

### Выполнение схем
```
Source → Agent → Condition → Loop → Output
         ↓
      Memory (MemPalace)
```

1. **Topological sort** (Kahn's algorithm) — вычисление уровней
2. **Параллельное выполнение** узлов одного уровня
3. **WebSocket события**: progress, log, error, nodeTime, token, wave
4. **Context management**: сбор результатов upstream-узлов + LLM-суммаризация
5. **Variable interpolation**: `{{input}}`, `{{prev_result}}`, `{{node:Name}}`

### LLM Provider Interface
```java
interface LlmProvider {
    String chat(String model, String system, String user, Map config);
    String streamingChat(..., Consumer<String> onToken);  // real-time tokens
    boolean supportsStreaming();
    boolean isAvailable();
    List<String> listModels();
}
```

---

## 📊 Статус реализации

| Категория | Реализовано | Всего | Процент |
|-----------|:-----------:|:-----:|:-------:|
| Визуальный редактор | ✅ | 12 | 100% |
| Выполнение схем | ✅ | 8 | 100% |
| LLM провайдеры | ✅ | 5 | 100% |
| MemPalace | ✅ | 4 | 100% |
| Plan / MCP | ✅ | 7 | 100% |
| Безопасность | ✅ | 5 | 100% |
| UI/UX | ✅ | 6 | 100% |
| Тесты | ✅ | 90/90 | 100% |

---

## 🧪 Тесты

```bash
cd backend
mvn test
# 90 tests — 90 pass ✅
```

| Тип | Инструменты |
|-----|------------|
| Unit | JUnit 5 + Mockito |
| Integration | SpringBootTest + TestRestTemplate |
| Frontend | Vitest + Vue Test Utils |

---

## 📁 Структура проекта

```
Axolotl/
├── backend/                          # Spring Boot 3.2, Java 21
│   ├── src/main/java/com/agent/orchestrator/
│   │   ├── controller/               # REST endpoints
│   │   ├── service/                  # Business logic
│   │   ├── repository/               # SQLite DAO
│   │   ├── model/                    # Domain objects
│   │   ├── mcp/                      # MCP Server
│   │   ├── llm/                      # LLM providers
│   │   ├── config/                   # Security, JWT, WebSocket
│   │   └── websocket/                # Execution handler
│   └── schema.db                     # SQLite database
├── frontend/                         # Vue 3 + TypeScript + Vite
│   └── src/
│       ├── components/               # Canvas, nodes, execution, plan, memory
│       ├── views/                    # Home, Settings, Login
│       ├── stores/                   # Pinia stores
│       └── composables/              # WebSocket, Toast
└── docker-compose.yml                # Full stack deployment
```

---

## 📝 Лицензия

MIT
