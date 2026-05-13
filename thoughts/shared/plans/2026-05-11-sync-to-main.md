# Sync `-next/` → Main: Axolotl Studio Redesign

**Date:** 2026-05-11
**Design:** `thoughts/shared/designs/2026-05-11-axolotl-studio-design.md`
**Previous plan:** `thoughts/shared/plans/2026-05-11-axolotl-studio-implementation.md`
**Sync script:** `scripts/sync-from-test.sh`

**Goal:** Copy all changes from `frontend-next/` and `backend-next/` to `frontend/` and `backend/`, then validate everything compiles and builds.

---

## Architecture / Approach

The sync uses `rsync -av --delete` which means:
- **New files** in `-next/` dirs are **created** in main dirs
- **Modified files** in `-next/` dirs **overwrite** main dirs
- **Deleted files** (exist in main but not in `-next/`) are **removed** via `--delete`
- Only `src/` directories are synced (not configs, scripts, etc.)

This is the riskiest part: `--delete` removes orphaned main-only files. The plan handles this with a backup step before sync and a rollback plan.

### What will be created (new files)
- **Backend:** `AppModel.java`, `AppController.java`, `scripts/migrate-schemas-to-apps.py`
- **Frontend:** `templates/index.ts`, `views/DashboardView.vue`, `views/StudioView.vue`, `stores/settingsStore.ts`, `components/app/`, `components/blocks/`, `components/live/`, `components/studio/`, `components/ui/ThemeToggle.vue`

### What will be modified (overwritten)
- **Backend:** `WorkflowSchema.java`, `SchemaService.java`, `ExecutionWebSocketHandler.java`
- **Frontend:** `App.vue`, `router/index.ts`, `stores/schemaStore.ts`, `stores/panelStore.ts`, `views/SettingsView.vue`

### What will be deleted (frontend/src/ only)
- `views/HomeView.vue` → replaced by `DashboardView.vue`
- `components/canvas/` (WorkflowCanvas.vue, NodeContextMenu.vue) → replaced by `components/studio/`
- `components/panels/` (RightPanel.vue) → replaced by `BlockConfigPanel.vue`
- `components/execution/` (ExecutionPanel.vue, ExecutionHistory.vue) → replaced by `LiveView.vue`, `TimelineView.vue`
- `components/plan/` (PlanPanel.vue) → removed from UI
- `components/memory/` (MemoryGraphView.vue) → removed from UI
- `components/ui/OnboardingModal.vue`, `CoachmarkOverlay.vue`, `SchemaBuilderModal.vue`, `CommandPalette.vue`, `ShortcutsOverlay.vue`
- `components/TheWelcome.vue`, `components/WelcomeItem.vue`

---

## Dependency Graph (all tasks sequential)

```
Batch 1 (parallel): 1.1, 1.2, 1.3  [PRE-SYNC: backup + diff review]
Batch 2 (sequential): 2.1, 2.2, 2.3, 2.4, 2.5  [SYNC EXECUTION]
Batch 3 (parallel): 3.1, 3.2, 3.3  [POST-SYNC VALIDATION]
Batch 4 (sequential): 4.1, 4.2, 4.3  [VERIFICATION + ROLLBACK STANDING BY]
```

---

## Batch 1: Pre-Sync (parallel — 3 implementers)

### Task 1.1: Backup backend main dir
**File:** N/A (shell operations)
**Depends:** none
**Executor executor**

```bash
# Create timestamped backup of backend src/
BACKUP_DIR="/Users/evgenijtihomirov/git/Axolotl/Axolotl/.sync-backups/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR/backend"
cp -a backend/src/ "$BACKUP_DIR/backend/src"
echo "Backend backed up to: $BACKUP_DIR/backend/src"
```

**Verify:**
```bash
ls -la "$BACKUP_DIR/backend/src/main/java/com/agent/orchestrator/model/" | head -5
```
Expected: shows existing model files (WorkflowSchema.java, etc.)

---

### Task 1.2: Backup frontend main dir
**File:** N/A (shell operations)
**Depends:** none
**Executor executor**

