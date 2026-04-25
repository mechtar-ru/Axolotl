---
title: Начало работы
description: Начните работу с Axolotl - визуальная оркестрация AI-агентов
---

# Начало работы

Добро пожаловать в Axolotl! Это руководство поможет вам настроить и запустить первый AI workflow.

## Требования

- **Java** 21+
- **Node.js** 18+
- (Опционально) **Docker** для полного стека
- (Опционально) **Ollama** для локальных LLM моделей

## Быстрый старт

### 1. Запуск бэкенда

```bash
cd backend
mvn spring-boot:run
```

Бэкенд доступен на [http://localhost:8080](http://localhost:8080)

### 2. Запуск фронтенда

```bash
cd frontend
npm install
npm run dev
```

Фронтенд доступен на [http://localhost:5173](http://localhost:5173)

### 3. Docker Compose (полный стек)

```bash
docker-compose up -d
```

Запускает: бэкенд, фронтенд, PostgreSQL, MemPalace и nginx.

## Настройка

Создайте файл `.env` в корне проекта:

```bash
# API Ключи
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...

# Ollama (для локальных моделей)
OLLAMA_BASE_URL=http://localhost:11434

# MemPalace
MEMPALACE_URL=http://localhost:5890

# Cloudflare Workers AI
CLOUDFLARE_ACCOUNT_ID=your_account_id
CLOUDFLARE_API_TOKEN=your_token
```

## Создание первого workflow

1. Откройте [http://localhost:5173](http://localhost:5173)
2. Нажмите **+ Новая схема**
3. Добавьте узел **Source** (кнопка на панели инструментов)
4. Добавьте узел **Agent**
5. Соедините их, перетащив от выхода Source ко входу Agent
6. Дважды кликните Source → введите текст
7. Дважды кликните Agent → выберите модель и напишите промпт
8. Нажмите **Выполнить** ▶️

## Следующие шаги

- [Узнать о узлах](/ru/nodes/)
- [Справочник API](/ru/api)
- [Репозиторий на GitHub](https://github.com/mechtar-ru/Axolotl)

## Устранение проблем

**Бэкенд не запускается?**
```bash
cd backend
mvn clean compile
mvn spring-boot:run
```

**Фронтенд показывает ошибки подключения?**
Убедитесь, что бэкенд запущен на порту 8080.

**LLM вызовы не работают?**
Проверьте API ключи в Настройках (⚙️) или файле `.env`.