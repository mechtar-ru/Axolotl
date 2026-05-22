# Начало работы

## Требования

- Java 21
- Node.js 20+
- Maven
- Neo4j 5 (community или enterprise)

## Установка

### 1. Клонирование и сборка

```bash
git clone https://github.com/mechtar-ru/Axolotl.git
cd Axolotl
```

### 2. Запуск Neo4j

```bash
docker run -d \
  --name axolotl-neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/axolotl2026 \
  neo4j:5-enterprise
```

Neo4j браузер: [http://localhost:7474](http://localhost:7474)

### 3. Запуск бэкенда

```bash
cd backend
mvn spring-boot:run -Dserver.port=8082
```

Или через dev-скрипт:

```bash
scripts/dev.sh start
```

Бэкенд запускается на [http://localhost:8082](http://localhost:8082).

### 4. Запуск фронтенда

```bash
cd frontend
npm install
npm run dev
```

Фронтенд запускается на [http://localhost:5173](http://localhost:5173).

### 5. Вход

Учётные данные по умолчанию:

| Логин | Пароль | Роль |
|-------|--------|------|
| `admin` | `admin` | Администратор |
| `tech` | `tech` | Автоматизация / harness |

## Быстрый старт: создание первой схемы

1. Откройте фронтенд [http://localhost:5173](http://localhost:5173)
2. Нажмите **Quick Start** на панели управления
3. Введите описание (например, «Создай чат-бота»)
4. Будет создан 5-этапный пайплайн: Receive → Review → Agent → Verify → Output
5. В Studio нажмите **Build Pipeline** затем **Execute**
6. Пайплайн остановится на этапе Review для утверждения плана
7. Просмотрите план и нажмите Approve для продолжения

## Дальнейшее чтение

- [Pipeline System](/ru/pipeline) — документация многозвенного пайплайна
- [Типы узлов](/ru/nodes) — справочник по типам узлов
- [Архитектура](/ru/architecture) — обзор архитектуры системы
