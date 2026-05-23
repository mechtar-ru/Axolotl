# Бережно (Emotion Tracker) — Multi-Session Build Plan

## 1. Purpose

Build a production-quality Flutter mobile app (iOS + Android) for Russian-speaking users to identify, log, and understand their emotions. Uses Axolotl's multi-session pipeline pattern: each session builds on the previous one's generated files, with progressive verification.

## 2. Architecture

### Stack

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Framework | Flutter (Dart) | Per spec recommendation, single codebase for iOS + Android |
| Local DB | SQLite via `sqflite` | ACID, offline-first |
| State management | Provider | Lightweight, sufficient for this scope |
| Charts | `fl_chart` | Open-source, supports donut + line charts |
| Localization | `flutter_localizations` + Russian locale | No language toggle needed |
| Dependency injection | Provider (simple DI) | Matches state management choice |

### Target Directory

```
/Users/evgenijtihomirov/git/Бережно/
```

All sessions write to this directory. Each session reads existing files before writing.

### Session Dependency Graph

```
Session 1 (Scaffold + Data)
    ├── Session 2 (Emotion Log)
    │       └── Session 3 (History + Analytics)
    └── Session 4 (Profile + Tips + Polish) — reads all prior files
```

No session should overwrite files from a prior session unless fixing a bug.

## 3. Session Workflow

Each session = one Axolotl pipeline execution:

```
Receive (loads 003.md) → Review (plan) → Agent (code) → Verify (quality gate) → Output (report)
```

1. User clicks "Execute Pipeline" in Studio
2. Receive loads the requirements spec from `003.md`
3. Review generates a session-specific plan → pauses for human approval
4. Agent reads existing files + writes new ones specific to this session
5. Verify checks:
   - New/modified files have real implementations (no stubs, no TODOs)
   - Minimum code volume per file (≥15 lines of real code)
   - Imports reference real packages
   - Classes/functions have real bodies
6. If Verify detects stubs → auto-retry with stronger prompt (up to 3 attempts)
7. Output lists all generated/modified files with descriptions

## 4. Session Breakdown

### Session 1: Scaffold + Data Layer

**Goal:** Create the Flutter project skeleton with tab navigation, theme, Russian locale, and the full SQLite data layer.

**System prompt for Agent:**
```
You are building an emotion tracker Flutter app called "Как мы чувствуем".

CREATE these files in /Users/evgenijtihomirov/git/Бережно/:

1. pubspec.yaml — Flutter project with dependencies: flutter sdk, sqflite, provider, intl, fl_chart, path_provider, share_plus, shared_preferences

2. lib/main.dart — App entry point with:
   - MaterialApp with Russian locale
   - 4-tab bottom navigation (Log, History, Analytics, Profile)
   - Dark/light theme support
   - ThemeData with Russian-appropriate color palette

3. lib/app_theme.dart — Theme configuration:
   - Light and dark theme definitions
   - Custom color scheme matching the 6 emotion categories (Зелёный/Спокойствие, Синий/Печаль, Красный/Гнев, Жёлтый/Тревога, Фиолетовый/Удовольствие, Серый/Апатия)
   - Russian-appropriate typography

4. lib/models/emotion_entry.dart — Full data model:
   - id (String/UUID)
   - timestamp (DateTime, ISO8601)
   - basicEmotion (enum: calm, sadness, anger, anxiety, pleasure, apathy)
   - specificFeeling (String, max 50 chars, Russian)
   - intensity (int, 1-5)
   - note (String?, max 500 chars)
   - contextTags (List<String>, max 3: home/work/public/alone)
   - activity (String?)
   - synced (bool)
   - toMap() / fromMap() for SQLite serialization

5. lib/services/database_service.dart — Full SQLite service:
   - Singleton pattern
   - onCreate: CREATE TABLE emotion_entries with all fields
   - CRUD: insert, getById, getAll, getByDateRange, update, delete
   - Query methods: getByDateRange, getEmotionDistribution, getTopFeelings, getMoodTrend
   - deleteAll (for account deletion)
   - Close method

6. lib/services/settings_service.dart — SharedPreferences wrapper:
   - dailyReminderEnabled (bool)
   - dailyReminderTime (String "HH:MM")
   - theme (String: light/dark/system)
   - userId (String?)
   - onboardingCompleted (bool)
   - Load/save methods

RULES:
- Read existing files first before creating new ones
- Do NOT delete or modify files from other sessions
- Generate COMPLETE implementations — no stubs, no TODOs, no placeholder comments
- Each file must have ≥15 lines of real code (not counting comments or blank lines)
- Use Provider for dependency injection
- All UI text must be in Russian
- Follow Flutter best practices (const constructors, proper dispose, etc.)
```

