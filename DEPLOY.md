# Deployment Guide

This guide covers deploying Axolotl to production servers.

## Quick Start with Docker Compose

```bash
# Clone and start
git clone https://github.com/anomalyco/axolotl.git
cd axolotl
docker-compose up -d

# Access at http://localhost:5173
```

## Production Deployment

### Option 1: Docker

```bash
# Backend
docker run -d \
  --name axolotl-backend \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e AXOLOTL_MEMPALACE_URL=http://mempalace:5890 \
  ghcr.io/anomalyco/axolotl/backend:latest

# Frontend
docker run -d \
  --name axolotl-frontend \
  -p 80:80 \
  -e VITE_API_URL=http://localhost:8080/api \
  ghcr.io/anomalyco/axolotl/frontend:latest
```

### Option 2: Kubernetes with Helm

```bash
# Add Helm repository (after chart is published)
helm repo add axolotl https://anomalyco.github.io/axolotl

# Install
helm install axolotl axolotl/axolotl \
  --set ingress.enabled=true \
  --set ingress.host=yourdomain.com

# Or with custom values
helm install axolotl axolotl/axolotl -f values.yaml
```

## Environment Variables

### Backend

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Spring profile |
| `AXOLOTL_DB_PATH` | `schema.db` | SQLite database path |
| `AXOLOTL_JWT_SECRET` | — | JWT signing secret (REQUIRED in production) |
| `AXOLOTL_MEMPALACE_URL` | `http://localhost:5890` | MemPalace MCP URL |
| `AXOLOTL_MEMPALACE_ENABLED` | `false` | Enable MemPalace |
| `OPENAI_API_KEY` | — | OpenAI API key |
| `SPRING_AI_OLLAMA_BASE-URL` | `http://localhost:11434` | Ollama URL |

### Frontend

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_URL` | `http://localhost:8080/api` | Backend API URL |
| `VITE_WS_URL` | `ws://localhost:8080/ws` | WebSocket URL |

## Nginx Reverse Proxy

```nginx
server {
    listen 443 ssl http2;
    server_name axolotl.example.com;

    ssl_certificate /etc/ssl/certs/axolotl.crt;
    ssl_certificate_key /etc/ssl/private/axolotl.key;

    location / {
        proxy_pass http://localhost:80;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    location /api {
        proxy_pass http://localhost:8080;
    }

    location /ws {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "ws";
        proxy_read_timeout 300;
    }
}
```

## Health Checks

- Backend: `GET /api/health`
- Frontend: `GET /`
- Prometheus: `GET /actuator/prometheus`

## Required Secrets

```bash
# Generate JWT secret
openssl rand -base64 32

# Set environment
export AXOLOTL_JWT_SECRET=your-generated-secret
```

## External Services

### MemPalace (Optional)

For memory features, deploy MemPalace:

```bash
docker run -d --name mempalace -p 5890:5890 anomalyco/mempalace
```

### Ollama (Optional)

For local LLM:

```bash
docker run -d --name ollama -p 11434:11434 ollama/serve
```

## Monitoring

### Prometheus Metrics

Metrics available at `/actuator/prometheus`:

- `axolotl_schema_executions_total`
- `axolotl_schema_executions_success`  
- `axolotl_node_executions_total`
- `axolotl_llm_calls_total`
- `axolotl_tool_calls_total`

## Troubleshooting

### View logs
```bash
docker logs axolotl-backend
docker logs axolotl-frontend
```

### Check health
```bash
curl http://localhost:8080/api/health
```

### Restart services
```bash
docker-compose restart
```