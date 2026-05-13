# AI Team Console Java

Desktop control panel for coordinating AI engineering agents. The first provider is Cursor Cloud Agents through the public REST API.

## Requirements

- JDK 23 available to Maven on this machine
- Maven 3.9+
- Cursor API key with access to Cloud Agents

## Run

```bash
export CURSOR_API_KEY="cursor_..."
mvn javafx:run
```

The API key can also be pasted into the Settings row in the app. By default it is held only in memory for the session — `state.json` never contains it. If you want the app to remember the key across restarts, click **Save API key** in Settings: it is then written to `~/.ai-team-console-java/cursor-api.json` for your OS user only. Use **Forget** to delete that file. On startup the saved key is loaded first, then `CURSOR_API_KEY` is used as a fallback.

## What Is Implemented

- Agent profiles: the only required inputs are **Name** and **Role**. The git **Branch prefix** is auto-derived from the name as `ia-agent-<slug>`, so the Tasks table can group changes by agent without extra setup. Provider defaults to Cursor Cloud and auto-PR is on by default. Agents can be edited/deleted and linked to one or more repository names/tags.
- Tasks may be assigned to a specific developer, or left unassigned. `Save&Start` persists the form and starts the task; if no developer is assigned, the app picks a free agent by task prefix → role and matching repository name/tag.
- Cursor Cloud Agent launch via `POST /v1/agents` — supports **multiple repositories per task** (one task can touch a frontend repo and a backend repo together).
- Run status refresh via `GET /v1/agents/{agentId}/runs/{runId}`.
- Run cancellation via `POST /v1/agents/{agentId}/runs/{runId}/cancel`.
- **GitHub & Repositories window** opened from a single top-bar button (`GitHub & Repos…`). The top bar stays compact (Cursor API key, base URL, model, repo verification, GitHub status).
- **Repository registry**, managed inside that window. Each repository has URL, **Name** (this is the repository tag used by tasks/agents), and **Default branch** (`main` by default). Right after a successful GitHub sign-in the app auto-imports every repository the OAuth token can see (deduped against the existing list). There is also an explicit `Import my repositories now` button to refresh later.
- When creating a task you pick one or more repository names/tags instead of typing a URL. Each selected name/tag is matched against the registry, and every matching repository URL is sent to Cursor in a single `repos` array.
- Branch selection is optional. Enable **Specify branch** to activate the branch dropdown; the app fetches live branches from the repository matching the task's selected repository tag (requires GitHub sign-in). If unchecked, the repository default branch is used (`main` unless changed).
- Local state stored in `~/.ai-team-console-java/state.json`. Tasks track **Started** (most recent transition into RUNNING) and **Ended** (most recent transition into DONE), plus the selected repository names/tags, shown as columns in the Tasks table.
- A task in `DONE` (or `FAILED`/`CANCELLED`/`WAITING_REVIEW`) can be loaded back into the form via the **Edit** button in the Actions column, reassigned to another agent or have its title/description/repository tag tweaked, saved, and started again. Re-running resets **Started** to the new run and clears **Ended** until the new run finishes.
- Table columns auto-size to the wider of their header and the widest cell value (capped at 520 px), recomputed when rows change. The Actions column is excluded so the **Edit** / **Delete** button labels stay visible.
- Role-specific agent prompt profiles stored in `src/main/resources/agent-prompts/`, adapted from [`msitarzewski/agency-agents`](https://github.com/msitarzewski/agency-agents). The selected desktop agent role is now included as a specialized operating profile in the Cursor prompt.
- Optional **Sign in with GitHub** (OAuth 2.0 device flow): per OS user the app can store an access token in `~/.ai-team-console-java/github-session.json` and use the GitHub REST API to fetch branch lists (`GET /repos/{owner}/{repo}/branches`) and open fallback PRs. This is for convenience in the desktop UI only; it does **not** replace linking GitHub to Cursor for Cloud Agents (`Verify repo access` still reflects Cursor’s `/v1/repositories`).

### GitHub OAuth App (for device flow)

1. GitHub → **Settings** → **Developer settings** → **OAuth Apps** → **New OAuth App**.
2. Enable **Device flow** on the app (GitHub documents this under the OAuth app’s settings).
3. **Client ID** is public and safe to paste into the app (or set env `GITHUB_OAUTH_CLIENT_ID`). Do not distribute the **client secret** with this app — device flow does not require embedding the secret in the desktop client.
4. Requested scopes: **`repo`** and **`read:user`** (the app sends `repo read:user` when starting device authorization).

When you start sign-in the app shows a dialog with the GitHub user code; the code is rendered in a read-only text field with a **Copy code** button (clipboard) so you can paste it into the GitHub verification page. After sign-in the app:

- auto-imports your repositories into the Repositories table (the repository name becomes its task/agent tag);
- uses the token to fetch branch names for the task form's **Branch name** dropdown;
- opens a fallback PR when the agent's `Auto-create PR` is enabled and Cursor finishes without one.

Use **Sign out GitHub** to delete the stored token file.

### If no pull request appears after a successful run

1. **Cursor** — Cloud Agents must be able to create a PR on GitHub (GitHub linked in Cursor, repo access for the Cursor GitHub App). If the run finishes without `pullRequestUrl`, Cursor did not expose a PR link in the API response.
2. **This app (fallback)** — If the agent has **Auto-create PR** enabled and you are **signed in with GitHub** in Settings, the app calls `POST /repos/{owner}/{repo}/pulls` using your OAuth token. That only works when the **head branch already exists on GitHub** (same name the agent pushed). Open the run’s **Run log**: lines starting with `PR automation:` explain skips (not signed in, wrong repo URL shape, disabled auto PR) or GitHub API errors (e.g. branch not found — often a mismatch if Cursor omitted `branchName` on create and used another branch name; the app now prefers `branchName` from the run status JSON when present).
3. **Repository URL** on the task or agent must be `https://github.com/owner/repo` (SSH URLs are normalized when possible).

## Verifying the role profile reached the Cloud Agent

Cursor Cloud is a black-box runtime; we cannot inspect its system prompt. The app provides two evidence-based checks.

1. **Self-report token in the run summary.** The prompt sent to `POST /v1/agents` always includes a `Verification protocol` block asking the agent to echo a fixed line, e.g. `ROLE-VERIFY: Backend Engineer | TASK: BE-TASK01`. When the run’s `summary` returned by `GET /v1/agents/{id}/runs/{id}` contains that line, the app appends one log entry per run (deduplicated across polls):

   ```
   Role profile verified by Cloud Agent: ROLE-VERIFY: Backend Engineer | TASK: BE-TASK01
   ```

   Open the run’s **Run log (selected run)** in the UI to confirm. **No verification line** in the summary means the model did not honor the operating profile block — try a different `model` setting, or compare A/B with another role.

2. **Opt-in full prompt logging.** By default only `promptChars=...` is recorded. To capture the exact prompt text for debugging, set:

   ```bash
   export AI_TEAM_LOG_PROMPT_TEXT=1   # accepts 1 | true | yes | on
   mvn javafx:run
   ```

   The full prompt (truncated at 50k chars) is written to `~/.ai-team-console-java/logs/app-0.log` next to the existing `POST /v1/agents ...` summary line. Unset the variable after debugging — the file may contain task descriptions and role-profile text.

## Logging

On startup the app writes **java.util.logging** output to:

- **Files:** `~/.ai-team-console-java/logs/app-0.log` (rotates: up to 5 files × 5MB).
- **Console:** INFO and above.

Logged for debugging GitHub/Cursor issues: normalized repo URL, `startingRef` attempts, HTTP method/path, status codes, **truncated** error bodies, and Cursor run activity snapshots during status refresh (`status`, `currentStep`, `message`, `summary`, `pullRequestUrl`, and similar non-secret fields when returned by the API). **Not logged by default:** API keys, full prompt text (only character count for `POST /v1/agents`; full text is opt-in via `AI_TEAM_LOG_PROMPT_TEXT` as described above).

## Cursor API Notes

The provider uses HTTP Basic auth with the Cursor API key as username and an empty password. Cursor run status is read from runs, not from the durable agent object.

For a step-by-step Cursor Dashboard and GitHub setup checklist, see [`CURSOR_CLOUD_AGENTS_SETUP.md`](CURSOR_CLOUD_AGENTS_SETUP.md).

The app intentionally keeps the provider behind an `AgentProvider` interface so other services, such as Devin, can be added later without rewriting the UI or domain model.
