# AI Team Console

A desktop application that gives you a team of AI engineers — each with a specific role (Backend, Frontend, QA, Product Analyst, and more) — that can read your GitHub repositories, write code, run tests, and open pull requests, all from a single control panel.

Connect your Cursor Cloud account or run models locally with Ollama. No CLI required.

---

## What You Can Do

- **Create AI agents** with specific roles (Backend Engineer, Frontend Engineer, QA, Product Analyst, DevOps, Code Reviewer)
- **Create tasks** in plain language — the agent reads your codebase, implements the change, commits, and opens a PR
- **Product Analyst** refines vague requests into a structured spec before any code is written
- **Analyze domain** — scan your repository once to build project memory; all subsequent agent runs skip full re-analysis and use the cached context (saves 80–90% of tokens)
- **Track runs** with duration and token usage per task
- **Convert open questions** from analyst specs directly into backlog tickets
- **Use Ollama** to run local open-source models (llama3.2, qwen2.5-coder, mistral, and more) with RAM compatibility checks built in

---

## Requirements

| Requirement | Version |
|---|---|
| Java (JDK) | 23 or newer |
| Maven | 3.9 or newer |
| Cursor API key | (for Cursor Cloud provider) |
| Git | on PATH (for Ollama local provider only) |
| Ollama | running locally (for Ollama provider only) |

You need **at least one** of the two providers: Cursor Cloud or Ollama.

---

## First Launch

### 1. Clone and run

```bash
git clone https://github.com/<your-org>/ai-team-console-java.git
cd ai-team-console-java
mvn javafx:run
```

Or, if you have a Cursor API key handy:

```bash
export CURSOR_API_KEY="cursor_..."
mvn javafx:run
```

The app opens a desktop window. No browser required.

### 2. Enter your Cursor API key

Click **Settings → Cursor** in the top bar. Paste your API key and click **Save API key**. The key is stored in `~/.ai-team-console-java/cursor-api.json` and loaded automatically on next launch. Click **Forget** to remove it.

