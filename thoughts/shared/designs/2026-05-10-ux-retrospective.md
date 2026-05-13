---
date: 2026-05-10
topic: "UX Postmortem — Why Axolotl Failed (Design Autopsy)"
status: validated
---

## Why We're Here

6 months post-launch. Users came, peeked, left. Active schemas: single digits. The tech was solid — Spring Boot, VueFlow, WebSockets, Neo4j. The marketing hit the right notes — "visual AI orchestration", "draw logic, don't write it". But the product bled users from day one.

This is not about the tech stack. It's not about the go-to-market. It's about what users saw, felt, and bounced off of. A design autopsy.

---

## 1. The Onboarding Betrayal

**The problem:** We walk users through a 3-step wizard — pick a provider, pick a template, see a summary — then drop them into a blank canvas with zero orientation.

**The moment of truth:** User clicks "Start Building". The modal closes. They're staring at a dark canvas with a toolbar, a search input, and nodes they don't understand. No tooltip. No highlight. No "try connecting these two nodes". The README promises a "2-step wizard" but it's actually 3 steps, and the most important step — **how to use the canvas** — doesn't exist.

**What the user feels:** "Cool, I picked a template... now what?"

**Design failure:** We optimized the sign-up flow but neglected the *first-value flow*. The onboarding wizard is a promise the canvas UI doesn't keep.

---

## 2. The Toast That Never Toasted

**The problem:** `useToast()` creates a singleton state queue. No `<ToastContainer>` renders it. Toasts are fired eagerly throughout the codebase (`toast.success()`, `toast.error()`) but users never see them.

**The full extent:** When a user clicks "Save" — nothing visible happens. When execution fails — no popup, no alert. When JSON import breaks — the error is serialized into the void.

**What the user feels:** "Did it work? No idea. Is it broken? Probably."

**Design failure:** We built a feedback system and forgot to plug in the speakers. Every action in the app becomes a gamble — the user never knows if their input registered.

---

## 3. The Promise Land / Feature Desert Gap

**The README promises:**
- 15 built-in tools per agent
- 7 agent types (Assistant, Coder, Researcher...)
- Per-node tool permissions (block rm -rf, allowlist paths)
- Visual trajectory with iteration history
- Share links, webhooks, Remote API keys
- SVG export
- Semantic memory search with cosine similarity

**The UI delivers:**
- A dropdown to pick an LLM model
- A logs panel with text output
- A trajectory tab that says "will appear here when..."

**What the user feels:** The README is a sci-fi novel. The app is a text editor.

**Design failure:** We over-committed in prose and under-delivered in pixels. Every missing feature is a trust breach. The user reads about "15 tools", opens an AgentNode, and finds... a model selector. The gap between promise and reality is the product's toxic asset.

---

## 4. The Two Template Systems

**The problem:** Onboarding has 3 hardcoded templates (AI Pipeline, RAG Pipeline, Blank Canvas). The sidebar has a Template Gallery that fetches from `/api/templates` and shows different content (UI/UX Review, Frontend Refactoring, Code Analysis).

They use different visual languages: onboarding uses purple accent (`#6c63ff`), templates gallery uses cyan accent (`#00bcd4`). They appear in different places. They serve different purposes.

**What the user feels:** "Wait, I already picked a template. Now there's more? Why do these look different?"

**Design failure:** Two template systems with no hierarchy. The user can't tell which one to use when. This is a classic "too many doors" anti-pattern.

---

## 5. The Invisible Toolbox

**The problem:** AgentNode.vue shows a model dropdown and a prompt textarea. There's no way to:
- See what tools the agent can use
- Add or remove tools per agent
- Configure tool permissions
- Choose an agent type (Assistant vs Coder vs Researcher)

The entire tool infrastructure exists in the backend (`ToolExecutor.java`, `ToolPermission.java`, `AgentService.java`). The UI never exposes it.

**What the user feels:** "The agent just talks. I thought it could do things?"

**Design failure:** We built a powerful tool system and made it invisible. The single most important differentiation from n8n and LangFlow — tool-enabled agents — requires backend API calls to configure. The average user never discovers it.

---

## 6. The Mental Model Collision

**The problem:** The app calls it "Schema". The code calls it "WorkflowSchema". The toolbar says "Execution". The README says "Draw logic, don't write it". The domain is visual AI orchestration.

