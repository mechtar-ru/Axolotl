---
title: Узлы
description: Обзор всех типов узлов в Axolotl
---

# Узлы

Axolotl предоставляет 12 типов узлов для построения AI workflow.

## Типы узлов

### Ввод / Вывод

| Узел | Описание |
|------|----------|
| [Source](/ru/nodes/source) | Ввод данных в workflow |
| [Output](/ru/nodes/output) | Обработчик результатов |
| [Human](/ru/nodes/human) | Ручное подтверждение |

### AI / Логика

| Узел | Описание |
|------|----------|
| [Agent](/ru/nodes/agent) | LLM вызов с выбором модели |
| [Condition](/ru/nodes/condition) | Ветвление (if/else) |
| [Loop](/ru/nodes/loop) | Итерации с ограничением |

### Память / Контекст

| Узел | Описание |
|------|----------|
| [Memory](/ru/nodes/memory) | Интеграция с MemPalace |
| [Subagent](/ru/nodes/subagent) | Вложенный workflow |

### Безопасность / Утилиты

| Узел | Описание |
|------|----------|
| [Guardrail](/ru/nodes/guardrail) | Валидация данных |
| [Fallback](/ru/nodes/fallback) | Обработка ошибок |
| [Group](/ru/nodes/group) | Группировка узлов |
| [SchemaBuilder](/ru/nodes/schemabuilder) | AI-генерация workflow |

## Общие свойства

Все узлы имеют:
- **Имя** - Нажмите дважды для редактирования
- **Позиция** - Координаты X/Y на канвасе
- **Статус** - idle, running, completed, failed

## Порты ввода / вывода

Узлы соединяются через порты:
- **Верхний порт** - Вход (target)
- **Нижний порт** - Выход (source)
- **Связи** могут иметь тип: `data`, `condition_true`, `condition_false`, `loop`

## Начните с основных узлов

- [Source](/ru/nodes/source) - Ввод данных
- [Agent](/ru/nodes/agent) - LLM вызов
- [Output](/ru/nodes/output) - Результаты
- [Condition](/ru/nodes/condition) - Логика