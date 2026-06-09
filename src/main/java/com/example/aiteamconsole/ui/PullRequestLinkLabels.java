package com.example.aiteamconsole.ui;

import com.example.aiteamconsole.GitHubRepoUrls;

import java.util.Optional;

/** Compact PR link text for table cells (full URL stays in tooltip). */
public final class PullRequestLinkLabels {

    private PullRequestLinkLabels() {
    }

    /**
     * @return e.g. {@code PR-my-repo} from {@code https://github.com/acme/my-repo/pull/42}
     */
    public static String compactLabel(String pullRequestUrl) {
        if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
            return "";
        }
        return GitHubRepoUrls.parsePullRequestHtmlUrl(pullRequestUrl.strip())
                .map(ref -> "PR-" + ref.repo())
                .orElseGet(() -> GitHubRepoUrls.parseSlug(pullRequestUrl)
                        .map(slug -> "PR-" + slug.repo())
                        .orElse("PR"));
    }

    public static Optional<String> tooltipForUrl(String pullRequestUrl) {
        if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
            return Optional.empty();
        }
        String url = pullRequestUrl.strip();
        return GitHubRepoUrls.parsePullRequestHtmlUrl(url)
                .map(ref -> ref.owner() + "/" + ref.repo() + " #" + ref.number() + "\n" + url)
                .or(() -> Optional.of(url));
    }
}
