---
session: ses_1e25
updated: 2026-05-12T19:34:23.838Z
---

# Session Summary

## Goal
Identify and document all code patterns across the Axolotl project (Java backend, Vue/TS frontend, Electron, shell scripts, e2e tests) clustered by architectural layers, component types, module organization, similar structures, and duplicated patterns.

## Constraints & Preferences
- Must cover all 5 target areas: backend Java (`com.agent.orchestrator`), frontend Vue/TypeScript, shell scripts, Electron main process, e2e Playwright tests
- Group findings by architectural layer, component types, module organization, similar templates, and duplication
- Preserve exact file paths and class/function names

## Progress
### Done
- [x] Mapped full backend package structure under `com.agent.orchestrator` — 11 subpackages
- [x] Mapped full frontend structure under `frontend/src` — 8 directories
- [x] Read all 16 controllers, all 14 services, all 19 models, all 6 repositories, all 9 config files
- [x] Read all 11 LLM provider files (interface + implementations)
- [x] Read all 19 node Vue components, 5 block components, 3 UI components
- [x] Read all 4 composables, 5 stores, the API service layer, types
- [x] Read electron main/preload, e2e spec, all 10 shell scripts
- [x] Analyzed pattern clustering across all layers

### In Progress
- [ ] Synthesizing findings into grouped pattern clusters below

### Blocked
- (none)

## Key Decisions
- **Analysis scope = entire codebase at `/Users/evgenijtihomirov/git/Axolotl/Axolotl/`**: Covers production code, not `backend-next/`, `frontend-next/`, or test directories within those. The e2e spec and unit tests under `__tests__/` are in scope.

## Next Steps
1. Review the clustered patterns below to identify refactoring candidates
2. Extract templates for code generation (controller template, service template, node component template)
3. Investigate more deeply any specific cluster of interest

## Critical Context

### CLUSTER 1: Java Architectural Layers (Backend)

**Package**: `backend/src/main/java/com/agent/orchestrator/`

**1.1 Controller Layer** — 16 files, all follow the same pattern:
```
@RestController @RequestMapping("/api")
class {Entity}Controller {
  private final {Entity}Service service;   // DI via constructor
  private final OtherService...             // additional deps

  @GetMapping("/{entity}s")        → List<{Entity}>
  @PostMapping("/{entity}s")       → {Entity}
  @PutMapping("/{entity}s/{id}")   → {Entity}
  @DeleteMapping("/{entity}s/{id}")→ void
  // + domain-specific endpoints
}
```
- All use constructor injection (no @Autowired on fields)
- All delegate to service layer, never call repository directly
- Auth extracted via `SecurityContextHolder.getContext().getAuthentication()`

**Controllers list**: AgentController, AppController, AuthController, CrossCheckController, CustomEndpointController, EvidenceController, EvolveController, HarnessController, ManifestController, PlanController, PluginController, RemoteApiController, SettingsController, ShareController, SkillController, TemplateController

**1.2 Service Layer** — 14 files, pattern:
```
@Service
class {Entity}Service {
  private final Repository repo;           // DI constructor
  private final OtherService deps;

  public Result domainMethod(params) { ... }
}
```
**Key services**:
- `SchemaService` — workflow execution engine (CompletableFuture-based parallel execution, node visitor pattern, WebSocket streaming)
- `AgentService` — agent CRUD + chat dispatch to LLM
- `PlanningService` — planning stages (outline → refine → execute)
- `PlanService` — CRUD for execution plans
- `NodeExecutor` — executes individual nodes by type (switch on `node.getType()`)
- `ToolExecutor` — dispatches tool calls (15 tools: file_read, file_write, grep, bash, git, web_search, etc.)
- `SettingsService` — persistence of LLM/app settings
- `SchemaExporter` — export to Mermaid, Python, JSON, PNG
- `CrossCheckService`, `MetricsService`, `PluginService`, `SkillService`, `TransformService`

**1.3 Model Layer** — 19 POJOs, pattern:
```
public class {Entity} {
  private String id;                       // UUID strings, not numeric
  private String name;
  private String ...;
  // getters + setters
  // no JPA annotations — uses JDBC + SQLite
}
```
**Key models**: WorkflowSchema (hub — contains nodes+edges+metadata), Node (type, config, position), Edge (source, target, type), Agent (llmConfig, tools, systemPrompt), Plan, Task, ExecutionRecord, AppUser, ShareLink, Skill, Tool, AppModel, ApiKey, CustomLlmEndpoint

