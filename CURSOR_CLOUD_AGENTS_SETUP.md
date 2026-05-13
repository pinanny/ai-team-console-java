# Cursor Cloud Agents Setup Checklist

This guide is for configuring a Cursor account so a desktop app can create Cursor Cloud Agents through the Cursor API.

Use it when the app can call Cursor but agents fail to start, stay stuck, or Cursor reports errors such as:

```text
Failed to verify existence of branch 'refs/heads/main'
Cursor could not verify that the starting ref exists in this repository
```

In many cases this does **not** mean the branch is missing on GitHub. It usually means Cursor Cloud Agents cannot read the repository yet, or the Cloud Agent environment for the repository has not been configured.

## Accounts That Must Match

Make sure these are all the same logical setup:

- The Cursor account that owns the `CURSOR_API_KEY`.
- The Cursor Dashboard session opened in the browser.
- The GitHub account or organization that owns the target repository.
- The GitHub App installation used by Cursor.

If the API key belongs to one Cursor account but GitHub is connected in another Cursor account, the app may authenticate successfully but Cloud Agents will not see the expected repositories.

## 1. Verify GitHub Is Connected In Cursor

Open:

```text
https://cursor.com/dashboard/integrations
```

Check the **Source Control** / **GitHub** section.

Expected good state:

```text
GitHub
Connected as <github-user> to repositories in organizations: <owner-or-org>
```

For example:

```text
Connected as pinanny to repositories in organizations: pinanny
```

If GitHub is not connected:

1. Click **Connect** for GitHub.
2. Authorize Cursor with the GitHub account that owns or can access the repository.
3. If GitHub asks for repository access, choose either:
   - **All repositories**, or
   - **Only selected repositories** and include the target repo.

## 2. Verify Cursor GitHub App Is Installed

Open GitHub:

```text
https://github.com/settings/installations
```

Expected good state:

- There is an installed GitHub App related to Cursor.
- The page has a **Configure** button for that app.

If there are no installed GitHub Apps, Cursor Cloud Agents will not be able to clone private repositories, even if normal GitHub OAuth works elsewhere.

Click **Configure** and verify repository access:

1. Select the account or organization that owns the repository.
2. Check **Repository access**.
3. Make sure the target repository is included.

For this project, the repo was:

```text
pinanny/test_ai_be_project
```

## 3. Verify The Repository Exists And The Branch Exists

Open the repository directly in GitHub:

```text
https://github.com/<owner>/<repo>/tree/<branch>
```

Example:

```text
https://github.com/pinanny/test_ai_be_project/tree/main
```

Expected good state:

- The page opens normally.
- The branch selector shows the expected branch, such as `main`.

If GitHub opens the branch successfully but Cursor API still says it cannot verify the branch, the branch probably exists and the problem is Cursor's GitHub App access or Cloud Agent environment setup.

## 4. Verify Cursor Can See The Repository

Open:

```text
https://cursor.com/dashboard/cloud-agents
```

Look at the **Environments** section.

Expected good state:

- The target repository appears in the table.
- It is not blocked by GitHub access.

In the desktop app, you can also use:

```text
Verify repo access
```

This calls Cursor:

```text
GET /v1/repositories
```

Expected good state:

- The normalized repo URL appears in Cursor's repository list.

Note: Cursor rate-limits this endpoint, so avoid clicking repeatedly. Wait about a minute between checks if needed.

## 5. Configure The Cloud Agent Environment

On:

```text
https://cursor.com/dashboard/cloud-agents
```

Check the repository row under **Environments**.

Bad state example:

```text
Repo:   pinanny/test_ai_be_project
Scope:  Unconfigured
Status: Inactive
```

This means Cursor can see the repository, but the Cloud Agent environment is not ready yet.

To configure it:

1. Click **New** in the **Environments** section.
2. Cursor opens onboarding:

   ```text
   https://cursor.com/onboard
   ```

3. Click **Select Repository**.
4. Choose the target repository.
5. Confirm the branch, usually `main`.
6. Keep default settings unless you know you need custom setup.
7. Click **Start For Free**.

