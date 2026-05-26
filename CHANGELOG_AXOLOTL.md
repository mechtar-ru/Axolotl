# Changelog: Axolotl backend fixes

## 2026-05-26: Diff review feature + interactive dependency install dialog

### Добавлено

1. **Diff review (`requireDiffReview`)** — новый режим контроля изменений файлов.
   - В `stage.config` можно включить `"requireDiffReview": true`
   - При каждом `file_write` в существующий файл, оригинал сохраняется в `.axolotl.bak`
   - После завершения агент-ноды пайплайн приостанавливается с WS-событием `diffs_needed`
   - Фронтенд `DiffReviewDialog.vue` показывает unified diff с Accept All / Reject All
   - При approve: `.bak`-файлы удаляются, пайплайн продолжается
   - При reject: исходный контент восстанавливается из `.bak`
   - **Файлы**: ToolExecutor.java (+backup/diff tracking), PipelineService.java (+pause после agent), SchemaService.java (+handleDiffsApprove/Reject), AgentController.java (+approve-diffs/reject-diffs endpoints), ExecutionStateManager.java (+PendingDiff registry), ExecutionWebSocketHandler.java (+sendDiffsNeeded), DiffReviewDialog.vue, useWebSocket.ts (+onDiffsNeeded)

2. **Interactive dependency install dialog** — при отсутствии Flutter SDK/Android SDK/Xcode
   - `build_app` tool проверяет наличие SDK, отправляет WS `deps_needed`
   - Фронтенд `DepsInstallDialog.vue`: выводит список, кнопка "Install All (brew)"
   - Backend `POST /install-deps` запускает установку через brew
   - **Файлы**: ToolExecutor.java (+build_app tool + WS deps_needed), ExecutionWebSocketHandler.java (+sendDepsNeeded), AgentController.java (+install-deps), DepsInstallDialog.vue, StudioView.vue (wiring), useWebSocket.ts (+onDepsNeeded), SchemaBuilderNodeStrategy.java (+build_app в enabledTools по умолчанию)

### Исправлено (из предыдущих сессий)

1. **SchemaService.updateSchema** — partial merge (не перезаписывает поля, не указанные в запросе)
2. **PipelineService.clearStaleApprovals** — очистка устаревших флагов подтверждения перед новым запуском
3. **resolveSourceData** — fallback на stateManager.getNodeResults() для агента после resume
4. **enabledTools/agentType sync** — синхронизация из stage config в blueprint Node в executeSingleStage()
5. **Stage.java** — добавлены пропущенные поля (config, systemPrompt, agentType)
6. **file_read relative path** — handleFileReadWithSandbox() с префиксом schemaTargetPath
7. **Ollama timeout** — 120s → 3600s в application.yml
8. **CustomLlmProvider** — baseUrl localhost:8085 для Bonsai MLX
9. **Post-write syntax validator** — dart analyze, python3 -m py_compile, javac после file_write
10. **model resolution** — resolveStageModel treats deepseek-v4-flash as transparent when schema has explicit default
11. **failure propagation** — pipeline_failed event + updateRunCompleted("failed") on stage failure
12. **pause-approve-resume** — wave loop breaks on AWAITING_APPROVAL, resume resets node states
