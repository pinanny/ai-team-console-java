## PROJECT_MEMORY_START

## BRIEF_START
Java 23 desktop monolith built with Maven and JavaFX 23 (`pom.xml`, `mvn javafx:run`). Single package tree `com.example.aiteamconsole` coordinates AI engineering agents against GitHub repos. Root package holds domain records (`AgentProfile`, `AgentTask`, `AgentRun`), `AgentProvider` implementations (`CursorCloudAgentProvider`, `OllamaAgentProvider`), GitHub REST clients, and Jackson persistence to `~/.ai-team-console-java/state.json`. `ui/` is MVVM JavaFX: `MainViewModel` plus tab Views/ViewModels for Agents, Tasks, Runs, and modal settings windows. `pixel/PixelOfficeStage` is an optional canvas visualization. Role prompts live in `src/main/resources/agent-prompts/*.md`. JUnit 5 unit tests mirror the main package under `src/test/java`.
## BRIEF_END

## CORE_START
#### Stack
- **Language:** Java 23 (`maven.compiler.release` in `pom.xml`)
- **UI:** JavaFX 23.0.2 (`javafx-controls`); entry `com.example.aiteamconsole.AiTeamConsoleApplication`
- **Libraries:** Jackson 2.18.2 + `jackson-datatype-jsr310`; `java.net.http.HttpClient` (no Spring)
- **Build/test:** Maven; `javafx-maven-plugin` (`mvn javafx:run`); JUnit Jupiter 5.11.4 + Surefire 3.5.2
- **Deployment:** local desktop only. Config under `~/.ai-team-console-java/` (`state.json`, `cursor-api.json`, `github-session.json`, `logs/app-*.log`). Env: `CURSOR_API_KEY`, optional `CURSOR_API_BASE_URL`, `GITHUB_OAUTH_CLIENT_ID`, `AI_TEAM_LOG_PROMPT_TEXT`

#### Architecture
- **Shape:** single-module monolith; all production code in `src/main/java/com/example/aiteamconsole/`
- **Orchestration:** `MainViewModel` owns `ObservableList`s for agents, tasks, runs, repositories; loads/saves `AppState` via `StateStore`; polls non-terminal runs every 8s (`AiTeamConsoleApplication` Timeline)
- **Domain layer (root package):** immutable records — `AgentProfile` (role, provider, branch prefix, repo tags), `AgentTask` (prefix/number key, status lifecycle, repo tags), `AgentRun` (external Cursor/Ollama ids, PR URL, logs), `RepositoryEntry` (URL + display name whose `tag()` resolves tasks). Enums: `AgentRole`, `TaskStatus`, `RunStatus`, `ProviderType`
- **Providers:** `AgentProvider` interface (`startTask`, `refreshRun`, `cancelRun`); `ProviderRegistry` selects `CursorCloudAgentProvider` (Cursor REST `POST/GET /v1/agents`) or `OllamaAgentProvider` (local clone → Ollama chat → `PatchParseUtil` + `git apply` → push). `RepositoryResolver` injected at startup maps repo tags → HTTPS GitHub URLs
- **GitHub integration:** `GitHubDeviceFlowService` (OAuth device flow), `GitHubReposClient` (branches, repo import), `GitHubPullsClient` (fallback PR creation, merge watch). `GitHubJsonStore` persists session separately from `state.json`
- **UI (`ui/`):** MVVM — `AgentsView`/`AgentsViewModel`, `TasksView`/`TasksViewModel`, `RunsView`/`RunsViewModel`; `SettingsViewModel` + `ConsoleSettingsWindows` for Cursor/Ollama/GitHub; `UiEnvironment` abstracts FX thread + dialogs (`AlertUserDialogs`)
- **Main flow:** register repos (GitHub settings) → create agent (name + role; branch prefix auto `ia-agent-<slug>`) → create task with repo tags → Save&Start → `MainViewModel.startTask` → provider run → auto-refresh → task status derived from run → optional GitHub fallback PR when finished without `pullRequestUrl`