But:
- "Schema" is a database term to most users
- "Workflow" implies a fixed sequence, not a directed graph
- "Execution" sounds like a code deployment, not running an AI chain
- The product says "draw" but the UI is node-and-connector (very different from drawing)

**What the user feels:** "Is this a database tool? A workflow engine? A no-code platform?"

**Design failure:** We invented a new category but used existing category terminology. Users pattern-match what they see — and they matched "schema" to "database schema editor" (which this isn't).

---

## 7. The Missing Auto-Save Indicator

**The problem:** Auto-save works (debounced 500ms). The README promises "visual save status". But nothing in the UI shows:
- "Saving..."
- "Saved"
- "Unsaved changes"
- Network error on save

Users make changes, navigate away, come back — their edits are saved. They never know they were saved. They never trust the save.

**What the user feels:** "Did I lose my work?" (spoiler: no, but the paranoia is real)

**Design failure:** Reliable auto-save with no indicator is indistinguishable from no auto-save. The user experience of both is identical: uncertainty.

---

## 8. The Accent Color Civil War

**The problem:** Different parts of the app use different accent colors:
- Primary: `#6c63ff` / `#7b5cff` (purple-blue)
- Template Gallery: `#00bcd4` (cyan)
- Right Panel tabs: `#00bcd4` (cyan)
- Everything else: purple

CSS variables exist (`--accent`) but are inconsistently applied. Some components use the variable, others hardcode the hex.

**What the user feels:** Nothing conscious. But the UI feels "off." The inconsistency registers as low visual polish.

**Design failure:** Inconsistent accent color is the design equivalent of a misaligned logo on a business card. Users won't point at it, but they'll trust the product less.

---

## 9. The Execution Mode Jargon

**The dropdown says:**
- ▶ Execute
- 🔍 Analyze
- 🎭 Dry Run

**The button says:**
- "Run" (when mode is EXECUTE)
- "Analyze" (when mode is ANALYZE)  
- "Simulate" (when mode is DRY_RUN)

Three verb pairs for three states. "Dry Run" vs "Simulate" are synonyms but different words to the user. The emoji prefix on dropdown items implies each mode is a distinct tool, but the button treats them as labels.

**What the user feels:** "What's the difference between Analyze and Dry Run? Do both?"

**Design failure:** We used two different naming systems for the same concept in the same UI region, 50 pixels apart.

---

## 10. The Loading-Void Pattern

**The app has no loading states for:**
- Schema list fetch (sidebar stays empty)
- Provider list fetch (onboarding shows nothing, then pops in)
- Schema create/update (silent auto-save)
- WebSocket connection (no indicator during execution startup)

**What the user feels:** "Is anything happening?"

**Design failure:** Silence in UI is indistinguishable from failure. Every async operation without a loading state is a potential user-abandonment point.

---

## Root Cause Summary

1. **We confused "onboarding" with "registration."** The wizard gets them logged in, not productive.
2. **We built features behind the UI.** Tool system, agent types, skills — all backend, zero frontend.
3. **We over-promised in prose and under-delivered in pixels.** The README/sales narrative diverged from what the app actually does.
4. **We neglected feedback loops.** Toast container missing, auto-save invisible, loading states absent.
5. **We used inconsistent visual language.** Two accent colors, two template systems, mismatched labels.
6. **We chose developer terminology over user terminology.** "Schema" means something else to most people.

---

## What We'd Do Differently

1. **Post-onboarding overlay:** After the wizard, show 3 highlighted regions with tooltip explanations — "Add nodes here", "Connect them here", "Run here"
2. **Fix ToastContainer:** Single `<ToastContainer />` in App.vue — fixes every silent feedback issue
3. **Tool selector on AgentNode:** Checklist of tools with enable/disable, search filter, permission path inputs
4. **One template system:** Consolidate onboarding templates and Template Gallery into one authoritative source
5. **Unify accent color:** `#7b5cff` everywhere, remove all `#00bcd4` references
6. **Auto-save chip:** "Saving..."/"Saved" pill next to schema name
7. **Loading skeletons:** Replace empty voids with shimmer/placeholder for every async region
8. **Remove jargon:** Standardize "Schema" → "Workflow" or pick one and stick to it. Unify execution mode labels.
9. **SVG export:** `toSvg()` exists in the same library we use for PNG
