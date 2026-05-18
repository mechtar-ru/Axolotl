# Draft: Self-Editing Schema — Axolotl Refactoring Itself

## Requirements (confirmed)
- **Goal**: Use Axolotl schemas to do automated refactoring of Axolotl's own source code
- **Edit scope**: Frontend first (Vue/TS/CSS — hot-reload), then backend (Java — needs restart)
- **Use case**: Automated refactoring — agent reads code, plans changes, writes edits
- **Sync strategy**: Git-based (worktree or clone) — edit in separate copy, commit, merge back

## Technical Decisions
- Git worktree preferred over clone (lightweight, single repo, easy branch management)
- Existing tools (file_read, file_write, bash, directory_read) are foundation
- Need additional tools: git operations, grep/search, file delete/move
- Need configurable base path (point tools at worktree, not running instance)
- Need verification pipeline (build, test) to validate edits

## Research Findings
- **ToolExecutor.java**: Has file_read, file_write, directory_read, bash — good foundation
- **Security**: Dangerous commands blocked (rm -rf, etc.) — need to ensure worktree cleanup is allowed
- **No dedicated git tools**: Need to add git operations (status, commit, push, diff)
- **No grep/search tool**: Need for finding patterns across multiple files
- **No file operations**: Missing file_delete, file_move/rename
- **PromptEditorModal**: Basic textarea — no code editor needed for this feature (agent-driven, not human)
- **No Monaco/CodeMirror**: Not needed — edits are tool-driven, not manual

## Open Questions
- ~~What LLM provider drives the refactoring agent?~~ → RESOLVED: existing custom connection
- ~~Verification: run npm build? npm test?~~ → RESOLVED: npm run build + npm run type-check
- Should we create schema templates for common refactoring patterns? → YES, include in plan

## Additional Requirements (added during review)
- Import skills from OpenCode (SKILL.md → Axolotl Skill model)
- Wire btca, context7, mempalace into ToolExecutor if feasible (pending explore)
- Axolotl already has Skill system: SkillService, SkillController, Skill model with promptTemplate, triggerPattern

## Scope Boundaries
- INCLUDE: Tool enhancements, git worktree setup, verification pipeline, schema templates, plugin wiring, skill import
- EXCLUDE: In-browser code editor UI (this is tool-driven, not manual editing)
- EXCLUDE: Backend restart automation (out of scope for first iteration)