**Verify criteria:**
- `pubspec.yaml` exists with ≥5 dependencies
- `main.dart` exists with ≥30 lines real code
- `emotion_entry.dart` exists with model + enum + serialization
- `database_service.dart` exists with CRUD + 3+ query methods
- `settings_service.dart` exists with getter/setter for 4+ settings
- No file contains `// TODO`, `// stub`, `// placeholder`, or empty function bodies
- Each .dart file has ≥15 lines of real code

**Expected files created:** 6 files, ~400-600 lines total

---

### Session 2: Emotion Log Screen

**Goal:** Build the core emotion logging UI — color-coded emotion grid, category expansion, intensity selection, note entry, and save flow.

**System prompt for Agent:**
```
You are continuing the emotion tracker app "Как мы чувствуем" in /Users/evgenijtihomirov/git/Бережно/.

Read the existing files first. Do NOT modify any existing files unless they contain clear bugs.

CREATE these files:

1. lib/screens/emotion_log_screen.dart — Main log tab screen:
   - AppBar with current date/time (editable pencil icon)
   - 6 large color circles in 2 rows of 3 (matching emotion categories)
   - Each circle: emotion name (Спокойствие, Печаль, etc.) in white text
   - Colors: Зелёный=#4CAF50, Синий=#2196F3, Красный=#F44336, Жёлтый=#FFC107, Фиолетовый=#9C27B0, Серый=#607D8B
   - Tapping a circle → opens EmotionDetailSheet
   - After selecting feeling + intensity → show tip from tips engine
   - Subtle save confirmation animation
   - Uses DatabaseService via Provider

2. lib/widgets/emotion_detail_sheet.dart — Bottom sheet for emotion details:
   - Title: emotion category name
   - Grid/list of 7+ specific feelings (Russian, e.g. тоска, грусть, разочарование)
   - Feeling displayed in nominative case as header
   - Description shows "Я чувствую [feeling in accusative]"
   - Intensity selector: 5 buttons or slider (1-5)
   - Optional: note field (≤500 chars, with counter)
   - Optional: context tags (Home/Work/Public/Alone as Chips)
   - Optional: activity text field
   - "Save" button that writes to DatabaseService

3. lib/widgets/emotion_grid.dart — Reusable 6-circle grid widget:
   - 2 rows × 3 columns layout
   - Accepts onEmotionSelected callback
   - Responsive sizing
   - Accessible labels (Russian)

4. lib/services/tips_service.dart — Tip engine:
   - Map of basic emotion → tip text (1-3 sentences, Russian)
   - Tips are culturally relevant, non-clinical
   - Example: "Попробуйте дыхание квадратом: вдох 4 с – задержка 4 с – выдох 4 с – задержка 4 с."
   - getTipForEmotion(basicEmotion) method
   - At least 2 tips per emotion category

RULES:
- Read existing files before writing
- Import from existing models/services (emotion_entry.dart, database_service.dart)
- Generate COMPLETE implementations — no stubs, no TODOs
- Use Provider to access DatabaseService
- Russian language for all UI text
- Accessible: add Semantics labels for VoiceOver/TalkBack
- Save writes to SQLite via DatabaseService
- After save, show brief confirmation animation and return to main screen
- Each file must have ≥20 lines of real code
```

**Verify criteria:**
- `emotion_log_screen.dart` exists with 6-color grid + date display + save flow
- `emotion_detail_sheet.dart` exists with feeling list + intensity + note + save
- `emotion_grid.dart` exists with responsive 2×3 layout + callback
- `tips_service.dart` exists with ≥2 tips per emotion (12+ total)
- Each file has ≥20 lines of real code
- No stubs, no TODOs, no placeholder comments
- File imports reference real existing packages/files

**Expected files created:** 4 files, ~500-700 lines total

---

### Session 3: History + Analytics

**Goal:** Build the calendar view (color-coded days), analytics screen with charts, and data export.