```bash
# Create timestamped backup of frontend src/
BACKUP_DIR="/Users/evgenijtihomirov/git/Axolotl/Axolotl/.sync-backups/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR/frontend"
cp -a frontend/src/ "$BACKUP_DIR/frontend/src"
echo "Frontend backed up to: $BACKUP_DIR/frontend/src"
```

**Verify:**
```bash
ls "$BACKUP_DIR/frontend/src/views/"
```
Expected: shows HomeView.vue (which will be deleted after sync)

---

### Task 1.3: Diff review — preview changes before sync
**File:** N/A (shell operations)
**Depends:** none
**Executor executor**

Run a dry-run rsync to preview what will change, and save the log:

```bash
# Dry-run to show what will be synced
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAIN_BACKEND="$SCRIPT_DIR/backend"
MAIN_FRONTEND="$SCRIPT_DIR/frontend"
TEST_BACKEND="$SCRIPT_DIR/backend-next"
TEST_FRONTEND="$SCRIPT_DIR/frontend-next"

echo "=== BACKEND CHANGES ===" > /tmp/sync-preview.txt
rsync -av --delete --dry-run \
  "$TEST_BACKEND/src/" "$MAIN_BACKEND/src/" \
  --exclude='target/' --exclude='.mvn/' --exclude='*.log' \
  >> /tmp/sync-preview.txt 2>&1

echo "" >> /tmp/sync-preview.txt
echo "=== FRONTEND CHANGES ===" >> /tmp/sync-preview.txt
rsync -av --delete --dry-run \
  "$TEST_FRONTEND/src/" "$MAIN_FRONTEND/src/" \
  --exclude='node_modules/' --exclude='dist/' --exclude='dist-electron/' \
  >> /tmp/sync-preview.txt 2>&1

cat /tmp/sync-preview.txt
```

**Manual review checkpoint:** Before proceeding to Batch 2, confirm:
- `deleting` lines show expected removals only (no accidental config deletions)
- Backend changes show: AppModel.java created, AppController.java created, SchemaService.java overwritten, WorkflowSchema.java overwritten, ExecutionWebSocketHandler.java overwritten
- Frontend changes show: all expected new files created, all expected deletes

**Decision:** If unexpected deletions appear → ABORT, investigate, do NOT proceed to Batch 2.
**Proceed:** If everything matches expectations → continue to Batch 2.

---

## Batch 2: Sync Execution (sequential — 1 implementer)

**Critical:** All Batch 1 must complete successfully before Batch 2 starts.

### Task 2.1: Run sync-from-test.sh
**File:** N/A (executes script)
**Depends:** 1.1, 1.2, 1.3 (backups done, diff reviewed)
**Executor executor**

```bash
bash scripts/sync-from-test.sh
```

**Expected output:**
```
Syncing test → main dirs...
<rsync output showing files transferred>
Done. Main dirs updated from test.
Restart Axolotl to use verified changes.
```

**Verify files transferred:**
```bash
# Check backend new files exist
ls -la backend/src/main/java/com/agent/orchestrator/model/AppModel.java
ls -la backend/src/main/java/com/agent/orchestrator/controller/AppController.java
# Check frontend new files exist
ls -la frontend/src/views/DashboardView.vue
ls -la frontend/src/views/StudioView.vue
ls -la frontend/src/stores/settingsStore.ts
ls -la frontend/src/templates/index.ts
# Check deleted files are gone
test ! -f frontend/src/views/HomeView.vue && echo "HomeView.vue deleted: OK"
test ! -d frontend/src/components/canvas && echo "canvas dir deleted: OK"
test ! -d frontend/src/components/panels && echo "panels dir deleted: OK"
test ! -d frontend/src/components/execution && echo "execution dir deleted: OK"
```

---

### Task 2.2: Copy migrate script (not covered by rsync)
**File:** N/A (rsync only covers src/)
**Depends:** 2.1
**Executor executor**

```bash
# Copy the migration script from backend-next/scripts/ to scripts/
cp backend-next/scripts/migrate-schemas-to-apps.py scripts/migrate-schemas-to-apps.py
chmod +x scripts/migrate-schemas-to-apps.py
echo "Migration script copied"
```

**Verify:**
```bash
ls -la scripts/migrate-schemas-to-apps.py
```

