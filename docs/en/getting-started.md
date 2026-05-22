# Getting Started

## Prerequisites

- Java 21
- Node.js 20+
- Maven
- Neo4j 5 (community or enterprise)

## Installation

### 1. Clone and build

```bash
git clone https://github.com/mechtar-ru/Axolotl.git
cd Axolotl
```

### 2. Start Neo4j

```bash
docker run -d \
  --name axolotl-neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/axolotl2026 \
  neo4j:5-enterprise
```

Neo4j browser UI: [http://localhost:7474](http://localhost:7474)

### 3. Start the backend

```bash
cd backend
mvn spring-boot:run -Dserver.port=8082
```

Or use the dev script:

```bash
scripts/dev.sh start
```

The backend runs on [http://localhost:8082](http://localhost:8082).

### 4. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend runs on [http://localhost:5173](http://localhost:5173).

### 5. Login

Default credentials:

| Username | Password | Role |
|----------|----------|------|
| `admin` | `admin` | Administrator |
| `tech` | `tech` | Automation / harness |

## Quick Start: Create Your First Schema

1. Open the frontend at [http://localhost:5173](http://localhost:5173)
2. Click **Quick Start** on the dashboard
3. Enter a description (e.g. "Build a chat bot")
4. A 5-stage pipeline is created: Receive → Review → Agent → Verify → Output
5. In Studio, click **Build Pipeline** then **Execute**
6. The pipeline pauses at the Review stage for approval
7. Review the plan and approve to continue

## Next Steps

- [Pipeline System](/en/pipeline) — learn about the multi-stage pipeline
- [Node Types](/en/nodes) — reference for all node types
- [Architecture](/en/architecture) — system design overview