**1.4 Repository Layer** — 6 files, all extend no interface, pattern:
```
@Repository
class {Entity}Repository {
  private final DbConfig dbConfig;
  private final ObjectMapper mapper;
  
  // raw JDBC with DriverManager.getConnection(dbUrl)
  // SQLite via CREATE TABLE IF NOT EXISTS
  // INSERT OR REPLACE INTO / SELECT / DELETE
}
```
**File-based SQLite** (not H2, not PostgreSQL). Connection string from `DbConfig.getDbUrl()`.
- SchemaRepository, PlanRepository — CRUD + list, JSON serialization of data field
- UserRepository — auth queries (findByUsername)
- ApiKeyRepository, CustomLlmEndpointRepository, ShareLinkRepository

**1.5 Config Layer** — 9 files:
- `SecurityConfig` — Spring Security filter chain, JWT, CORS, CSRF disabled
- `JwtAuthFilter` — OncePerRequestFilter extracting Bearer token
- `JwtUtil` — JWT encode/decode (jjwt library)
- `WebSocketConfig` — STOMP over WebSocket endpoint registration
- `DbConfig` — SQLite connection string from env/properties
- `AppConfig`, `AgentConfig`, `SpringAiConfig`, `OpenApiConfig`

**1.6 LLM Provider Layer** — Strategy pattern:
```
interface LlmProvider {
  String chat(model, systemPrompt, userPrompt, config);
  default String streamingChat(..., Consumer<String> onToken);
  boolean supportsStreaming();
  String getName();
  List<String> listModels();
  String getBaseUrl();
}
```
**Implementations**: 
- `OllamaProvider` — local via HTTP
- `OpenAiProvider` — OpenAI API
- `AnthropicProvider` — Anthropic API
- `DeepSeekProvider` — DeepSeek API
- `CustomLlmProvider` — user-configurable endpoint
- `SpringAiLlmProvider` — wrapper around Spring AI
- `RlmProvider`, `OpencodeZenProvider`
- `LlmService` — routes requests to correct provider by name

**1.7 MCP Layer**: `McpServer` — MCP protocol server implementation

**1.8 Client Layer**: `LlmClient`, `OpenClawClient` — HTTP clients for external LLM/tool APIs, `MemPalaceClient` for memory service

**1.9 Graph Layer**: `Neo4jSchemaRepository` — alternative Neo4j-backed schema storage

**1.10 WebSocket Layer**: `ExecutionWebSocketHandler` — real-time streaming of execution progress, tokens, logs, waves

### CLUSTER 2: Vue/TypeScript Frontend Patterns

**2.1 Type Definitions** — Single file `frontend/src/types/index.ts`:
- Exports TypeScript interfaces matching backend models: `WorkflowSchema`, `Node`, `Edge`, `Agent`, `ExecutionMode`, `ExecutionRecord`, `PlanningModels`, `PlanRequest`, `PlanResponse`
- Plus frontend-only types for UI state

**2.2 API Service** — Single file `frontend/src/services/api.ts`:
- Axios instance with JWT interceptor (auto-attach Bearer token)
- 401 interceptor: clear localStorage + redirect to /login
- Structured as namespace objects: `schemaApi`, `agentApi`, `authApi`, `planApi`, `settingsApi`, `executionApi`
- Each namespace exposes async methods: get/list, create, update, delete + domain endpoints
- All base URLs from `VITE_API_URL` env var

**2.3 Pinia Stores** — 5 stores under `frontend/src/stores/`:
```
// defineStore pattern
export const use{X}Store = defineStore('{x}', () => {
  const state = ref(...);
  const getter = computed(...);
  function action() { ... }
  return { state, getter, action };
});
```
- `schemaStore.ts` — current schema, nodes, edges, undo/redo stack
- `authStore.ts` — user, token, login/logout
- `settingsStore.ts` — LLM settings, app preferences
- `panelStore.ts` — UI panel visibility state
- `counter.ts` — (likely template remnant)