#### Key patterns & conventions
- **Records + factories:** domain types use `create()` and `with*` methods; no JPA/ORM
- **Task keys:** `{PREFIX}{NN}` via `AgentTask.taskKey()` (e.g. `BE01`); prefix normalized uppercase in `MainViewModel.normalizeTaskPrefix`
- **Prefix → role:** `MainViewModel.roleForTaskPrefix` — `BE` backend, `FE` frontend, `QA` QA, `REV` reviewer, `DEVOPS` devops; used for unassigned-task agent pick
- **Branch naming:** `AgentBranchNames.forTask` → `{branchPrefix}/{taskKey}-{title-slug}`; `sanitizeForCursorApiBranch` for API/git; shared via `resolveHeadBranch`
- **Prompts:** `AgentTaskPrompts.buildCursorStyleTaskPrompt` embeds `RolePromptCatalog` markdown from `src/main/resources/agent-prompts/{role}.md`; includes mandatory `ROLE-VERIFY:` line (`AgentTaskPrompts.VERIFY_TOKEN_PREFIX`)
- **Adding a feature:** domain logic in root package; wire through `MainViewModel`; tab UI in matching `ui/{area}/` View + ViewModel; provider changes implement `AgentProvider` or extend `CursorCloudAgentProvider`/`OllamaAgentProvider`
- **Async:** network/git on `CompletableFuture`; UI updates via `fxRunner` / `Platform.runLater`
- **Testing:** unit tests only in `src/test/java/com/example/aiteamconsole/*Test.java`; naming `{Class}Test`; Mockito not used — package-private constructors for test doubles (e.g. `CursorCloudAgentProvider(HttpClient, ObjectMapper)`)
- **Constraints:** API keys never in `state.json`; Cursor model hardcoded `composer-2` in `CursorCloudAgentProvider.CURSOR_CLOUD_MODEL_ID`; repo URLs normalized to `https://github.com/owner/repo` via `GitHubRepoUrls`; one active run per agent (`MainViewModel.isAgentFree`)

#### Domain glossary
- **Agent profile** — configured worker: name, `AgentRole`, `ProviderType`, optional repo tags, auto-derived `branchPrefix`
- **Agent task** — work item with `taskPrefix`+`taskNumber`, description, assigned agent (optional), `repositoryTags`, `TaskStatus`
- **Agent run** — single provider execution; stores `externalAgentId`/`externalRunId`, `expectedHeadBranch`, `RunLogEntry` list
- **Repository tag** — lowercase slug from `RepositoryEntry.displayName`; links tasks/agents to registry URLs
- **Repository registry** — `List<RepositoryEntry>` in `AppState`; managed in GitHub settings window
- **Provider** — `CURSOR_CLOUD` (remote Cursor API) or `OLLAMA` (local git+LLM pipeline)
- **Task prefix** — e.g. `BE`, `FE`; drives role inference and branch slug
- **Head branch** — git branch the agent must push; resolved by `AgentBranchNames`
- **Starting ref** — base branch/ref for agent checkout (`task.startingRef` overrides `agent.startingRef`)
- **WAITING_REVIEW** — task finished with a PR URL; `MainViewModel` watches GitHub merge → `DONE`
- **Role profile** — markdown operating brief per role, loaded by `RolePromptCatalog`
- **Run log** — append-only messages on `AgentRun.logs`; PR automation lines prefixed `PR automation:`
- **Auto-create PR** — `AgentProfile.autoCreatePr`; sent to Cursor API; GitHub fallback in `maybeCreateGithubPullRequestIfNeeded`
- **Pixel Office** — `pixel/PixelOfficeStage` canvas showing agents/desks synced to observable lists
## CORE_END

## EXTENDED_START
#### Stack
- **Language:** Java 23 (`maven.compiler.release` in `pom.xml`)
- **UI:** JavaFX 23.0.2 (`javafx-controls`); entry `com.example.aiteamconsole.AiTeamConsoleApplication`
- **Libraries:** Jackson 2.18.2 + `jackson-datatype-jsr310`; `java.net.http.HttpClient` (no Spring)
- **Build/test:** Maven; `javafx-maven-plugin` (`mvn javafx:run`); JUnit Jupiter 5.11.4 + Surefire 3.5.2
- **Deployment:** local desktop only. Config under `~/.ai-team-console-java/` (`state.json`, `cursor-api.json`, `github-session.json`, `logs/app-*.log`). Env: `CURSOR_API_KEY`, optional `CURSOR_API_BASE_URL`, `GITHUB_OAUTH_CLIENT_ID`, `AI_TEAM_LOG_PROMPT_TEXT`

