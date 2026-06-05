# Multi-Agent Development — Future Idea

**Status:** Deferred  
**Priority:** Low  
**Depends on:** 49-app-creation-workflow-plan (Neo4j Plan API)

---

## Idea

When a plan has N independent steps (no depends_on chain), launch N agent sub-agents in parallel, each implementing one step. Coordinator waits for all, then runs verifier.

## How it would work

1. Plan API: `GET /api/plan/steps?status=pending&ready=true` — steps with all deps fulfilled
2. Pipeline: spawn N parallel agent nodes, one per ready step
3. Each agent: reads its step file, pseudocode, writes code
4. Verifier: runs after all agents complete

## Challenges

- **Race conditions** — two agents editing same file (need file locking or step isolation)
- **Context size** — each agent needs full project context but only works on its step
- **Consistency** — agent A modifies file, agent B doesn't see it (no shared state between parallel agents)
- **Token cost** — N agents = N × context/response tokens
- **Simple fix for race conditions** — use different directories per step agent: agent1 writes to `src/feature-x/`, then verifier merges

## When it could make sense

- Large projects with truly independent modules (e.g., separate microservices)
- Test generation in parallel with implementation
- UI components when each is in its own file

## When NOT to use

- Single-file changes (one agent is cheaper and simpler)
- Tightly coupled code where files touch each other
- Small projects (< 500 lines) — overhead beats parallelism

---

*Linked from: 49-app-creation-workflow-plan.md*
