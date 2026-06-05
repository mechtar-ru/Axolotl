# App Creation Workflow — Axolotl Implementation Plan

**Status:** Planned  
**Priority:** High  
**Theme:** Pipeline / Design / Planning  
**Dependencies:** None (Doc-Agent 48 — parallel track)  
**Estimate:** 5–7 days (phased: 3 phases)

---

## Overview

Complete rework of the app creation pipeline from one-shot generation to a structured 4-phase workflow: **Design → Plan → Implement → Document**.

---

## The 4-Phase Workflow

```
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 1: DESIGN                                                │
│                                                                  │
│  [Receive] → дизайн-док или текст описания                       │
│  [Review (design mode)]                                          │
│    ├── анализ (premortem / prism / postmortem)                   │
│    ├── вопросы пользователю (NEEDS_INFO)                          │
│    └── diff (предыдущая версия / предыдущий review)              │
│  ✋ User approves                                                │
│  → design/initial_design.md  (если нет)                          │
│  → design/features/<N>-<kebab-name>.md (если декомпозиция)       │
│  → ✋ User approves каждую фичу                                   │
├─────────────────────────────────────────────────────────────────┤
│  PHASE 2: PLANNING                                              │
│                                                                  │
│  [Planner] → план имплементации со steps + depends_on             │
│  [Review (plan mode)]                                            │
│    ├── премортем / анализ                                         │
│    ├── вопросы пользователю                                       │
│    └── diff с предыдущим планом                                   │
│  ✋ User approves → Neo4j: шаги с графом зависимостей             │
│  → plan/implementation_plan.md (snapshot)                         │
│  → Декомпозиция (LLM) в шаги в Neo4j                              │
│  → ✋ User approves каждый шаг                                     │
├─────────────────────────────────────────────────────────────────┤
│  PHASE 3: IMPLEMENTATION                                        │
│                                                                  │
│  [Prep]                                                          │
│    ├── plan/pseudo-frontend.md — контракт API/компонентов        │
│    ├── plan/pseudo-backend.md — контракт эндпоинтов/моделей      │
│    └── tests/ — тесты по pseudocode                              │
│                                                                  │
│  [Agent] → читает Neo4j: первый pending шаг с fulfilled deps    │
│         → читает pseudocode (контракт)                           │
│         → пишет код                                              │
│         → Plan API: status=done                                  │
│                                                                  │
│  [Verifier]                                                      │
│    ├── тесты проходят?                                            │
│    ├── план выполнен? (все шаги done)                             │
│    ├── дизайн реализован? (по feature doc)                        │
│    └── код соответствует pseudocode? (surface check)              │
│  ❌ Gap → Neo4j: шаг → incomplete / rejected                     │
├─────────────────────────────────────────────────────────────────┤
│  PHASE 4: DOCUMENTING                                           │
│                                                                  │
│  [Doc-Agent] (см. 48-doc-agent-mode-plan.md)                     │
│    ├── .axolotl/spec.md (append)                                 │
│    ├── .axolotl/changelog.md (session entry)                     │
│    ├── design/features/<N>-<name>.md (новые фичи)                │
│    └── plan/steps/*.md snapshot (синк на диск из Neo4j)           │
│                                                                  │
│  [Output] → pipeline-report.md (как сейчас)                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## Node Types (final)

| Type | Role | Status |
|------|------|--------|
| `source` | Приём дизайн-дока / текста | ✅ As-is |
| `review` | Анализ, вопросы diff. Единый диалог для design/plan | 🔧 Extend |
| `planner` | Генерация плана + steps с depends_on | 🆕 New |
| `prep` | Pseudocode (контракт) + тесты | 🆕 New |
| `agent` | Реализация по шагам плана | 🔧 Extend |
| `verifier` | +coverage плана, +coverage дизайна | 🔧 Extend |
| `doc-agent` | Обновление документации | 🆕 New (48) |
| `output` | Финальный отчёт | ✅ As-is |

---

## Node Details

### Review (extended)

**Новое в findings:**
```json
{
  "findings": [
    { "type": "analysis", "text": "Design lacks target audience..." },
    { "type": "question", "text": "Кто целевая аудитория?" },
    { "type": "question", "text": "Нужна ли авторизация?" },
    { "type": "diff", "old": "...", "new": "..." }
  ],
  "plan": "...",
  "mode": "manual",
  "status": "needs_info"  // NEW: вместо needs_approval когда есть вопросы
}
```

- `type: "question"` → UI показывает поле для ввода
- `type: "diff"` → UI показывает unified diff (предыдущая версия / предыдущий review)
- После ответа пользователя → регенерация с ответами в контексте
- Approve → запись дизайна/плана
- Reject → отмена

**Diff logic:**
- Если review запускался N раз → сравнение с (N-1) результатом
- Всегда доступен diff с оригинальным доком (initial_design.md / первый review)
- Diff показывается всегда (не по кнопке)

**Единый диалог** — один компонент `ReviewApprovalDialog`:
- Секция diff (сверху)
- Секция analysis (premortem/prism/postmortem findings)
- Секция questions (input поля для ответов)
- Кнопки: Approve | Edit & Regenerate | Reject

### Planner (new)

- Отдельный тип узла (`type: "planner"`)
- Принимает: design/ файлы как контекст
- Генерирует: план имплементации со steps
- **LLM решает** когда декомпозировать на шаги
- Записывает шаги в Neo4j через Plan API
- Каждый шаг:
  ```json
  {
    "id": 3,
    "title": "Добавить модель EmotionEntry",
    "description": "Реализовать модель данных...",
    "depends_on": [1, 2],
    "status": "pending",
    "created_at": "..."
  }
  ```
- Возможные статусы: `pending` → `in_progress` → `done` | `rejected` | `incomplete`
- Snapshot на диск: `plan/implementation_plan.md` + `plan/steps/<N>-<kebab-name>.md`

### Prep (new)

- Один узел (`type: "prep"`), делает и pseudocode и тесты
- **Pseudocode** — не временный файл, а **контракт**:
  - `plan/pseudo-frontend.md` — компоненты, пропсы, стейт, события, роуты
  - `plan/pseudo-backend.md` — эндпоинты, модели, сервисы, middleware
- **Tests** — генерирует тесты на основе pseudocode API
- Agent сверяется с pseudocode при реализации
- Verifier проверяет код на соответствие pseudocode

### Agent (extended)

- При запуске читает Plan API: первый `pending` шаг, где все `depends_on` — `done`
- Загружает pseudocode в контекст
- Пишет код под конкретный шаг
- После завершения → `Plan API: status=done`
- Без активных шагов → ждёт / завершается

### Verifier (extended)

Три проверки:
1. **Тесты** — существующая логика (PASS/FAIL checks)
2. **Coverage плана** — все ли шаги `done`? Если нет → gap
3. **Coverage дизайна** — все ли features из `design/` реализованы (LLM-powered check)
4. **Pseudocode compliance** — код соответствует API из pseudocode? (surface check)

**Gap handling:**
- Непокрытый пункт → `plan/steps/<N>.md` → `status: incomplete`
- Причина gap → `plan/steps/<N>.md` → `reason: "..."`

---

## Plan Storage: Neo4j + File Sync

**Source of truth: Neo4j**
- Шаги как узлы (`PlanStep`) с relationship-ами (`DEPENDS_ON`)
- Атомарные обновления статуса (Cypher `MATCH...SET`)
- Графовые запросы: `ready`, `blocked`, `blocker chain`
- Кросс-схемные запросы (дашборд)

**File sync (read-only snapshot):**
- После завершения пайплайна doc-agent пишет `plan/steps/*.md` на диск
- Frontmatter: `id`, `title`, `depends_on`, `status`
- Для git history и ручного просмотра (не редактирования)
- Plan API читает из Neo4j, не из файлов

### Plan API

Существующий `POST /api/plan/tasks` + MCP переделывается:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/plan/steps` | GET | Все шаги схемы |
| `/api/plan/steps/{id}` | GET | Один шаг |
| `/api/plan/steps` | POST | Создать шаг |
| `/api/plan/steps/{id}/status` | PUT | Обновить статус |
| `/api/plan/graph` | GET | Dependency graph (что ready, что blocked) |
| `/api/plan/sync` | POST | Snapshot → файлы на диск |

MCP tools (same):
- `read_plan` → dependency graph + статусы
- `add_task` → создать шаг
- `update_task_status` → сменить статус

### Directory structure on disk (snapshot)

```
targetPath/
  design/
    initial_design.md
    features/
      1-database-service.md
      2-emotion-entry-model.md
      ...
  plan/
    implementation_plan.md
    steps/
      1-emotion-entry-model.md
      2-database-service.md
      3-home-screen-ui.md
      ...
    done/        # шаги со status=done
    rejected/    # шаги со status=rejected
    later/       # deferred шаги
```

---

## Default Pipeline Template (App Creation)

```
source → review (design) → planner → review (plan) → prep → agent → verifier → doc-agent → output
```

Короткая версия (без design review):
```
source → planner → prep → agent → verifier → output
```

Минимальная (только код, без плана):
```
source → agent → verifier → output
```

---

## UI Components — Новые и изменённые

| Компонент | Изменение |
|-----------|-----------|
| `ReviewApprovalDialog` | + секция questions (input поля). + секция diff (unified diff viewer) |
| `DiffViewer.vue` | **Новый**. Показывает unified diff между двумя текстами |
| `PlannerBlock.vue` | **Новый**. Визуализация для planner-узла (список шагов) |
| `PrepBlock.vue` | **Новый**. Визуализация для prep-узла (ссылки на pseudocode) |
| `BlockConfigPanel` | + planner, prep, doc-agent типы |
| `PlanView.vue` | **Новый/расширение**. Визуализация dependency graph |
| `BlueprintView` | Поддержка новых типов узлов |

---

## Implementation Order

### Phase 1: Foundation (2-3 days)

1. Neo4j: `PlanStep` node + `DEPENDS_ON` relationship
2. Plan API: CRUD для steps + graph endpoint
3. Plan MCP: `read_plan`, `add_task`, `update_task_status` переделаны на Neo4j
4. PipelineService: новый pipeline template

### Phase 2: New Nodes (2-3 days)

5. `PlannerNodeStrategy` — генерация плана + steps
6. `PrepNodeStrategy` — pseudocode + tests
7. `AgentNodeStrategy` — интеграция с Plan API (чтение шагов)
8. `VerifierNodeStrategy` — coverage плана + дизайна

### Phase 3: Review & UI (2-3 days)

9. `ReviewApprovalDialog` — redesign: questions, diff, единый интерфейс
10. `DiffViewer.vue`
11. Новые блоки в BlueprintView
12. Plan API → doc-agent sync to disk

### Parallel track

13. Doc-Agent (48-doc-agent-mode-plan.md) — можно параллельно с Phase 1-2

---

## Edge Cases

| Scenario | Behaviour |
|----------|-----------|
| Пустой дизайн-док | Review задаёт вопросы, не уходит в Approve |
| Один шаг в плане | Нет декомпозиции — plan/steps/ содержит 1 файл |
| Все шаги done | Verifier PASS → doc-agent |
| Есть incomplete шаги | Verifier FAIL → gap записан → user решает retry/reject |
| Pseudocode меняется между сессиями | Diff в review показывает изменения контракта |
| Нет pseudocode (старая схема) | Prep пропускается, Agent работает без контракта |
| Multi-agent (future) | Один agent на шаг — координация через Plan API |
| Смена targetPath | Plan в Neo4j не теряется (snapshot на диске — только для чтения) |

---

## Links

- Doc-Agent Mode: `48-doc-agent-mode-plan.md`
- Multi-agent future: `.ideas/misc/multi-agent-dev.md` (TODO)
