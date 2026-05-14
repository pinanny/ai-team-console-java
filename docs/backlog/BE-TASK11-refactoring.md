# BE-TASK11: Refactoring — product handoff (Product Analyst)

This document records the refined scope for **BE-TASK11**. Replace or extend the one-line task title **Refactoring** in AI Team Console with the sections below before running Backend/Frontend/QA.

---

## 1. Problem statement

- **Who is affected:** Operators and developers using **AI Team Console Java** to launch Cursor Cloud Agents, and future maintainers of this repository.
- **Pain / opportunity:** The intake title **Refactoring** does not name a subsystem, smell, or measurable outcome. Without a target, implementers risk unrelated churn, conflicting style with the recent **BE-TASK08 MVVM** work, or refactors that do not reduce real defect or maintenance cost.
- **Why it matters now:** The codebase recently underwent a significant UI architecture change (MVVM). Follow-on refactors should be **small, goal-directed, and test-backed** so behavior (Cursor launch, GitHub helpers, state persistence) stays stable.
- **Constraints:** JDK 23 + Maven; JavaFX desktop app; existing tests must remain green; no change to external API contracts (Cursor/GitHub) unless explicitly scoped; respect user data paths (`~/.ai-team-console-java/`).

---

## 2. Acceptance criteria

1. **Given** a named refactor target (class, package, or documented pain) agreed in the task description, **when** the implementation is merged, **then** `mvn test` passes with no new failures and no broad suppression of tests.
2. **Given** the refactor, **when** a developer runs `mvn javafx:run`, **then** core flows still work: save settings, create/start a task, refresh run status (smoke — manual or existing automated coverage as applicable).
3. **Given** before/after structure, **when** a reviewer reads the PR, **then** the PR explains *what* moved or simplified, *why* it is safer or clearer, and calls out any intentional no-op or behavior-preserving guarantee.
4. **Given** duplicated or dead code was in scope, **when** the change ships, **then** duplication is reduced or dead paths are removed without breaking public method contracts used by other packages (or those contracts are updated in the same PR with call sites).
5. **Negative case:** If the requested refactor would change user-visible behavior (e.g. task routing, PR automation, OAuth), **then** that must be listed explicitly in scope and covered by tests or a documented manual check — not bundled silently with a “refactor” label.

---

## 3. Prioritized scope

### Must-have

- Replace vague **Refactoring** with a **single concrete target** (e.g. “extract X from Y”, “align Z with MVVM”, “reduce duplication in `CursorCloudAgentProvider` / GitHub clients”) before heavy coding.
- Mechanical or structural change that preserves behavior; tests updated only if structure forces it.
- PR description lists files/areas touched and verification performed.

### Should-have

- Short note in code or PR on **invariants** preserved (e.g. “no change to JSON shape in `state.json`”).
- If touching UI ViewModels, follow patterns established in BE-TASK08 (naming, separation of concerns).

### Later

- **Cross-cutting style-only reformat of the whole repo** — defer: high noise, low value, obscures meaningful diffs; OK as a separate, explicit task.
- **Abstracting new providers beyond `AgentProvider`** — defer unless this task explicitly names provider extension; orthogonal to unnamed “refactoring”.
- **Performance refactoring without a reported bottleneck** — defer until metrics or profiling justify it.

---

## 4. Open questions

- **Assumption:** BE-TASK11 intends internal code quality improvement only, not new product features. If false, clarify feature vs refactor boundary.
- **Dependency:** Exact refactor target — **requires operator or tech lead input** (or a follow-on spike PR that only inventories candidates).
- **Risk:** Implementing refactors without a named target increases regression risk in Cursor API integration and GitHub OAuth/PR fallback paths.

---

ROLE-VERIFY: Product Analyst | TASK: BE-TASK11
