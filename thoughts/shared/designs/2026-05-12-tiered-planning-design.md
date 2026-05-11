date: 2026-05-12
topic: "Tiered Planning — уровневый подход к генерации в DesignWorkspaceUI"
status: validated

## Problem Statement

DesignWorkspaceUI генерирует черновик игры/приложения одним долгим вызовом LLM
(до 1 часа). Пользователь не получает промежуточного фидбека, не может скорректировать
направление на раннем этапе. Если результат не соответствует ожиданиям — время
потеряно.

## Constraints

- **Выбор моделей за пользователем**: для каждого уровня планирования пользователь
  выбирает модель (fast / medium / execution). Настройки хранятся в метаданных схемы.
- **Минимум новых эндпоинтов**: один эндпоинт `POST /api/schemas/{id}/plan`.
- **Никаких изменений execution engine**: уровни 1-2 работают через отдельный
  лёгкий LLM-вызов, а не через основную схему с 25 итерациями.
- **Level 3 (Execute) использует существующий `generateDraft()`** — он уже умеет
  обновлять sourceData и запускать executeSchema.

## Approach

Трёхуровневое планирование внутри DesignWorkspaceUI:

### Level 0 — Concept
Пользователь пишет промпт в текстовом поле. Без изменений.

### Level 1 — Outline (fast model)
Кнопка "Generate Draft" запускает **один LLM-вызов** к fast-модели.
- System prompt для планирования (хранится на бэкенде)
- Ответ: upper-level план + вопросы с вариантами по умолчанию
- Показывается в Review tab

### Level 2 — Refine (medium model)
Пользователь редактирует план, отвечает на вопросы, жмёт "Refine Plan".
- Один LLM-вызов к medium-модели
- Передаётся оригинальный промпт + правки пользователя + ответы на вопросы
- Ответ: детальный design document
- Показывается в Review tab

### Level 3 — Execute (execution model)
Пользователь жмёт "Execute Plan".
- Финальный план записывается в `sourceData` source-ноды
- Запускается `schemaApi.executeSchema(id)` — существующий код
- Результат появляется в Output tab

## Architecture

### Новый эндпоинт

```
POST /api/schemas/{id}/plan
{
  prompt: string,           // оригинальный промпт пользователя
  level: "outline" | "refine",
  model: string,            // выбранная пользователем модель
  context?: {
    outline: string,         // предыдущий план (для refine)
    userEdits: string,       // правки пользователя
    answers: Record<string, string>  // ответы на вопросы
  }
}

→ {
  type: "outline" | "refine",
  content: string,
  questions?: Question[]    // только для outline
}

interface Question {
  id: string,
  text: string,
  defaultAnswer: string,
  options?: string[]        // варианты для pick
}
```

### Системные промпты

Два промпта на бэкенде (в ресурсах или коде):

- **Outline prompt**: "На основе промпта пользователя составь краткий план
  и задай уточняющие вопросы. Формат: JSON."
- **Refine prompt**: "На основе оригинального промпта, плана, правок
  пользователя и ответов на вопросы составь детальный design document."

### Хранение настроек

Новое поле в `WorkflowSchema`:

```typescript
planningModels: {
  fast: string,      // модель для outline (e.g. "gpt-4o-mini")
  medium: string     // модель для refine (e.g. "deepseek-chat")
  // execution берётся из schema.defaultModel
}
```

По умолчанию:
- fast: `gpt-4o-mini` (если не задана — берётся `defaultModel`)
- medium: `deepseek-chat` (если не задана — берётся `defaultModel`)

### UI изменения DesignWorkspaceUI

- Кнопка ⚙️ рядом с "Generate Draft" → оверлей с выбором fast/medium моделей
- Review tab делится на два состояния: outline (с вопросами) и refined plan
- Кнопка "Refine Plan" появляется после outline
- Кнопка "Execute Plan" — запускает Level 3

## Data Flow

```
[Concept: type prompt]
  → [⚙️ select fast model] → Generate Draft
    → POST /api/schemas/{id}/plan { level: "outline", model: fast }
    → [Review: план + вопросы, редактируемые поля]
    → Refine Plan
      → POST /api/schemas/{id}/plan {
           level: "refine",
           model: medium,
           context: { outline, userEdits, answers }
         }
      → [Review: детальный план]
      → Execute Plan
        → schemaApi.updateSchema (sourceData = финальный план)
        → schemaApi.executeSchema (execution model)
        → [Output: файлы]
```

## Components

### Backend
- **`POST /api/schemas/{id}/plan`** — новый контроллер/метод
- **`PlanningService.java`** — выбирает модель, формирует system prompt,
  делает один LLM-вызов, парсит JSON-ответ
- System prompts: два шаблона (outline / refine)

### Frontend
- **DesignWorkspaceUI.vue** — три фазы (concept → outline → refine → execute)
- **PlanningModelsPicker.vue** (новый) — оверлей выбора fast/medium моделей
- **Вопросы**: редактируемые поля с вариантами (radio / text) в Review tab

## Error Handling

- Если fast или medium модель недоступна — падаем на `defaultModel` схемы
- Если LLM ответил не JSON-ом — показываем raw текст как план
- Если outline запрос упал — остаёмся на Concept tab с ошибкой
- Если refine запрос упал — показываем старый outline, не теряем прогресс

## Open Questions

- Нужен ли rate limiting для plan эндпоинта?
- Стоит ли кешировать результат outline если пользователь его не менял?
- Нужен ли отдельный UI компонент для вопросов или достаточно plain text?

