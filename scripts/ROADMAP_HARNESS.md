# Roadmap Harness — Workflow for .ideas/approved

Purpose: standardize how roadmap items move through `approved/` (planned → implementing → done) and enforce test requirements for any feature being implemented.

Directory structure used by the team:

- `.ideas/approved/planned/` — new items (roadmap_XX.md files) awaiting assignment
- `.ideas/approved/implementing/` — items actively being implemented
- `.ideas/approved/done/` — items fully implemented and verified

Naming conventions:
- Files must keep a two-digit iterator prefix matching the original roadmap number: `01-roadmap_01.md`.
- When moving a file from `planned/` to `implementing/`, update the top of the file with the `Implementer:` and `Start-Date:` metadata lines (YYYY-MM-DD).
- When completed, move file to `done/` and add `End-Date:` and `PR:` metadata fields.

Process for starting work (automated / manual):
1. Move the roadmap file from `planned/` → `implementing/` (git mv).  
2. Edit the top of the roadmap file to add:
   - `Implementer: <github-handle>`
   - `Start-Date: YYYY-MM-DD`
3. Create a branch: `feature/roadmap-<XX>-short-description` and push it.
4. Open a PR, link the roadmap file in the description, and assign reviewers.

Definition of Done (must be satisfied before moving to `done/`):
1. Backend compiles clean: `cd backend && mvn -q -DskipTests=false test` (no broken tests introduced)
2. Frontend static type-check: `cd frontend && vue-tsc --noEmit` → zero errors
3. Frontend unit tests: `npm run test:unit` → existing suite must pass locally
4. E2E smoke test (Playwright): run the new or updated scenario in `frontend/e2e/` that covers the implemented feature. Replace brittle `waitForTimeout()` with polling for API or event-driven checks.
5. New code includes thorough unit tests for edge cases and happy paths
6. Integration tests for cross-cutting concerns (auth, pipeline execution) are included when the change touches those areas
7. Add `QA Steps` section at the bottom of the roadmap file describing manual steps to validate the feature in a running dev instance

Testing guidance — how to write thorough tests for new features

Backend
- Write unit tests for service/business logic using `@SpringBootTest`/`@ExtendWith(MockitoExtension.class)` as appropriate.
- Add integration tests using the existing `McpIntegrationTest` pattern; use the `@BeforeEach` Cypher cleanup fixture when tests touch Neo4j nodes to keep test isolation.
- For async pipeline execution tests, wait on WebSocket or polling endpoints; avoid sleeping. Use `waitUntil` style loops with timeouts.
- Always assert persistence: after write operations, read the entity back from Neo4j and assert expected fields rather than only asserting no exception.

Frontend
- Use `vue-tsc --noEmit` to catch type regressions early; fix types rather than add `any`.
- Unit tests: prefer testing logic in composables and store actions (Pinia). Mock network calls using MSW or the project's test helpers.
- E2E: avoid `page.waitForTimeout` — prefer polling for backend states or use WebSocket events. Make tests idempotent and tolerant of timing variability.
- Replace emoji/text-based assertions with DOM existence checks for inline SVGs or aria-labels.

Cross-cutting
- Add tests for auth edge cases (expired JWT 401/403 handling). Use `scripts/token.sh` tokens for automated runs.
- For file operations, include sandbox path tests and validate `validateSandboxPath()` rejects out-of-scope paths.
- Coverage: aim for meaningful coverage on new logic; we don't require 100% but tests should catch regressions.

Automation notes
- CI will run `mvn test`, `vue-tsc --noEmit`, unit tests, and a subset of Playwright tests. Fix CI before merging.
- Add new Playwright scenarios to `frontend/e2e` and ensure they are not flaky (use polling, reduce hard sleep).

Moving roadmap items to `done/`
1. When all checks above pass and PR is merged, `git mv` the implementing file to `done/` and add `End-Date:` and `PR: <url>` metadata.
2. Commit and push the move.

Notes
- This harness file lives in `scripts/` so it's versioned and discoverable by CI and contributors.
