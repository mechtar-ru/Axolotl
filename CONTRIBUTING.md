# Contributing to Axolotl

Welcome! This guide will help you set up a development environment and make your first contribution.

## Quick Start

```bash
# Clone the repository
git clone https://github.com/anomalyco/axolotl.git
cd axolotl

# Start development stack
docker-compose up -d  # frontend + backend + neo4j

# Or run individually
cd backend && mvn spring-boot:run    # http://localhost:8080
cd frontend && npm run dev       # http://localhost:5173
```

## Development Setup

### Prerequisites
- Java 21+
- Node.js 18+
- Docker & Docker Compose (for full stack)

### Backend
```bash
cd backend
cp src/main/resources/application.yml.example src/main/resources/application.yml
mvn spring-boot:run
```

### Frontend
```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```

##Coding Standards

### Backend (Java)
- Use SLF4J for logging: `Logger log = LoggerFactory.getLogger(MyClass.class)`
- Follow Spring Boot conventions
- Add unit tests for new services
- Use camelCase for variables, PascalCase for classes

### Frontend (Vue/TypeScript)
- Composition API with `<script setup lang="ts">`
- Pinia for state management
- camelCase for variables, PascalCase for components
- Run `npm run type-check` before committing

## Pull Request Process

1. **Create a feature branch**
   ```bash
   git checkout -b feature/my-new-feature
   # or
   git checkout -b fix/bug-description
   ```

2. **Make changes** and commit with clear messages
   ```bash
   git add .
   git commit -m "feat(component): add new feature"
   ```

3. **Run checks locally**
   ```bash
   cd backend && mvn compile
   cd frontend && npm run type-check && npm run build
   ```

4. **Push and create PR**
   ```bash
   git push -u origin feature/my-new-feature
   ```

## Commit Message Format

Use [Conventional Commits](https://www.conventioncommits.org):

```
<type>(<scope>): <description>

[optional body]
```

Types:
- `feat` — new feature
- `fix` — bug fix
- `refactor` — code refactoring
- `docs` — documentation
- `test` — tests
- `chore` — build, tooling

Examples:
```
feat(agent): add tool-enabled agent architecture
fix(canvas): correct node position on drag
docs(readme): update installation steps
```

## Testing

### Backend
```bash
cd backend && mvn test
```

### Frontend
```bash
cd frontend && npm run test:unit
```

### E2E (Playwright)
```bash
# Start backend first
cd backend && mvn spring-boot:run

# In another terminal - start frontend
cd frontend && npm run dev

# Run tests
cd e2e && npx playwright test

# Or with custom URLs
API_URL=http://localhost:8080 FRONTEND_URL=http://localhost:5173 npx playwright test
```

### E2E (Playwright)
```bash
cd frontend && npx playwright test
```

## Code Review Checklist

- [ ] Code follows project conventions
- [ ] Tests pass locally
- [ ] No console errors in browser
- [ ] TypeScript compiles without errors
- [ ] Java compiles without warnings

## Questions?

- Open an issue: https://github.com/anomalyco/axolotl/issues
- Discussions: https://github.com/anomalyco/axolotl/discussions