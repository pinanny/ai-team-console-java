package com.example.aiteamconsole;

/**
 * Optional launch parameters for {@link AgentProvider#startTask(AgentProfile, AgentTask, AppSettings, TaskLaunchHints)}.
 * Cursor Cloud uses PR focus + head branch for post-merge review runs; other providers may ignore.
 */
public record TaskLaunchHints(String pullRequestReviewFocusUrl, String cursorHeadBranchOverride) {
    public TaskLaunchHints {
        pullRequestReviewFocusUrl = pullRequestReviewFocusUrl == null ? null : pullRequestReviewFocusUrl.strip();
        cursorHeadBranchOverride = cursorHeadBranchOverride == null ? null : cursorHeadBranchOverride.strip();
        if (pullRequestReviewFocusUrl != null && pullRequestReviewFocusUrl.isEmpty()) {
            pullRequestReviewFocusUrl = null;
        }
        if (cursorHeadBranchOverride != null && cursorHeadBranchOverride.isEmpty()) {
            cursorHeadBranchOverride = null;
        }
    }

    public static TaskLaunchHints none() {
        return new TaskLaunchHints(null, null);
    }

    public static TaskLaunchHints postPullRequestCodeReview(String prHtmlUrl, String implementationHeadBranch) {
        return new TaskLaunchHints(prHtmlUrl, implementationHeadBranch);
    }
}