**2.4 Composables** — 4 files under `frontend/src/composables/`:
```
export function use{X}() {
  const state = ref(...);
  function doThing() { ... }
  return { state, doThing };
}
```
- `useWebSocket.ts` — WebSocket connection management, reconnect logic, message dispatch
- `useToast.ts` — toast notification system
- `useElectron.ts` — Electron IPC bridge
- `useExecutionState.ts` — execution status tracking

**2.5 Vue Component Hierarchy**:
```
frontend/src/components/
├── nodes/          # 16 VueFlow node components + node-base.css
│   ├── AgentNode.vue, SourceNode.vue, OutputNode.vue, ConditionNode.vue
│   ├── LoopNode.vue, MemoryNode.vue, GuardrailNode.vue, HumanNode.vue
│   ├── FallbackNode.vue, SubagentNode.vue, GroupNode.vue, CommandNode.vue
│   ├── TransformNode.vue, FileWriteNode.vue, SchemaBuilderNode.vue
│   ├── CommentNode.vue, MemoryResultCard.vue
│   └── __tests__/
├── blocks/         # 5 block components (sub-node patterns for Agent node)
│   ├── BlockBase.vue, ThinkBlock.vue, ActBlock.vue
│   ├── RememberBlock.vue, ReceiveBlock.vue
├── edges/          # Custom edge components
├── editor/         # Canvas editor components
├── app/            # App shell components
├── live/           # Live execution view components
├── studio/         # Studio workspace layout components
├── ui/             # Reusable UI components (AppModal, TemplateGallery, ThemeToggle)
├── icons/          # SVG icon components
└── ToastContainer.vue
```

**2.6 Node Component Template** — All 16 node components share the exact same structure:
```vue
<template>
  <div class="node {type}-node" :class="{ selected, 'node-running': ..., 'node-completed': ..., 'node-failed': ... }">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete">✕</button>
    <Handle type="target" :position="Position.Top" />
    <Handle v-if="isSource" type="source" :position="Position.Bottom" />
    <div class="node-header">
      <span class="node-icon">{emoji}</span>
      <span v-if="!editingName" class="node-name" @dblclick="startEditName">{{ props.data.name }}</span>
      <input v-else ref="nameInput" v-model="localName" class="node-name-input" ... />
      <span class="node-status" :style="{ background: statusColor }"></span>
      <span class="execution-icon">{{ executionIcon }}</span>
      <button class="node-expand" @click="expanded = !expanded">{{ expanded ? '▼' : '▶' }}</button>
    </div>
    <div v-if="expanded" class="node-content">
      <!-- type-specific config controls -->
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Handle, Position, useVueFlow } from '@vue-flow/core';
import { useSchemaStore } from '@/stores/schemaStore';

const props = defineProps<{ id: string; data: any; ... }>();
const emit = defineEmits<{ connect: [] }>();

const schemaStore = useSchemaStore();
const { getSelectedNodes } = useVueFlow();
const expanded = ref(false);
const editingName = ref(false);
const localName = ref(props.data.name);
const isSelected = computed(() => getSelectedNodes.value.some(n => n.id === props.id));

// type-specific watchers and methods
</script>

<style scoped>
@import './node-base.css';
</style>
```

### CLUSTER 3: Module Organization Patterns

**3.1 Backend Module Mapping**:
```
controller → service → repository (→ SQLite)
                         └→ graph/Neo4jSchemaRepository (→ Neo4j)
                 └→ llm/LlmService → LlmProvider implementations (Ollama, OpenAI, etc.)
                 └→ websocket/ExecutionWebSocketHandler
                 └→ client/LlmClient, MemPalaceClient
                 └→ mcp/McpServer
```

**3.2 Frontend Module Mapping**:
```
components/nodes/ ←→ stores/schemaStore (Pinia) ←→ services/api.ts ←→ backend REST
composables/useWebSocket                        ←→ backend WebSocket
composables/useElectron                         ←→ electron/preload.ts ←→ electron/main.ts
```

**3.3 Electron** — thin Electron shell:
- `electron/main.ts` — BrowserWindow creation, file protocol, window management
- `electron/preload.ts` — contextBridge exposing IPC to renderer