---

### Task 2.3: Verify App.vue CSS vars transferred correctly
**File:** `frontend/src/App.vue` (read-only check)
**Depends:** 2.1
**Executor executor**

```bash
# Check that light theme CSS vars are present (redesign feature)
grep -c 'data-theme="light"' frontend/src/App.vue && echo "Light theme vars: OK"
grep -c 'settingsStore.initTheme' frontend/src/App.vue && echo "initTheme() call: OK"
```

**Expected:** Both grep commands return > 0 matches

---

### Task 2.4: Verify router has new routes
**File:** `frontend/src/router/index.ts` (read-only check)
**Depends:** 2.1
**Executor executor**

```bash
# Check new routes are present
grep -E "path: '/'" frontend/src/router/index.ts | head -3
grep -E "DashboardView|StudioView" frontend/src/router/index.ts | head -5
```

**Expected:** DashboardView for `/`, StudioView for `/app/:id`

---

### Task 2.5: Verify settingsStore exists with theme support
**File:** `frontend/src/stores/settingsStore.ts` (read-only check)
**Depends:** 2.1
**Executor executor**

```bash
# Check theme-related exports
grep -E "theme|Theme" frontend/src/stores/settingsStore.ts | head -10
```

---

## Batch 3: Post-Sync Validation (parallel — 3 implementers)

### Task 3.1: Backend compile check
**File:** N/A (shell command)
**Depends:** 2.1
**Executor executor**

```bash
cd backend && mvn compile -q 2>&1
```

**Expected output:** No output (silent = success). Exit code 0.

**On failure:** Stop all Batch 3 tasks. Execute rollback (Task 4.1).

**Timeout:** 120 seconds (Maven downloads may be slow)

---

### Task 3.2: Frontend type-check
**File:** N/A (shell command)
**Depends:** 2.1
**Executor executor**

```bash
cd frontend && npm run type-check 2>&1
```

**Expected output:** No errors (vue-tsc returns exit 0). May show some warnings — warnings are OK, errors are not.

**On failure:** Stop all Batch 3 tasks. Execute rollback (Task 4.1).

**Timeout:** 60 seconds

---

### Task 3.3: Frontend Vite build
**File:** N/A (shell command)
**Depends:** 2.1
**Executor executor**

```bash
cd frontend && npm run build-only 2>&1
```

**Expected output:** Build completes successfully, output in `frontend/dist/`.

**On failure:** This is less critical than compile/type-check (build can fail due to missing assets). If this fails but 3.1 and 3.2 pass, issue a WARNING but do NOT rollback automatically.

**Timeout:** 120 seconds

---

## Batch 4: Route Verification (sequential — 1 implementer)

### Task 4.1: Kill any running backend, start fresh
**File:** N/A (shell command)
**Depends:** 3.1, 3.2, 3.3
**Executor executor**

```bash
scripts/dev.sh stop 2>/dev/null || true
sleep 2
scripts/dev.sh start
```

**Verify startup:**
```bash
sleep 10
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/api/schemas
```
Expected: 200 or 401 (auth required) — server is running

---

### Task 4.2: Verify key API routes
**File:** N/A (shell command)
**Depends:** 4.1
**Executor executor**

```bash
# Test API routes (may require auth, so just check they respond)
echo "=== API Route Checks ==="

# Health / schema list
curl -s -o /dev/null -w "GET /api/schemas: %{http_code}\n" http://localhost:8082/api/schemas

# App endpoints
curl -s -o /dev/null -w "GET /api/app: %{http_code}\n" http://localhost:8082/api/app

# Templates
curl -s -o /dev/null -w "GET /api/app/templates: %{http_code}\n" http://localhost:8082/api/app/templates

# Login (should be accessible)
curl -s -o /dev/null -w "POST /api/auth/login: %{http_code}\n" -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}' 2>/dev/null || echo "POST /api/auth/login: (auth check)"
```

**Expected:** All routes return 200, 401, or 400 (not 404). If any return 404 → investigate missing AppController or routes.

---

### Task 4.3: Start frontend dev server and verify routes
**File:** N/A (shell command — runs in background)
**Depends:** 4.2
**Executor executor**