**System prompt for Agent:**
```
You are continuing the emotion tracker app "Как мы чувствуем" in /Users/evgenijtihomirov/git/Бережно/.

Read the existing files first. Do NOT modify existing files from Sessions 1-2.

CREATE these files:

1. lib/screens/history_screen.dart — History tab screen:
   - Month calendar grid with navigation arrows
   - Days with entries show small color dot (dominant emotion color)
   - No dot = no entry for that day
   - Tap a day → list of entries (time, emotion name, intensity bar)
   - Swipe left on entry to delete (with confirmation)
   - Uses DatabaseService.getByDateRange

2. lib/screens/analytics_screen.dart — Analytics tab screen:
   - Date range filter presets: 1W, 1M, 3M, 1Y, custom
   - Line chart: mood intensity trend over time (fl_chart)
   - Donut chart: emotion distribution by basic color (fl_chart)
   - Top 5 most frequent specific feelings list
   - Average intensity per basic emotion
   - Export button (JSON + CSV via share sheet)
   - Uses DatabaseService query methods

3. lib/widgets/emotion_calendar.dart — Reusable calendar widget:
   - Month grid with day cells
   - Color-coded dots per day
   - onDaySelected callback
   - Proper month/year header with navigation

4. lib/utils/export_service.dart — Export utility:
   - generateJSON(): all entries as formatted JSON
   - generateCSV(): all entries as CSV with headers
   - Share via platform share sheet (share_plus)
   - Respects privacy (no data leaves device unless user explicitly shares)

RULES:
- Read existing files before writing
- Import from existing models and services
- Use fl_chart package for charts (LineChart, PieChart)
- Locale-aware date formatting: DD.MM.YYYY, 24-hour time
- All UI text in Russian
- Accessible: add Semantics labels for chart elements
- Generate COMPLETE implementations — no stubs, no TODOs
- Each file must have ≥30 lines of real code
- Charts must have real data binding, not mock data
```

**Verify criteria:**
- `history_screen.dart` exists with calendar + day detail + delete
- `analytics_screen.dart` exists with donut + line chart + export
- `emotion_calendar.dart` exists with navigation + color dots + callback
- `export_service.dart` exists with JSON + CSV generation
- Each file has ≥30 lines of real code
- Charts use fl_chart with real data from DatabaseService
- No stubs, no TODOs, no placeholder comments

**Expected files created:** 4 files, ~600-900 lines total

---

### Session 4: Profile + Tips + Onboarding + Polish

**Goal:** Complete the app with settings, resources, onboarding flow, and accessibility polish.

**System prompt for Agent:**
```
You are finishing the emotion tracker app "Как мы чувствуем" in /Users/evgenijtihomirov/git/Бережно/.

Read ALL existing files first. You may modify existing files from Sessions 1-3 if you find bugs or missing features, but do NOT delete any files.

CREATE these files:

1. lib/screens/profile_screen.dart — Profile tab screen:
   - Section: Account (guest mode vs registered, upgrade option)
   - Section: Notifications (daily reminder toggle, time picker)
   - Section: Appearance (Light/Dark/System theme toggle)
   - Section: Data (Export, Delete all data)
   - Section: About (version, privacy policy link, disclaimer)
   - Delete confirmation dialog with "Я уверен" text input
   - Uses SettingsService via Provider

2. lib/screens/onboarding_screen.dart — 2-screen onboarding:
   - Screen 1: "Как мы чувствуем" — value proposition (Russian)
   - Screen 2: Privacy policy summary, data collection disclosure (per 152-ФЗ)
   - "Начать" button → saves onboardingCompleted=true in SettingsService
   - Skip option
   - Smooth page transition

3. lib/widgets/resources_list.dart — Resources screen:
   - Static list of Russian-language mental health resources
   - Hotlines with phone numbers
   - Website links
   - Disclaimer: "Приложение не заменяет профессиональную помощь. При кризисной ситуации звоните 112 или на горячую линию."

MODIFY these existing files as needed:

4. lib/main.dart — Add onboarding check flow:
   - On first launch, show OnboardingScreen before main app
   - Read onboardingCompleted from SettingsService

5. All screen files — Add accessibility polish:
   - Semantics labels for all interactive elements
   - Proper heading hierarchy
   - Focus management for keyboard/switch navigation
   - Dynamic text sizing (respects system font scale)
   - Dark mode visual testing (ensure all screens look correct in dark theme)

6. lib/app_theme.dart — Polish dark theme colors

RULES:
- Read ALL existing files before modifying
- Make minimal changes to existing files — do not refactor working code
- Test mentally: does the navigation flow work end-to-end?
- All UI text in Russian
- Date format DD.MM.YYYY, time 24-hour
- Accessibility: VoiceOver (iOS) and TalkBack (Android) must be usable
- Confirm dialog before destructive actions (delete, reset)
- Generate COMPLETE implementations — no stubs, no TODOs
```