**3.4 Scripts** — 10 shell/Python scripts:
- `dev.sh` — start/stop/logs/execute convenience commands
- `sync-to-test.sh`, `sync-from-test.sh` — rsync main→next development copies
- `setup-worktree.sh`, `teardown-worktree.sh` — git worktree management for parallel dev
- `update-graph.sh` — Neo4j schema graph migration
- `migrate-schemas.py`, `migrate-to-neo4j.py` — Python database migration scripts
- `setup-graph-hook.sh` — git hook for graph schema automation
- `requirements.txt` — Python deps

**3.5 e2e** — single Playwright spec: `axolotl.spec.ts`

### CLUSTER 4: Duplicated Patterns

**4.1 CRUD Boilerplate in Controllers** — 10+ controllers have nearly identical CRUD endpoints:
```java
@GetMapping("/entities")
public List<Entity> getAll() { return service.getAll(); }
@PostMapping("/entities")
public Entity create(@RequestBody Entity e) { return service.create(e); }
@PutMapping("/entities/{id}")
public Entity update(@PathVariable String id, @RequestBody Entity e) { ... }
@DeleteMapping("/entities/{id}")
public void delete(@PathVariable String id) { service.delete(id); }
```
**Variations**: Some add `@PathVariable String id` in path, some use query params, all follow the same semantic shape.

**4.2 Constructors in all Java classes** — every controller, service, repository uses explicit constructor DI (no Lombok `@RequiredArgsConstructor`) — ~50+ identical `this.x = x;` blocks.

**4.3 Node Vue Components** — 16 near-identical files sharing:
- Same computed properties: `isSelected`, `statusColor`, `executionIcon`, `editingName` logic
- Same template skeleton (header, handles, delete button, expand collapse)
- Same CSS via `node-base.css`
- Same `handleDelete` → `schemaStore.deleteNode(id)` pattern
- Same `useSchemaStore()` + `useVueFlow()` dependency injection

**4.4 Repository JDBC pattern** — 6 repositories with identical `try (Connection conn = DriverManager.getConnection(dbUrl)) { ... }` and `CREATE TABLE IF NOT EXISTS` bootstrap.

**4.5 Logger declarations** — every class (50+ Java files) has the same:
```java
private static final Logger log = LoggerFactory.getLogger({ClassName}.class);
```

**4.6 `@Configuration` beans** — multiple config files with similar `@Bean` factory methods for `ObjectMapper`, `RestTemplate`, etc.

### CLUSTER 5: Cross-Layer Communication Patterns

```
Browser/Vue (Vite)  
  ├─ REST (Axios) ─→ Backend Controller ─→ Service ─→ Repository ─→ SQLite
  │                                    └→ LLM Provider → External API
  │                                    └→ Neo4j (via Neo4jSchemaRepository)
  ├─ WebSocket ───→ ExecutionWebSocketHandler (STOMP) ─→ SchemaService (parallel exec)
  │                    └→ token streaming, progress, logs, waves
  └─ Electron IPC ─→ preload.ts ─→ main.ts (window/file management)
        (via useElectron composable)
```

## File Operations
### Read
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/Application.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/client/LlmClient.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/config`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/config/DbConfig.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/config/JwtAuthFilter.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/config/SecurityConfig.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/config/WebSocketConfig.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/AgentController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/AuthController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/PlanController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/RemoteApiController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/SettingsController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/ShareController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/graph/repository/Neo4jSchemaRepository.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm/AnthropicProvider.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm/LlmProvider.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm/LlmService.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm/OllamaProvider.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm/OpenAiProvider.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/mcp/McpServer.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/model`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/model/Agent.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/model/ExecutionRecord.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/model/Node.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/model/Plan.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/model/Task.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/repository`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/repository/PlanRepository.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/repository/SchemaRepository.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/repository/UserRepository.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/AgentService.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/PlanService.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/SettingsService.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/e2e`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron/main.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/App.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/blocks`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/AgentNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/OutputNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/SourceNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/composables`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/composables/useElectron.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/composables/useToast.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/composables/useWebSocket.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/services`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/services/api.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/stores`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/stores/authStore.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/stores/schemaStore.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/types`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/types/index.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/dev.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/sync-to-test.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/update-graph.sh`

### Modified
- (none)