> **Where to get a Cursor API key:** Log in at [cursor.com](https://cursor.com), go to **Settings → API Keys**, and create a key.

### 3. Connect GitHub

Click **GitHub & Repos…** in the top bar. Click **Sign in with GitHub** and follow the device flow: a code appears in the dialog — copy it, open the GitHub URL shown, and paste the code. After signing in, the app automatically imports your repositories.

GitHub is used for:
- fetching live branch lists when creating a task
- automatically opening pull requests after a successful run

---

## Core Concepts

### Agents

An agent is an AI worker with a fixed role. It does not hold any conversation history — each task run starts fresh. You can have multiple agents with the same role running in parallel.

**Roles and what they do:**

| Role | What it does |
|---|---|
| **Product Analyst** | Converts vague tasks into a structured spec (problem statement, acceptance criteria, scope, open questions). Runs automatically before any implementation agent. |
| **Backend Engineer** | Writes server-side code, runs tests, opens a PR |
| **Frontend Engineer** | Writes UI code, runs tests, opens a PR |
| **QA Engineer** | Writes or reviews tests |
| **Code Reviewer** | Reviews existing PRs or branches |
| **DevOps Engineer** | Infrastructure, CI/CD, configuration |
| **Project Memory Analyst** | One-time role: scans the repository and saves a structured project snapshot. You do not keep this agent long-term. |

### Tasks

A task is a piece of work described in natural language. Example: _"Add pagination to the /users endpoint"_. You assign it to a repository and optionally to a specific agent. When you click **Run**, the app dispatches the task to the agent.

Task statuses:

| Status | Meaning |
|---|---|
| DRAFT | Created, not yet sent to an agent |
| SPEC_REVIEW | Product Analyst finished; waiting for your review |
| OPEN | Spec accepted; ready for implementation |
| RUNNING | Agent is working |
| WAITING_REVIEW | Run finished; PR is open on GitHub |
| DONE | Completed |
| FAILED | Run ended with an error |

### Runs

Each time a task is sent to an agent, a run is created. You can see all runs in the **Runs** tab with duration and token usage. Double-click a run to open the full log.

---

## Step-by-Step: Running Your First Task

### Step 1 — Add a repository

Click **GitHub & Repos…** → **Add repository**. Enter the HTTPS URL (e.g. `https://github.com/your-org/your-repo`) and a display name. Or sign in with GitHub to auto-import all your repositories.

### Step 2 — Create an agent

Go to the **Agents** tab. Click **Create agent…**.

- **Name:** anything (e.g. _"Alex Backend"_)
- **Role:** Backend Engineer
- **Provider:** Cursor Cloud (or Ollama if you have it set up)
- Click **Create**

Repeat to create agents for other roles you need.

### Step 3 — Create a task

Go to the **Tasks** tab. Click **Create task**.

- **Prefix:** use `BE-TASK` for backend tasks, `FE-TASK` for frontend, `PA-TASK` for spec-only, etc.
- **Title:** short description of what needs to be done
- **Description:** more detail, acceptance criteria, anything the agent should know
- **Repositories:** select the repository this task touches
- **Agent:** leave blank to auto-assign, or pick a specific agent

Click **Save & Start** to save and immediately queue the task.

### Step 4 — Review the Product Analyst output (if you have a PA agent)

If you have a Product Analyst agent, it runs first on every new task. The task moves to **SPEC_REVIEW** when it finishes. Open the task, read the output under **Product Analyst output**, edit the description if needed, then click **Accept spec** to move it to OPEN for implementation.

> **Tip:** Click **Create tickets** in the Actions column to automatically turn the analyst's open questions into separate DRAFT tasks.

### Step 5 — Watch the run

The task moves to **RUNNING**. Switch to the **Runs** tab to see live status. Double-click the run to see the agent's log.

When the run finishes, if Auto-create PR was enabled, the PR URL appears in the Runs table. Click it to open the pull request on GitHub.

---

## Project Memory (Save Tokens, Improve Quality)

By default, each agent run starts from scratch — the agent reads and re-analyzes the entire codebase every time. This is expensive (hundreds of thousands of tokens per task) and slow.

**Analyze domain** solves this: it scans the repository once, saves a structured summary, and injects it automatically into every subsequent agent prompt.

### How to set it up

1. Go to the **Tasks** tab
2. Click **Analyze domain** in the toolbar
3. Select the repository (if you have more than one)
4. A `MEM-01 Analyze domain: repo-name` task appears and runs automatically
5. When it finishes, the snapshot is saved to `~/.ai-team-console-java/project-memory/`

From that point on, every agent run for that repository will include the cached context. No action required.

### What each agent receives

Different roles receive different amounts of context to avoid wasting tokens:

| Role | Context tier | Approximate size |
|---|---|---|
| Code Reviewer, QA Engineer | Brief summary | ~200–400 tokens |
| Backend, Frontend, Product Analyst, DevOps | Core working memory | ~1 000–1 500 tokens |
| Implementation Planner | Full analysis | up to ~3 000 tokens |

### Auto-refresh

After every 10 completed tasks for a repository, the app automatically triggers a new domain analysis to keep the snapshot current as your project evolves. You will see a notification and a new `MEM-` task in the list.

---

## Using Ollama (Local Models)

Ollama lets you run open-source models on your own machine — no Cursor account needed.

### 1. Install Ollama

Download from [ollama.com](https://ollama.com) and start it:

```bash
ollama serve
```

### 2. Configure the app

Click **Settings → Ollama**. The default base URL is `http://127.0.0.1:11434`. Click **↺ Refresh list** to load installed models.

### 3. Create an Ollama agent

Go to **Agents → Create agent…**. Set **Provider** to **Ollama (local)**. A model picker appears showing:

- All models currently installed on your machine
- Popular presets (llama3.2, qwen2.5-coder, mistral, gemma2, and others)
- A compatibility indicator next to each model:

| Icon | Meaning |
|---|---|
| ✓ green | Fits comfortably in your RAM |
| ⚠ orange | Tight — may be slow or cause swapping |
| ✗ red | Likely too large for your machine |

The legend and your machine's total RAM are shown to the right of the picker.

If a model is not installed, click **⬇ Install** next to it. The app runs `ollama pull` in the background and shows progress.

> **Recommended starting model for code tasks:** `qwen2.5-coder:7b` (requires ~8 GB RAM) or `qwen2.5-coder:3b` (requires ~4 GB RAM).

---

## Settings Reference

### Cursor (Settings → Cursor)

| Setting | Description |
|---|---|
| **Cursor API key** | Your personal key from cursor.com. Click Save to persist across restarts. |
| **Model ID** | Optional. Leave blank — Cursor uses its account default. Set only if you get an `invalid_model` error (enter the exact model name Cursor requires). |
| **Verify repo access** | Tests whether Cursor can see a specific repository. Rate-limited to about once per minute. |

### Ollama (Settings → Ollama)

| Setting | Description |
|---|---|
| **Ollama base URL** | Default: `http://127.0.0.1:11434`. Change if Ollama runs on a different port or host. |
| **Chat model** | Global default model. Can be overridden per-agent in the Agents form. |
| **Embedding model** | Used for RAG (code retrieval). Default: `nomic-embed-text`. |
| **Enable Qdrant RAG** | Adds semantic code search to Ollama runs. Requires a running Qdrant instance at `http://127.0.0.1:6333`. |

### GitHub (Settings → GitHub)

| Setting | Description |
|---|---|
| **Sign in with GitHub** | OAuth device flow. Needed for branch lists and fallback PR creation. |
| **Import repositories** | Manually re-imports all repositories visible to your GitHub token. |

---

## Runs Tab — Columns Explained

| Column | Description |
|---|---|
| **Started** | When the run was queued |
| **Status** | RUNNING / FINISHED / ERROR / CANCELLED |
| **Task** | Which task this run belongs to |
| **Agent** | Which agent profile ran it |
| **PR** | Link to the pull request (if opened) |
| **Duration** | How long the run took (e.g. `4m 32s`) |
| **Tokens** | Estimated (Cursor: `~12.4k`) or real (Ollama: `8.1k`) token usage. Hover for input/output breakdown. |

Double-click any run row to open the full log and result summary.

---

## Data & Privacy

All state is stored locally on your machine:

| File | Contents |
|---|---|
| `~/.ai-team-console-java/state.json` | Agents, tasks, run history |
| `~/.ai-team-console-java/cursor-api.json` | Cursor API key (if saved) |
| `~/.ai-team-console-java/github-session.json` | GitHub OAuth token (if signed in) |
| `~/.ai-team-console-java/project-memory/` | Domain analysis snapshots, one file per repository |
| `~/.ai-team-console-java/logs/` | App logs (up to 5 × 5 MB, rotated) |
| `~/.ai-team-console-java/ollama-settings.json` | Ollama configuration |

Logs contain HTTP status codes, truncated error bodies, and run activity. **API keys and full prompt text are never written to logs by default.**

To enable full prompt logging for debugging:

```bash
export AI_TEAM_LOG_PROMPT_TEXT=1
mvn javafx:run
```

---

## Troubleshooting

### "Model 'composer-2' is not available or invalid"

Cursor changed their model API. Go to **Settings → Cursor → Model ID** and enter the current model name (e.g. `claude-sonnet-4-5`). Leave blank to let Cursor use its default.

### "Failed to verify existence of branch"

This usually means Cursor's GitHub App cannot access the repository — not that the branch is missing. See [`CURSOR_CLOUD_AGENTS_SETUP.md`](CURSOR_CLOUD_AGENTS_SETUP.md) for a step-by-step checklist.

### Task stays in DRAFT after creation

If you have a Product Analyst agent and no PA agent is free, the task stays in DRAFT until one becomes available. Either wait for a running PA run to finish, or create another PA agent.

### Ollama run fails immediately

- Make sure `ollama serve` is running
- Check that the model name in the agent form matches exactly what `ollama list` shows (e.g. `llama3.2:3b`, not `llama3.2`)
- Check Ollama settings: base URL should be `http://127.0.0.1:11434`

### No pull request appears after a successful run

1. Make sure **Auto-create PR** is enabled on the agent profile
2. Make sure you are **signed in with GitHub** in Settings
3. Open the run log — lines starting with `PR automation:` explain what happened
4. The agent's head branch must exist on GitHub (it was pushed successfully)

### "Nothing to compile" but app crashes

Run `mvn clean javafx:run` instead of `mvn javafx:run`. Maven sometimes skips recompilation when file timestamps look current.

---

## UI Tips

- **Double-click a run row** → opens the full run log and result summary
- **Accept spec** button → appears on tasks in SPEC_REVIEW; moves the task to OPEN
- **Create tickets** button → appears next to Accept spec; converts PA open questions into DRAFT tasks
- **Analyze domain** button → in the Tasks toolbar; scans the repository and saves project memory
- **Edit** button on a task → lets you change title, description, repository, or reassign the agent, even after the task is DONE
- **↺ Refresh list** in the Ollama model picker → loads currently installed models from your Ollama instance

---

## Architecture Overview (for Developers)

The application is built with **JavaFX 23** and **Java 23**, packaged with Maven. It uses no Spring or DI framework — plain Java with Jackson for JSON serialization.

Key packages:

| Package | Role |
|---|---|
| `com.example.aiteamconsole` | Domain model, providers, state store |
| `com.example.aiteamconsole.ui` | JavaFX views and view-models |
| `com.example.aiteamconsole.ui.agents` | Agent creation form |
| `com.example.aiteamconsole.ui.tasks` | Task list, task form, open questions dialog |
| `com.example.aiteamconsole.ui.runs` | Run list and log viewer |
| `com.example.aiteamconsole.ui.settings` | Settings windows (Cursor, Ollama, GitHub) |

Provider abstraction (`AgentProvider` interface) makes it straightforward to add new AI services without changing the UI or domain model.

For a step-by-step Cursor Dashboard and GitHub setup checklist, see [`CURSOR_CLOUD_AGENTS_SETUP.md`](CURSOR_CLOUD_AGENTS_SETUP.md).
