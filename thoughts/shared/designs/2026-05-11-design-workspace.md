date: 2026-05-11
topic: "DesignWorkspaceUI — единый дизайн-воркспейс для генеративных app типов"
status: draft

## Problem Statement

Сейчас `LiveView.vue` использует разные runtime UI для разных app типов:
- `ChatAppUI` — для CHAT
- `DocAnalyzerAppUI` — для ANALYZER
- `GameAppUI` — для GAME
- `GenericAppUI` — для всего остального (GENERATOR, EMAIL, CUSTOM)

Проблема: для генеративных app типов (GAME, GENERATOR) результат — не runtime-интерфейс, а **артефакты** (файлы, документы, планы). Нужен воркспейс, который отражает процесс:
1. **Concept** — пользователь описывает идею (промт)
2. **Review** — AI генерирует план, пользователь критикует и утверждает
3. **Output** — AI генерирует финальный артефакт, пользователь скачивает

## Constraints

1. Должен быть единый компонент для GAME и GENERATOR (и любых будущих генеративных типов)
2. CHAT и ANALYZER остаются на своих runtime UI (ChatAppUI, DocAnalyzerAppUI)
3. EMAIL и CUSTOM остаются на GenericAppUI (как runtime fallback)
4. Компонент должен работать через существующий execution engine (WebSocket, nodeResults)
5. Файлы скачиваются через Blob + anchor download (без серверного API)
6. Никакого встроенного превью — только скачивание

## Approach

Создать `DesignWorkspaceUI.vue` — трёхвкладочный дизайн-воркспейс, который заменяет `GameAppUI.vue` и становится новым рантаймом для GAME и GENERATOR app типов.

## Architecture

### LiveView routing (новое)

```typescript
CHAT        → ChatAppUI
ANALYZER    → DocAnalyzerAppUI
GAME        → DesignWorkspaceUI
GENERATOR   → DesignWorkspaceUI
EMAIL       → GenericAppUI
CUSTOM      → GenericAppUI (fallback)
```

### Компонент DesignWorkspaceUI

```
┌──────────────────────────────────────────────────────────┐
│ [Concept]  │  [Review]  │  [Output]                       │
│ ───────────────────────────────────────────────────────── │
│                                                           │
│  Tab 1: CONCEPT                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Textarea: "Tower defense с драконами и магией..."  │  │
│  │  [Generate Draft]                                   │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  Tab 2: REVIEW (active after draft generated)            │
│  ┌─────────────────────┬──────────────────────────────┐  │
│  │  Rendered Plan       │  Critique:                   │  │
│  │  (read-only GDD)     │  [textarea]                  │  │
│  │                      │                              │  │
│  │  - геймплей          │  [Refine with Critique]      │  │
│  │  - уровни            │                              │  │
│  │  - архитектура       │  [Approve & Generate]        │  │
│  │  - UI sketch         │                              │  │
│  └─────────────────────┴──────────────────────────────┘  │
│                                                           │
│  Tab 3: OUTPUT (active after approval)                   │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Сгенерированные файлы:                             │  │
│  │  ✅ game.html (12.4 KB)  [Download]                │  │
│  │  ✅ gdd.md (3.2 KB)      [Download]                │  │
│  │  ✅ assets.zip (1.1 MB)  [Download]                │  │
│  │                                                      │  │
│  │  [Download All as ZIP]                               │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### Data flow

```
┌──────────────┐    execution run 1    ┌──────────────┐
│   Concept     │ ─── Source→Agent →──→ │  Review tab  │
│  (пользоват.  │   (generate GDD)      │ (план готов)  │
│   промт)     │                       │              │
└──────────────┘                       └──────┬───────┘
                                              │ пользователь
                                              │ пишет critique
                                              ▼
                                    ┌──────────────────┐
                                    │  AI refinement    │
                                    │  execution run 2  │
                                    │  (optional,       │
                                    │   итеративно)     │
                                    └────────┬─────────┘
                                             │ пользователь
                                             │ нажимает Approve
                                             ▼