**Verify criteria:**
- `profile_screen.dart` exists with settings sections + delete confirmation
- `onboarding_screen.dart` exists with 2 screens + skip + privacy disclosure
- `resources_list.dart` exists with real hotlines + disclaimer
- `main.dart` updated with onboarding flow
- All screens have Semantics labels
- Dark mode renders correctly on all screens
- No stubs, no TODOs, no placeholder comments

**Expected files created/modified:** 3 new + ~8 modified, ~400-600 lines total

---

## 5. Model Strategy

| Model | Use Case | Status |
|-------|----------|--------|
| `Bonsai-8B` (hf.co/prism-ml/Bonsai-8B-gguf:Q1_0) | Primary — code generation | Try first |
| `llama3.1:8b` | Fallback — if Bonsai not supported by Ollama | Already installed |

**Bonsai-8B advantages:**
- 1.15 GB → loads instantly, no memory pressure on 16GB Mac Mini
- 85 tok/s on M4 Pro → 5.4× faster than FP16 models
- Beats Llama 3.1 8B on benchmarks (70.5 vs 67.1)
- Based on Qwen3-8B (strong code generation base)

**To install Bonsai via Ollama:**
```bash
ollama pull hf.co/prism-ml/Bonsai-8B-gguf:Q1_0
```

**If Bonsai doesn't work with local Ollama:**
```bash
# Use the PrismML llama.cpp fork instead
git clone https://github.com/PrismML-Eng/llama.cpp
cd llama.cpp
cmake -B build && cmake --build build -j
./build/bin/llama-server \
    -m Bonsai-8B-Q1_0.gguf \
    --host 127.0.0.1 \
    --port 8081 \
    -ngl 99

# Then configure Axolotl to point at :8081 as an OpenAI-compatible endpoint
```

**Stage timeout:** Set to 20 minutes (accommodates the slowest model; Bonsai will be much faster)

---

## 6. Stub Detection & Auto-Retry

### Stub Detection Logic (implemented in Verify node's system prompt)

The LLM-based verify node checks each generated file:

```
1. Count lines of real code (exclude comments, blank lines, and imports)
   → if < 15 lines for a .dart file, flag as STUB

2. Check for stub patterns in file content:
   → "// TODO" → flag
   → "// stub" → flag
   → "// placeholder" → flag
   → "return null;" in a method that should return data → flag
   → empty class body "class Foo {}" → flag
   → "throw UnimplementedError()" → flag

3. Verify imports reference real packages:
   → "import 'package:unknown_package'" → flag

4. Check function/method bodies are non-empty:
   → "void foo() {}" → flag
   → "String bar() => '';" → flag (for meaningful return types)

Verdict: PASS only if 0 flags. FAIL otherwise.
Output format:
{
  "status": "FAIL",
  "checks": [
    {"name": "stub_detection", "passed": false, "details": "File lib/models/emotion_entry.dart has only 1 line of real code"},
    {"name": "min_lines", "passed": true},
    {"name": "no_todos", "passed": false, "details": "Found '// TODO' in lib/main.dart line 15"}
  ],
  "summary": "2/3 checks failed. Session needs retry."
}
```

### Auto-Retry Flow

```
Agent writes code
    ↓
Verify detects stubs? → Yes → retryAttempt < 3? → Yes → Strengthen prompt and re-run Agent
    ↓                                                         ↓
   PASS                                                    No → FAIL pipeline, stop
    ↓
Continue to Output node
```

**Progressive prompt strengthening on retry:**

| Attempt | Prompt adjustment |
|---------|------------------|
| 1st | Original prompt |
| 2nd | Prefix: "The previous attempt generated stub/placeholder code. This is unacceptable. Write COMPLETE implementations. Every class must have working methods. Every file must have ≥15 lines of real code." |
| 3rd | Prefix: "FINAL ATTEMPT. If this attempt also generates stubs, the pipeline will fail. Write production-quality code. No exceptions." |

