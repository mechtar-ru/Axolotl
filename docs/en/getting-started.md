---
title: Getting Started
description: Get started with Axolotl - Visual AI Agent Orchestration
---

# Getting Started

Welcome to Axolotl! This guide will help you set up and run your first AI workflow.

## Requirements

- **Java** 21+
- **Node.js** 18+
- (Optional) **Docker** for full stack deployment
- (Optional) **Ollama** for local LLM models

## Quick Start

### 1. Start Backend

```bash
cd backend
mvn spring-boot:run
```

Backend runs on [http://localhost:8080](http://localhost:8080)

### 2. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend runs on [http://localhost:5173](http://localhost:5173)

### 3. Docker Compose (Full Stack)

```bash
docker-compose up -d
```

This starts: backend, frontend, PostgreSQL, MemPalace, and nginx.

## Configuration

Create `.env` file in project root:

```bash
# API Keys
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...

# Ollama (if using local models)
OLLAMA_BASE_URL=http://localhost:11434

# MemPalace
MEMPALACE_URL=http://localhost:5890

# Cloudflare Workers AI
CLOUDFLARE_ACCOUNT_ID=your_account_id
CLOUDFLARE_API_TOKEN=your_token
```

## Create Your First Workflow

1. Open [http://localhost:5173](http://localhost:5173)
2. Click **+ New Schema**
3. Add a **Source** node (click the button in toolbar)
4. Add an **Agent** node
5. Connect them by dragging from Source output to Agent input
6. Double-click Source → enter input text
7. Double-click Agent → select model and write prompt
8. Click **Execute** ▶️

## Next Steps

- [Learn about Nodes](/en/nodes/)
- [API Reference](/en/api)
- [GitHub Repository](https://github.com/mechtar-ru/Axolotl)

## Troubleshooting

**Backend won't start?**
```bash
cd backend
mvn clean compile
mvn spring-boot:run
```

**Frontend shows connection errors?**
Make sure backend is running on port 8080.

**LLM calls failing?**
Check your API keys in Settings (⚙️) or `.env` file.