#### Architecture
- **Shape:** single-module monolith; all production code in `src/main/java/com/example/aiteamconsole/`
- **Orchestration:** `MainViewModel` owns `ObservableList`s for agents, tasks, runs, repositories; loads/saves `AppState` via `StateStore`; polls non-terminal runs every 8s (`AiTeamConsoleApplication` Timeline)
- **Domain layer (root package):** immutable records — `AgentProfile` (role, provider, branch prefix, repo tags), `AgentTask` (prefix/number key, status lifecycle, repo tags), `AgentRun` (external Cursor/Ollama ids, PR URL, logs), `RepositoryEntry` (URL + display name whose `tag()` resolves tasks). Enums: `AgentRole`, `TaskStatus`, `RunStatus`, `ProviderType`
- **Providers:** `AgentProvider` interface (`startTask`, `refreshRun`, `cancelRun`); `ProviderRegistry` selects `CursorCloudAgentProvider` (Cursor REST `POST/GET /v1/agents`) or `OllamaAgentProvider` (local clone → Ollama chat → `PatchParseUtil` + `git apply` → push). `RepositoryResolver` injected at startup maps repo tags → HTTPS GitHub URLs
- **GitHub integration:** `GitHubDeviceFlowService` (OAuth device flow), `GitHubReposClient` (branches, repo import), `GitHubPullsClient` (fallback PR creation, merge watch). `GitHubJsonStore` persists session separately from `state.json`
- **UI (`ui/`):** MVVM — `AgentsView`/`AgentsViewModel`, `TasksView`/`TasksViewModel`, `RunsView`/`RunsViewModel`; `SettingsViewModel` + `ConsoleSettingsWindows` for Cursor/Ollama/GitHub; `UiEnvironment` abstracts FX thread + dialogs (`AlertUserDialogs`)
- **Main flow:** register repos (GitHub settings) → create agent (name + role; branch prefix auto `ia-agent-<slug>`) → create task with repo tags → Save&Start → `MainViewModel.startTask` → provider run → auto-refresh → task status derived from run → optional GitHub fallback PR when finished without `pullRequestUrl`

#### Key patterns & conventions
- **Records + factories:** domain types use `create()` and `with*` methods; no JPA/ORM
- **Task keys:** `{PREFIX}{NN}` via `AgentTask.taskKey()` (e.g. `BE01`); prefix normalized uppercase in `MainViewModel.normalizeTaskPrefix`
- **Prefix → role:** `MainViewModel.roleForTaskPrefix` — `BE` backend, `FE` frontend, `QA` QA, `REV` reviewer, `DEVOPS` devops; used for unassigned-task agent pick
- **Branch naming:** `AgentBranchNames.forTask` → `{branchPrefix}/{taskKey}-{title-slug}`; `sanitizeForCursorApiBranch` for API/git; shared via `resolveHeadBranch`
- **Prompts:** `AgentTaskPrompts.buildCursorStyleTaskPrompt` embeds `RolePromptCatalog` markdown from `src/main/resources/agent-prompts/{role}.md`; includes mandatory `ROLE-VERIFY:` line (`AgentTaskPrompts.VERIFY_TOKEN_PREFIX`)
- **Adding a feature:** domain logic in root package; wire through `MainViewModel`; tab UI in matching `ui/{area}/` View + ViewModel; provider changes implement `AgentProvider` or extend `CursorCloudAgentProvider`/`OllamaAgentProvider`
- **Async:** network/git on `CompletableFuture`; UI updates via `fxRunner` / `Platform.runLater`
- **Testing:** unit tests only in `src/test/java/com/example/aiteamconsole/*Test.java`; naming `{Class}Test`; Mockito not used — package-private constructors for test doubles (e.g. `CursorCloudAgentProvider(HttpClient, ObjectMapper)`)
- **Constraints:** API keys never in `state.json`; Cursor model hardcoded `composer-2` in `CursorCloudAgentProvider.CURSOR_CLOUD_MODEL_ID`; repo URLs normalized to `https://github.com/owner/repo` via `GitHubRepoUrls`; one active run per agent (`MainViewModel.isAgentFree`)