┌──────────────┐    execution run 3    ┌──────────────┐
│  Approve      │ ─── Source→Agent──→──│  Output tab   │
│  (gate)       │   (generate files)   │ (файлы готовы)│
└──────────────┘                       └──────────────┘
                                             │ скачивание
                                             ▼
                                        Локальный диск
```

### Props

```typescript
interface DesignWorkspaceProps {
  appType: 'GAME' | 'GENERATOR'
  executionResult: any  // injected from LiveView — последний output
}
```

### State (внутренний)

```typescript
interface DesignWorkspaceState {
  activeTab: 'concept' | 'review' | 'output'
  conceptPrompt: string
  plan: string | null           // rendered GDD/document
  critiquePrompt: string
  files: GeneratedFile[]        // список файлов для скачивания
  phase: 'ideation' | 'review' | 'generating' | 'complete'
}
```

### Взаимодействие с execution engine

DesignWorkspaceUI не запускает execution сам — он подписывается на `nodeResults` через LiveView и реагирует на изменения.

**Фаза 1 — Concept:**
- Пользователь пишет промт, нажимает "Generate Draft"
- Если есть активный execution на blueprint, он запускается обычным способом (через StudioView → toggleRun)
- DesignWorkspaceUI просто показывает результат в Review tab, когда `executionResult` обновляется

**Фаза 2 — Review:**
- План отображается как отформатированный markdown
- Critique: пользователь пишет замечания, нажимает "Refine"
- AI дообновляет план (результат показывается в том же Review tab)
- Кнопка "Approve & Generate" → пользователь возвращается на blueprint и запускает финальный execution
- Либо: пользователь сам управляет execution через blueprint, а DesignWorkspaceUI только показывает плоды

**Важно:** На первых порах DesignWorkspaceUI — это **пассивный вьювер**, который отображает результаты execution'а с блюпринта. Управление execution'ом остаётся на блюпринте (кнопка Run). DesignWorkspaceUI только:
- Показывает план на Review tab
- Позволяет писать critique (который потом можно скопировать в blueprint для следующего запуска)
- Показывает файлы и позволяет скачать на Output tab

### Скачивание файлов

```typescript
function downloadFile(filename: string, content: string, type: string) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
```

Файлы приходят как часть `executionResult` — node с типом `output` или агент возвращает объект `{ files: [{ name, content, type }] }`.

## Components

### Новые
1. **`frontend/src/components/live/DesignWorkspaceUI.vue`** — главный компонент воркспейса

### Удаляемые
1. **`frontend/src/components/live/GameAppUI.vue`** — заменяется DesignWorkspaceUI
2. **`frontend/src/components/live/GameAppUI.test.ts`** — тест старого компонента

### Изменяемые
1. **`frontend/src/components/studio/LiveView.vue`** — добавить DesignWorkspaceUI для GAME и GENERATOR
2. **`frontend/src/types/index.ts`** — добавить тип `DesignFile` (опционально)

## Error Handling

- Если execution ещё не запущен: Review tab показывает "Run the workflow from Blueprint to see results"
- Если execution завершился ошибкой: Review tab показывает ошибку с кнопкой "Retry"
- Если результат не содержит ожидаемых файлов: Output tab показывает "No files generated"
- Если critique не приводит к изменению плана: сообщить пользователю, что план не изменился

## Testing Strategy

1. **Unit test**: DesignWorkspaceUI рендерится с правильными пропсами
2. **Unit test**: Концепт → ввод текста → Generate Draft вызывает execution
3. **Unit test**: Review tab показывает план после получения executionResult
4. **Unit test**: Скачивание файла создаёт Blob и триггерит клик
5. **Integration**: LiveView правильно роутит GAME → DesignWorkspaceUI
6. **Integration**: LiveView правильно роутит GENERATOR → DesignWorkspaceUI

## Open Questions

1. Как critique передаётся в blueprint для повторного запуска? — Пока через копирование пользователем, в будущем можно через store.
2. Нужна ли возможность переключать фазы не по порядку? — Пока нет, движение Concept → Review → Output последовательное.
3. Должен ли DesignWorkspaceUI сам запускать execution или только реагировать? — Пока только реагировать (пассивный вьювер).
