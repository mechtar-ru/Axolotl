# Rejected Approaches

Ideas evaluated and explicitly declined. Kept for audit trail — don't revisit without new context.

| Idea | Why Rejected | Context |
|------|-------------|---------|
| SQLite as primary store | Full migration to Neo4j complete; dual-dB adds complexity with no benefit | Migrated 2026-04 |
| LLM-driven dynamic node generation (generate-nodes) | Quick Start uses fixed pipeline template; LLM-generated node graphs produced inconsistent, non-reproducible schemas | Deprecated April 2026 |
| Spring AI integration | Over-engineered for our use case; Zen provider uses direct HttpClient for better control and simpler debugging | Removed May 2026 |
| File-based execution persistence | Neo4j provides durability across page reloads, queryability, and consistency without file sync concerns | Replaced April 2026 |
| Unlimited thread pool for execution | SchemaService uses virtual threads (Java 21); fixed limits prevent resource exhaustion | Virtual threads adopted April 2026 |
| Emoji icons in frontend | Visual inconsistency across platforms; inline SVGs render reliably everywhere | User directive, eliminated May 2026 |
| Fine-tuning pipeline (ROADMAP Phase 2) | Only Zen API available — proprietary models can't be fine-tuned. Requires open-weight model provider (Ollama) and GPU infrastructure | Deferred indefinitely June 2026 |
| Model ensemble routing | Requires 3+ functioning providers; currently only Zen has an API key configured | Premature — revisit after 2nd provider |
| Self-optimizing workflow engine | Assumes large-scale user-generated workflows and extensive execution history; we have neither | Premature — revisit after 10+ real users |
| Collaboration / multi-user features | Single-developer tool at current stage; building team features solves problems we don't have | Premature — revisit when needed |
| Roadmap plans 01–42 (2026-05) | All 42 numbered roadmap plans reviewed in sequence and rejected. See below for per-plan rationale. Seeds extracted to `misc/02_old_plans_decent_ideas.md` | Reviewed & rejected June 2026 |

### Plans 01–42 Rejection Summary

All plans were written as part of an earlier roadmap that assumed a mature platform with many users, complex user-generated DAGs, and multi-provider infrastructure. Current state (June 2026): single-provider (Zen), fixed pipeline template, a few real users. Each plan was evaluated against this reality.

| Plan | Title | Why Rejected |
|------|-------|-------------|
| 01 | NodePerformanceMetrics | Phase 3 gated (10+ users). Aggregate Cypher query service extracted as seed idea |
| 02 | WorkflowAnalyzer | Solution in search of a problem; user sees the graph visually |
| 03 | Workflow Pruning | Depends on rejected 02 |
| 04 | Node Usage Tracking | Covered by existing NodeExecution Neo4j persistence |
| 05 | PatternDetector | Downstream of rejected 02, no consumer for detected patterns |
| 06 | NodeFactory | Current 5-node fixed template doesn't need factory |
| 07 | Workflow Template System | Useful kernel ("save schema as template") extracted as seed. Implementation is ~1 day |
| 08 | Node Suggestion | Needs usage data we don't have and topology flexibility we don't have |
| 09 | TaskType Classification | Covered by existing node type system |
| 10 | ModelPreference Tracking | Covered by SettingsService user default model |
| 11 | EnsembleModelRouter | Requires 3+ functioning providers; only Zen has API key configured |
| 12 | Model Performance Monitoring | Downstream of rejected 01, no single-model use case |
| 13 | WorkflowMetaLearner | Needs large user base and complex DAGs we don't have |
| 14 | Fitness Functions | Academic; no practical consumer in current architecture |
| 15 | Evolutionary Algorithm | Academic; self-optimizing is premature without users |
| 16 | A/B Testing Framework | Node config comparison extracted as seed idea. Useful but premature |
| 17 | Deploy Meta-Learning Optimization | Depends on rejected 15 chain |
| 18 | TrajectoryExtractor | Downstream of rejected 05 |
| 19 | DatasetBuilder | Depends on rejected 18 |
| 20 | Automated Fine-Tuning | No open-weight providers configured; no GPU infra |
| 21 | Model Versioning | Single model, no version drift to track |
| 22 | ToolSynthesizer | Useful but needs agent tool usage patterns we don't have yet |
| 23 | ToolChainingOptimizer | Depends on rejected 22 |
| 24 | Sandboxed Execution | Already handled by file_write sandbox (validateSandboxPath) |
| 25 | CognitiveLoadEstimator | No clear consumer for this metric |
| 26 | InterventionDetector | Downstream of rejected 25 |
| 27 | ExplanationGenerator | Routing too simple to benefit from explanations |
| 28 | ReasoningTraceAnalyzer | Downstream of rejected 27 |
| 29 | Natural Language Explanation Templates | Dead chain from 28 |
| 30 | Confidence Scoring | No softmax probabilities available from LLMs |
| 31 | PreferenceLearner | Already covered by SettingsService default model preference |
| 32 | CollaborativeReasoningSpace | Premature multi-user infra for single-user tool |
| 33 | Preference-based workflow adaptation | Dead chain from 31; pipeline topology is fixed |
| 34 | WorkflowAnalyzer (duplicate) | Solution in search of a problem (see plan 02) |
| 35 | WorkflowModifier | No self-modification loop exists |
| 36 | Workflow Self-Documentation | Depends on rejected WorkflowAnalyzer |
| 37 | Workflow Complexity Metrics | No consumer; fixed pipeline topology makes scores trivial |
| 38 | MultiModalReasoner | Model capability, not pipeline routing |
| 39–40 | MultiAgentReasoner | Already implemented by PipelineService topological execution + artifact passing |
| 41–42 | Workload Complexity Scoring | Dead chain from plan 25; unactionable score |