Cursor should open a setup agent page like:

```text
https://cursor.com/agents/<agent-id>?app=setup
```

Expected good state:

```text
Environment ready
```

The setup agent may also run checks such as:

```text
Analyze codebase product and services
Find setup and dev scripts
```

Wait until the environment says **Environment ready**.

## 6. Choose The Correct Starting Ref

The desktop app sends a `startingRef` to Cursor.

Good examples:

```text
main
refs/heads/main
cursor/be-task01-project-structure
refs/heads/cursor/be-task01-project-structure
```

The app automatically tries short branch names like `main` and also `refs/heads/main`.

Important: choose a branch that actually contains the project you want the agent to work on.

If `main` is empty and the real code is on another branch, use that branch as the starting ref. For example, during setup Cursor reported:

```text
The main branch is essentially empty.
The actual project code is on origin/cursor/be-task01-project-structure.
```

In that case, use:

```text
cursor/be-task01-project-structure
```

as the starting ref for future agent tasks.

## 7. Understand The `branchName` Warning

The desktop app may log:

```text
Cursor API rejected branchName; retried without the field.
The requested branch name remains in the agent prompt.
```

This is not fatal if the task is accepted afterwards.

Expected good log:

```text
Cursor Cloud Agent accepted the task.
```

Meaning:

- Cursor accepted the agent/task.
- The app retried without the unsupported `branchName` API field.
- The desired branch name was still included in the prompt.

## 8. Check The Desktop App Logs

The app writes logs to:

```text
~/.ai-team-console-java/logs/
```

Useful lines:

```text
Repository URL sent to Cursor: https://github.com/<owner>/<repo>
Starting ref sent to Cursor: <branch>
Requested branch name: cursor/<task-key-slug>
Cursor Cloud Agent accepted the task.
```

If the app logs this:

```text
Started provider run <agent-id>/<run-id>
Cursor Cloud Agent accepted the task.
```

then Cursor accepted the request. If nothing else happens, check the Cursor Cloud Agents UI and the environment status.

## 9. Common Problems And Fixes

### Cursor says branch does not exist, but GitHub shows it exists

Most likely causes:

- Cursor GitHub App is not installed.
- Cursor GitHub App is installed but not allowed to access this repository.
- GitHub is connected to a different Cursor account than the one that owns the API key.
- Cloud Agent environment is still unconfigured.

Fix:

1. Check Cursor Dashboard → **Integrations**.
2. Check GitHub → **Installed GitHub Apps**.
3. Check Cursor Dashboard → **Cloud Agents** → **Environments**.
4. Run onboarding until the environment says **Environment ready**.

### The repo appears but environment says `Unconfigured / Inactive`

Fix:

1. Cursor Dashboard → **Cloud Agents**.
2. Click **New** under **Environments**.
3. Select the repository.
4. Click **Start For Free**.
5. Wait for **Environment ready**.

### The agent is accepted but does not seem to work

Check:

- Is the environment ready?
- Is the starting ref pointing to a branch with real code?
- Does the agent appear in the Cursor Agents UI?
- Does the desktop app refresh run status with the correct `agentId` and `runId`?

### GitHub login inside the desktop app works, but Cursor agents still fail

These are separate integrations.

The desktop app's GitHub login lets the desktop app list repositories through GitHub's API.

Cursor Cloud Agents need Cursor's own GitHub connection and GitHub App installation. The desktop app's GitHub token does not replace Cursor's GitHub App access.

## Quick Success Checklist

Before running a task from the desktop app, verify:

- Cursor Dashboard → Integrations shows GitHub connected as the expected user.
- GitHub → Installed GitHub Apps shows Cursor installed/configurable.
- Cursor GitHub App has access to the target repository.
- Cursor Dashboard → Cloud Agents shows the repository in Environments.
- The environment is configured and ready.
- The starting ref points to a branch with the actual project code.
- The desktop app uses the Cursor API key from the same Cursor account.