---

## 7. Verification Strategy

### Per-Session Verification

Each session's Verify node checks three layers:

| Layer | What it checks | Failure action |
|-------|---------------|----------------|
| Completeness | All expected files exist from the session's file list | Auto-retry |
| Quality | No stubs, TODOs, placeholders; ≥15-30 lines per file | Auto-retry |
| Consistency | Imports match existing files; models used correctly | Auto-retry |

### Pipeline-Level Gates

| Gate | When | Criteria |
|------|------|----------|
| Review | Every session | Human must approve the session plan |
| Verify | Every session | Auto quality gate (pass = continue, fail = retry up to 3×) |
| Manual spot-check | After each session | User opens generated files and sanity-checks |

### What a Failed Session Looks Like

- Pipeline status: `failed`
- Verify node output shows which files had issues
- User reads the error, decides: modify prompt and retry, or accept partial result

---

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Bonsai-8B not supported by local Ollama | Medium | High — need fallback model | Fallback to llama3.1:8b; or run PrismML's llama.cpp fork as OpenAI-compatible server |
| 7B/8B model generates stubs/code too simple | Medium | High — wasted pipeline run | Verify node detects stubs; auto-retry; if persistent, switch to stronger model |
| Session N overwrites Session N-1 files | Medium | High — lost work | System prompt explicitly forbids; Verify checks file boundaries |
| Flutter SDK not in PATH | Medium | Low (env issue) | Agent can check `which flutter` and report; acceptable for early sessions |
| Cross-session import conflicts | Medium | Medium | System prompt: "Read existing files first"; Verify checks import correctness |
| Verify LLM misses stubs | Low | Medium | Conservative threshold (<15 lines = flag); manual spot-check after each session |
| Memory exhaustion (16GB) | Low | High — app crashes | Bonsai uses 1.15GB; llama3.1:8b uses ~5GB; both fit comfortably |

---

## 9. File Map

Final expected file tree after all 4 sessions:

```
/Users/evgenijtihomirov/git/Бережно/
├── pubspec.yaml                          [S1]
├── lib/
│   ├── main.dart                         [S1] + [S4] onboarding flow
│   ├── app_theme.dart                    [S1] + [S4] dark theme polish
│   ├── models/
│   │   └── emotion_entry.dart            [S1]
│   ├── services/
│   │   ├── database_service.dart         [S1]
│   │   ├── settings_service.dart         [S1]
│   │   └── tips_service.dart             [S2]
│   ├── screens/
│   │   ├── emotion_log_screen.dart       [S2]
│   │   ├── history_screen.dart           [S3]
│   │   ├── analytics_screen.dart         [S3]
│   │   ├── profile_screen.dart           [S4]
│   │   └── onboarding_screen.dart        [S4]
│   ├── widgets/
│   │   ├── emotion_grid.dart             [S2]
│   │   ├── emotion_detail_sheet.dart     [S2]
│   │   ├── emotion_calendar.dart         [S3]
│   │   └── resources_list.dart           [S4]
│   └── utils/
│       └── export_service.dart           [S3]
```

[S1]: Created in Session 1
[S2]: Created/modified in Session 2, etc.
[S1] + [S4]: Created in S1, modified in S4

---

## 10. Implementation Tasks for Axolotl

To enable this plan, the following changes are needed in the Axolotl backend/frontend:

1. **Increase `STAGE_TIMEOUT_MINUTES`** from 5 to 20 in `PipelineService.java`
2. **Add `clearStaleApprovals()`** — clears previous run's approval flags on new pipeline start (DONE)
3. **Update pipeline stages** with session-specific system prompts for each of the 4 sessions
4. **Add stub detection** to the Verify node system prompt (the LLM-based verifier checks for stubs)
5. **Add auto-retry logic** to the pipeline when Verify returns FAIL for stub detection
6. **Set default model** per stage to Bonsai-8B or llama3.1:8b depending on availability
7. **Fix pipeline failure propagation** to stop execution on FAIL and mark run as failed (DONE)

Tasks 1-3, 6-7: Backend Java changes
Tasks 4-5: Both backend (verify strategy) and frontend (prompts)

---

*Plan version 1.0 — 2026-05-23*