#### Domain glossary
- **Agent profile** — configured worker: name, `AgentRole`, `ProviderType`, optional repo tags, auto-derived `branchPrefix`
- **Agent task** — work item with `taskPrefix`+`taskNumber`, description, assigned agent (optional), `repositoryTags`, `TaskStatus`
- **Agent run** — single provider execution; stores `externalAgentId`/`externalRunId`, `expectedHeadBranch`, `RunLogEntry` list
- **Repository tag** — lowercase slug from `RepositoryEntry.displayName`; links tasks/agents to registry URLs
- **Repository registry** — `List<RepositoryEntry>` in `AppState`; managed in GitHub settings window
- **Provider** — `CURSOR_CLOUD` (remote Cursor API) or `OLLAMA` (local git+LLM pipeline)
- **Task prefix** — e.g. `BE`, `FE`; drives role inference and branch slug
- **Head branch** — git branch the agent must push; resolved by `AgentBranchNames`
- **Starting ref** — base branch/ref for agent checkout (`task.startingRef` overrides `agent.startingRef`)
- **WAITING_REVIEW** — task finished with a PR URL; `MainViewModel` watches GitHub merge → `DONE`
- **Role profile** — markdown operating brief per role, loaded by `RolePromptCatalog`
- **Run log** — append-only messages on `AgentRun.logs`; PR automation lines prefixed `PR automation:`
- **Auto-create PR** — `AgentProfile.autoCreatePr`; sent to Cursor API; GitHub fallback in `maybeCreateGithubPullRequestIfNeeded`
- **Pixel Office** — `pixel/PixelOfficeStage` canvas showing agents/desks synced to observable lists

### Per-module deep dive

**`AiTeamConsoleApplication.java`** — JavaFX `Application`; constructs `StateStore`, `ProviderRegistry`, `GitHubDeviceFlowService`, `MainViewModel`; builds `TabPane` (Agents/Tasks/Runs) and toolbar buttons opening `ConsoleSettingsWindows` and `PixelOfficeStage`.

**`MainViewModel.java`** (~1150 lines) — central coordinator: persistence (`save()`), task CRUD (`saveTaskFromInputs`, `deleteTask`, `canEditTask`), run lifecycle (`startTask`, `refreshRun`, `cancelRun`, `restartFailedRun`), 8s polling (`pollActiveRuns`), PR merge watch every 3 min (`checkWaitingReviewTasksForMergedPrs`), GitHub fallback PR (`maybeCreateGithubPullRequestIfNeeded` with head-branch candidates and backoff via `GitHubPullsClient.createPullWithHeadCandidatesAndBackoff`), branch list fetch (`refreshBranchesForTags`), repo URL resolution (`resolveRepositoryUrlsForTask`).

**`CursorCloudAgentProvider.java`** — Cursor Cloud Agents REST client. Auth: HTTP Basic, API key as username (`AppSettings.cursorApiKey`). `startTask`: builds `repos[]` from `RepositoryResolver`, constructs prompt via `AgentTaskPrompts`, POST `/v1/agents` with `branchName`, `autoCreatePR`, fixed `model.id=composer-2`. Retries `startingRef` attempts; may drop `branchName` if API rejects. `refreshRun`: GET `/v1/agents/{id}/runs/{runId}`; maps status via `RunStatus.fromCursorStatus`; detects `ROLE-VERIFY` in summary; updates `expectedHeadBranch` from JSON `branchName`. `cancelRun`: POST `.../cancel`.

**`OllamaAgentProvider.java`** — async local jobs in `ConcurrentHashMap<UUID, OllamaJob>`. Pipeline per run: clone to `~/.ai-team-console-java/ollama-workspaces/{runId}` (`LocalGitOperations`), optional Qdrant RAG (`QdrantRestClient`), Ollama chat (`OllamaHttpClient`), extract diff (`PatchParseUtil.extractUnifiedDiff`), `git apply`/commit/push. Settings from `OllamaRuntimeSettings` (base URL, model) persisted beside state. Requires GitHub OAuth for clone/push.

**`StateStore.java` / `AppState.java`** — Jackson JSON at `~/.ai-team-console-java/state.json`; fields: `agents`, `tasks`, `runs`, `repositories`. Does not store secrets.

**`CursorApiStore.java`** — optional `cursor-api.json`; loaded on startup before env fallback (`SettingsViewModel.reloadCursorFieldsFromStore`).

**`GitHubJsonStore.java`** — `github-session.json`, `github-oauth-app.json` in same directory.

**`AgentTaskPrompts.java`** — shared prompt builder for Cursor and Ollama; verification protocol and PR title/body rules.

**`RolePromptCatalog.java`** — maps each `AgentRole` to `src/main/resources/agent-prompts/*.md` (adapted from agency-agents).