```bash
# Start frontend dev in background
cd frontend && npm run dev -- --port 5173 &
FRONTEND_PID=$!
sleep 5

echo "=== Frontend Route Checks ==="

# Dashboard
curl -s -o /dev/null -w "GET / (Dashboard): %{http_code}\n" http://localhost:5173/

# Studio (redirects to /login since we're not authenticated)
curl -s -o /dev/null -w "GET /app/test-id: %{http_code}\n" http://localhost:5173/app/test-id

# Settings
curl -s -o /dev/null -w "GET /settings: %{http_code}\n" http://localhost:5173/settings

# Login
curl -s -o /dev/null -w "GET /login: %{http_code}\n" http://localhost:5173/login

# Kill the dev server
kill $FRONTEND_PID 2>/dev/null || true
```

**Expected:** All routes return 200 (Vite SPA serves index.html for all routes).

---

## Rollback Plan

If ANY of Batch 3 (compile/type-check/build) fails:

### Immediate rollback — Task RB-1: Restore from backup
**File:** N/A (shell command)
**Executor executor**

```bash
# Find the latest backup
BACKUP_DIR="/Users/evgenijtihomirov/git/Axolotl/Axolotl/.sync-backups"
LATEST=$(ls -t "$BACKUP_DIR" | head -1)

if [ -z "$LATEST" ]; then
  echo "ERROR: No backup found! Manual restore needed."
  echo "git checkout -- backend/src/ frontend/src/"
  exit 1
fi

echo "Restoring from backup: $BACKUP_DIR/$LATEST"

# Restore backend
rm -rf backend/src/
cp -a "$BACKUP_DIR/$LATEST/backend/src" backend/src/
echo "Backend restored"

# Restore frontend
rm -rf frontend/src/
cp -a "$BACKUP_DIR/$LATEST/frontend/src" frontend/src/
echo "Frontend restored"

# Delete migrated script if it was copied
rm -f scripts/migrate-schemas-to-apps.py
echo "Migration script removed"
```

**After restore, verify:**
```bash
# Check HomeView.vue is back (proves restore worked)
ls -la frontend/src/views/HomeView.vue
test -d frontend/src/components/canvas && echo "canvas dir restored: OK"
test -d frontend/src/components/panels && echo "panels dir restored: OK"
test -d frontend/src/components/execution && echo "execution dir restored: OK"
```

### Task RB-2: Recompile to verify rollback
**Executor executor**

```bash
cd backend && mvn compile -q && echo "Backend compiles after rollback: OK"
cd frontend && npm run type-check && echo "Frontend type-checks after rollback: OK"
```

If rollback also fails → the issue exists in the source (frontend-next/backend-next). Debug the `-next/` dirs directly.

---

## Success Criteria

All of the following must be true:

| # | Check | How |
|---|-------|-----|
| 1 | `cd backend && mvn compile -q` | Exit code 0, no output |
| 2 | `cd frontend && npm run type-check` | Exit code 0 |
| 3 | `cd frontend && npm run build-only` | Exit code 0 |
| 4 | Backend starts on :8082 | `curl localhost:8082/api/schemas` returns 200/401 |
| 5 | GET /api/app returns 200/401 | AppController endpoints work |
| 6 | GET /api/app/templates returns 200/401 | Template endpoint works |
| 7 | Frontend serves / route | Vite dev server responds 200 |
| 8 | Frontend serves /app/:id route | Vite dev server responds 200 |
| 9 | Frontend serves /settings route | Vite dev server responds 200 |

## Post-Sync Manual Steps (not automated)

1. **Start main Axolotl** — `cd backend && mvn spring-boot:run` (port 8080) + `cd frontend && npm run dev` (port 5173)
2. **Login as tech/tech** — verify dashboard loads with app cards
3. **Create new app** — verify Blueprint view renders with block palette
4. **Run an app** — verify Live mode activates
5. **Check Timeline** — verify execution trace renders
6. **Toggle theme** — verify light/dark work in Settings
7. **Run migration script** — `source .venv/bin/activate && python3 scripts/migrate-schemas-to-apps.py`
8. **Verify existing schemas still work** — old saved schemas load correctly

