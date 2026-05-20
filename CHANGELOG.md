# Changelog

All notable changes to Axolotl are documented in this file.

Format: Keep a Changelog (https://keepachangelog.com/)  
Versioning: SemVer via git tags.

## [Unreleased]

### Added

- Multi-stage pipelines (stage-level execution, persistence, resume/retry semantics).
- Pipeline Panel in Studio (Build, Execute, Cancel, Retry controls and per-stage status).
- Cross-stage artifact passing via `Stage.inputMapping` (dot-notation field extraction from upstream stage outputs).

### Changed

- Neo4j defaults and logging tweaks.
- JWT auth filter behavior clarified (expired tokens may be skipped at filter-level; protected endpoints still require auth).

### Fixed

- Various execution/resume edge-cases and review-node JSON handling.

---

## [v0.2.1] - 2026-04-20

### Added

- Initial pipeline primitives and execution persistence.

### Fixed

- NodeRouter status propagation fixes; persisted run shape fixes.

---

<!-- For maintainers: add one-line PR entries under [Unreleased] when your PR introduces user-visible changes. At release time, promote Unreleased into a new versioned section and close it out. -->
