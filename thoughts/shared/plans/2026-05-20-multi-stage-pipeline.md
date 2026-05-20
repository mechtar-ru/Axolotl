# 2026-05-20: Multi-Stage Pipeline

## Goal

Add a first-class Pipeline abstraction to Axolotl schemas, allowing users to define workflows as ordered stages (source → review → agent → verify → output) instead of raw DAG nodes.

## Implementation

### Backend

- **`Pipeline.java`** — model with `id`, `name`, `stages`, `config`, `parallelStrategy`
- **`Stage.java`** — model with `id`, `name`, `nodeType`, `model`, `systemPrompt`, `userPrompt`, `config`, `dependencies`, `inputMapping`, `outputMapping`, loop/retry config, UI position
- **`WorkflowSchema.java`** — added `pipeline` field (nullable)
- **`PipelineService.java`** — service with:
  - `buildPipelineNodes()` — generates nodes/edges from pipeline stages
  - `executePipeline()` — runs pipeline stages in dependency order via topological sort
  - `runPipelineStages()` — executes stages level by level, parallel within each level
  - `topologicalSortStages()` — Kahn's algorithm on stages
  - `stageToScratchNode()` — creates scratch nodes for stages without pre-existing nodes
  - `createDefaultPipeline()` — creates the standard 5-stage pipeline (receive → review → agent → verify → output)
- **`AgentController.java`** — endpoints:
  - `POST /api/schemas/{id}/pipeline/build`
  - `POST /api/schemas/{id}/pipeline/execute`
  - `POST /api/schemas/{id}/pipeline/cancel`
  - `GET /api/schemas/{id}/pipeline/status`
  - `POST /api/schemas/{id}/pipeline/default`

### Frontend

- **`pipeline.ts`** — TypeScript types for `Pipeline`, `Stage`, `PipelineStatus`
- **`WorkflowSchema`** — added `pipeline?: Pipeline` field
- **`schemaStore.ts`** — added pipeline state (`pipelineStatus`, `pipelineExpanded`) and actions (`buildPipelineNodes`, `executePipeline`, `cancelPipelineExecution`, `refreshPipelineStatus`, `createDefaultPipeline`, `setPipeline`)
- **`PipelinePanel.vue`** — pipeline sidebar component showing stages in levels (topological), with buttons to execute/cancel/build/create default
- **`StudioView.vue`** — added collapsible pipeline sidebar toggle button with 320px sidebar panel

## Usage

1. Create a schema (or use Quick Start)
2. Click "Pipeline" toolbar button in Studio to open pipeline sidebar
3. Click "+ Create Default Pipeline" to generate the 5-stage pipeline
4. Click "▶ Execute Pipeline" to run stages in dependency order
5. Click "Build Nodes" to materialize stages as canvas nodes

## Next Steps

- Stage editing UI (reorder, configure model/prompt)
- Input/output mapping editor between stages
- Conditional branching at stage level
- Parallel stage execution within a level (already supported at infrastructure level)
