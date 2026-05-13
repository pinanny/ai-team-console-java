package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubRepoUrlsTest {

    @Test
    void normalizesHttpsRepoAndStripsGitSuffix() {
        assertEquals(
                "https://github.com/pinanny/test_ai_be_project",
                GitHubRepoUrls.normalizeHttpsRepositoryUrl("https://github.com/pinanny/test_ai_be_project.git/")
        );
    }

    @Test
    void convertsSshRemoteToHttps() {
        assertEquals(
                "https://github.com/org/repo",
                GitHubRepoUrls.normalizeHttpsRepositoryUrl("git@github.com:org/repo.git")
        );
    }

    @Test
    void parsesPullRequestHtmlUrl() {
        assertEquals(
                new GitHubRepoUrls.PullRequestRef("acme", "widgets", 42),
                GitHubRepoUrls.parsePullRequestHtmlUrl("https://github.com/acme/widgets/pull/42").orElseThrow()
        );
        assertEquals(
                new GitHubRepoUrls.PullRequestRef("acme", "widgets", 7),
                GitHubRepoUrls.parsePullRequestHtmlUrl("https://github.com/acme/widgets/pull/7/files").orElseThrow()
        );
        assertEquals(
                new GitHubRepoUrls.PullRequestRef("o", "r", 1),
                GitHubRepoUrls.parsePullRequestHtmlUrl("https://www.github.com/o/r/pull/1?tab=checks").orElseThrow()
        );
    }

    @Test
    void parsesOwnerAndRepoFromNormalizedUrl() {
        assertEquals(
                new GitHubRepoUrls.Slug("acme", "widgets"),
                GitHubRepoUrls.parseSlug("https://github.com/acme/widgets.git").orElseThrow()
        );
    }
}