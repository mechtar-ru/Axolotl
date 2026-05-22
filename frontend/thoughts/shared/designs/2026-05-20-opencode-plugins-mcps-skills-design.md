---
date: 2026-05-20
topic: "Opencode plugins, MCPs and skills inventory + install"
status: draft
---

## Problem Statement

We need a clear, reproducible inventory of Opencode plugins, MCP servers, and skills present in this repository — and concise install/enable steps so new developers can reproduce the environment.

**Goal:** provide a single reference that lists what's present, how to install or enable each item, and which mechanism the project uses for browser interaction.


## Constraints

- Use existing repo configuration files (.opencode/, .mcp.json, .claude/settings.local.json) as ground truth.
- Avoid changing runtime behavior; this is documentation/design only.
- Installation steps must use npm/pip/mvn where the repo implies those package managers.


## Approach

I inventoried repo files that declare plugins, MCP servers, and skills and distilled reproducible install/enable steps. I prioritized minimal, repeatable commands (npm install, pip install, global npm for MCP server) and noted where configuration toggles live.


## Architecture (overview)

This is a developer-facing design doc (inventory + install). The repository uses three mechanisms:

- **Opencode plugins** configured under opencode.json and .opencode/opencode.json (resolved via @opencode-ai/plugin npm package).
- **MCP servers** defined in .mcp.json and enabled via .claude/settings.local.json (Playwright is wired as an MCP server and the backend has code to launch/bridge it).
- **Skills** installed as SKILL.md directories under .agents/skills or .claude/skills and tracked in skills-lock.json.


## Components

### Opencode plugins (present)

- **oc-mnemoria**
  - Location: opencode.json (root), data in .opencode/mnemoria/
  - Purpose: memory persistence plugin
  - Install: ensure `.opencode` deps are installed (see steps below)

- **opencode-browser-plugin**
  - Location: .opencode/opencode.json and frontend/.opencode/package.json (depends on @opencode-ai/plugin)
  - Purpose: provides browser automation capabilities to Opencode via the plugin SDK
  - Install: see steps below


### MCP servers (present)

- **playwright MCP server**
  - Location: declared in .mcp.json and enabled in .claude/settings.local.json
  - Purpose: provide browser navigation/click/type/snapshot tools to AI agents (Playwright-based)
  - Install: typically installed globally via npm (claude-playwright) — see steps below

- **memPalace MCP / plugin bridge**
  - Location: backend bridges (PluginService, mempalace references), permissions in .claude/settings.local.json
  - Purpose: memory / search tool backed by mempalace (Python service)
  - Install: pip install mempalace and run the mempalace service (see steps below)


### Skills (present)

- Caveman family: caveman, caveman-commit, caveman-compress, caveman-help, caveman-review
  - Location: .agents/skills/* and mirrored in .claude/skills/
  - Purpose: small behavior/prompt packages (compression, commit message helpers, review helpers)
  - Install: copy skill directory into .agents/skills/ or use the repository's skill import if available (see notes)

- compress
  - Location: .agents/skills/compress and .claude/skills/compress
  - Purpose: compress long-form memories and docs


## Installation & Enablement Steps (concise)

Assumption: you are at the project root. These commands reproduce the environment implied by the repo.

1) Install Opencode plugin SDK (local .opencode)

   - cd .opencode && npm install
   - Result: installs @opencode-ai/plugin and makes `opencode-browser-plugin` available to the local opencode runtime

2) Install Playwright MCP server (global, used by .mcp.json)

   - npm install -g claude-playwright
   - Verify: ensure the MCP server path referenced in .mcp.json exists (e.g., ~/.npm-global/lib/node_modules/claude-playwright/dist/mcp/server.cjs)

3) Install mempalace (Python memory tool)

   - python3 -m venv .venv && source .venv/bin/activate
   - pip install mempalace
   - (Optional) configure MEMPALACE_URL env var or run mempalace service per project docs

4) Frontend deps & Playwright test runner

   - cd frontend && npm install
   - (To run e2e) npm run test:e2e (uses @playwright/test configured in frontend/playwright.config.ts)

5) Backend deps & run

   - cd backend && mvn -DskipTests package
   - Start: mvn spring-boot:run (backend listens on :8082 per repo conventions)

6) Install or add skills

   - Option A (manual): copy skill folder (SKILL.md + assets) into .agents/skills/<name>/ and optionally .claude/skills/<name>/ then restart agent runtime
   - Option B (if available): use the repository's skill import endpoint (referenced: POST /api/skills/import/opencode) — call that API with skill archive or GitHub source


## Browser interaction: what the project currently uses

- Primary mechanism: **Playwright**, used two ways:
  - As an MCP server for AI agents (Playwright MCP declared in .mcp.json and enabled in .claude/settings.local.json). This gives AI agents explicit tools like browser_navigate, browser_click, browser_snapshot, browser_type, etc.
  - For frontend E2E tests via @playwright/test (frontend/playwright.config.ts) — invoked with `npm run test:e2e`.

- Secondary: **opencode-browser-plugin** (via @opencode-ai/plugin) configured under .opencode/. This integrates with Opencode runtime and offers browser helper tooling (the plugin SDK). Depending on runtime selection, the Opencode runtime may prefer the plugin or the MCP bridge.

Conclusion: Playwright (MCP) is the repo's active browser automation mechanism for AI-driven interactions; Playwright tests are used for CI/e2e. The opencode-browser-plugin complements this and is configured for in-project Opencode use.


## Error Handling / Troubleshooting Notes

- If the Playwright MCP binary path in .mcp.json doesn't exist, reinstall `claude-playwright` globally or adjust .mcp.json to point to the installed path.
- If Opencode plugin resolution fails, ensure `.opencode/node_modules` contains `@opencode-ai/plugin` by running `cd .opencode && npm install`.
- Zen API key: set ZEN_API_KEY in .env before starting backend; some provider classes expect it.


## Testing Strategy

- Verify Playwright MCP: start Opencode runtime and ensure AI agent can call `mcp__playwright__browser_navigate` (check logs or run a small agent scenario).
- Run frontend Playwright tests: cd frontend && npm run test:e2e
- Verify skills: restart Opencode runtime and confirm skills show under available_skills (they are listed by the runtime from .agents/skills)


## Open Questions

- Does the project prefer global or local installation of claude-playwright? (repo implies global but local installs are often safer.)
- Is there a documented skill import workflow (API payload, auth) for POST /api/skills/import/opencode? The file references it but docs are missing.


---

Created by: Senior Engineer assistant — inventory + install guide for Opencode plugins, MCPs, and skills.