**`PatchParseUtil.java`** — parses fenced or raw unified diffs from LLM output; normalizes paths for `git apply`; handles `NO_PATCH`.

**`GitHubRepoUrls.java`** — URL normalization, `owner/repo` slug parsing, PR HTML URL parsing.

**`ui/tasks/TasksView.java` + `TasksViewModel.java`** — task form (prefix, title, description, tag picker via `TaskRepositoryTagPicker`/`TagSelectionControl`, optional branch dropdown, assign developer); table with Edit/Delete/Save&Start.

**`ui/agents/AgentsView.java` + `AgentsViewModel.java`** — minimal agent form (name, role, provider, repo tags); branch prefix derived, not edited.

**`ui/runs/RunsView.java` + `RunsViewModel.java`** — run table, manual refresh/cancel/restart, run log pane (`onSelectedRunUpdated`).

**`ui/settings/ConsoleSettingsWindows.java`** — modal windows for Cursor API, Ollama, GitHub+repository registry (import repos, verify Cursor repo access).

**`pixel/PixelOfficeStage.java`** — procedural canvas office; listens to same `ObservableList`s; no external assets.

### Data models (fields)

| Record | Key fields | File |
|--------|-----------|------|
| `AgentProfile` | `id`, `name`, `role`, `provider`, `branchPrefix`, `autoCreatePr`, `repositoryTags` | `AgentProfile.java` |
| `AgentTask` | `taskPrefix`, `taskNumber`, `title`, `description`, `assignedAgentId`, `status`, `startedAt`, `endedAt`, `repositoryTags`, `startingRef` | `AgentTask.java` |
| `AgentRun` | `taskId`, `agentProfileId`, `provider`, `externalAgentId`, `externalRunId`, `status`, `pullRequestUrl`, `resultSummary`, `expectedHeadBranch`, `logs` | `AgentRun.java` |
| `RepositoryEntry` | `url`, `displayName`, `defaultBranch`; `tag()` = normalized display name | `RepositoryEntry.java` |

`TaskStatus` lifecycle: `DRAFT` → `QUEUED` → `RUNNING` → (`DONE` | `WAITING_REVIEW` | `FAILED` | `CANCELLED`). `RunStatus.terminal()`: `FINISHED`, `CANCELLED`, `ERROR`.

### Build pipeline
- `mvn compile` — Java 23 + JavaFX (requires JDK with javafx or module path via plugin)
- `mvn test` — Surefire runs `src/test/java/**/*Test.java` (8 test classes: domain, branch names, patch parse, Cursor provider HTTP mocking, repo URLs, role prompts)
- `mvn javafx:run` — launches desktop app; `AppLogging.init()` configures rotating file + console handlers

### Notable algorithms
- **Starting-ref retry** (`CursorCloudAgentProvider.startingRefAttempts`): tries blank, explicit ref, `refs/heads/{ref}` variants when Cursor rejects branch verification
- **PR head candidates** (`MainViewModel.maybeCreateGithubPullRequestIfNeeded`): run `expectedHeadBranch`, sanitized effective branch, raw requested branch; `GitHubPullsClient` retries with backoff (8 attempts) when branch not yet on GitHub
- **Finished-run PR follow-up** (`scheduleFinishedRunFollowUpPollIfNeeded`): up to 3 extra refreshes at 7s when Cursor finishes without `pullRequestUrl`
- **Branch sanitization** (`AgentBranchNames.sanitizeForCursorApiBranch`): lowercase slug, max 244 chars, hash suffix on truncation

### Known tech debt / open questions
- `AgentProfile.repositoryUrl` / `AgentTask.repositoryUrl` legacy single-repo fields still present; tag-based resolution is preferred (`RepositoryResolver` + registry)
- Cursor Cloud is opaque — role profile effectiveness only verifiable via `ROLE-VERIFY` token in run summary
- `PRODUCT_ANALYST` role exists but has no task-prefix mapping in `roleForTaskPrefix`
- No integration/e2e tests; UI untested
- Ollama path requires local `git`, running Ollama, optional Qdrant; error surface is run logs only
- Additional providers (e.g. Devin) mentioned in README as future `AgentProvider` implementations — not present
- Deployment/packaging (native installer, jlink) unknown — not configured in `pom.xml`
## EXTENDED_END

## PROJECT_MEMORY_END
