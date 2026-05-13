package com.example.aiteamconsole;

import java.util.Locale;
import java.util.Optional;

/**
 * Normalizes GitHub repository URLs to the shape Cursor API examples use.
 */
public final class GitHubRepoUrls {
    private GitHubRepoUrls() {
    }

    public static String normalizeHttpsRepositoryUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.strip();
        if (trimmed.startsWith("git@github.com:")) {
            String path = trimmed.substring("git@github.com:".length());
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - ".git".length());
            }
            return "https://github.com/" + path;
        }
        if (trimmed.startsWith("ssh://git@github.com/")) {
            String path = trimmed.substring("ssh://git@github.com/".length());
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - ".git".length());
            }
            return "https://github.com/" + path;
        }
        trimmed = trimmed.replaceFirst("(?i)^http://", "https://");
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("https://www.github.com/")) {
            trimmed = "https://github.com/" + trimmed.substring("https://www.github.com/".length());
        }
        return trimmed;
    }

    /**
     * Owner and repository name for {@code https://github.com/owner/repo} (normalized).
     */
    public record Slug(String owner, String repo) {
    }

    /**
     * Parses {@link #normalizeHttpsRepositoryUrl(String)} output when it points at a single GitHub repo root.
     */
    public static Optional<Slug> parseSlug(String url) {
        String n = normalizeHttpsRepositoryUrl(url);
        if (n.isBlank() || !n.toLowerCase(Locale.ROOT).startsWith("https://github.com/")) {
            return Optional.empty();
        }
        String path = n.substring("https://github.com/".length());
        int slash = path.indexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            return Optional.empty();
        }
        String owner = path.substring(0, slash);
        String rest = path.substring(slash + 1);
        int next = rest.indexOf('/');
        String repo = next < 0 ? rest : rest.substring(0, next);
        if (owner.isBlank() || repo.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Slug(owner, repo));
    }

    /**
     * Parses a pull request {@code html_url} from the GitHub REST API, e.g.
     * {@code https://github.com/acme/widgets/pull/42} (optional trailing path segments ignored).
     */
    public record PullRequestRef(String owner, String repo, int number) {
    }

    public static Optional<PullRequestRef> parsePullRequestHtmlUrl(String htmlUrl) {
        if (htmlUrl == null || htmlUrl.isBlank()) {
            return Optional.empty();
        }
        String u = htmlUrl.strip();
        int idx = u.toLowerCase(Locale.ROOT).indexOf("github.com/");
        if (idx < 0) {
            return Optional.empty();
        }
        String path = u.substring(idx + "github.com/".length());
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        if (parts.length < 4) {
            return Optional.empty();
        }
        String owner = parts[0];
        String repo = parts[1];
        if (!"pull".equalsIgnoreCase(parts[2])) {
            return Optional.empty();
        }
        String numPart = parts[3];
        int dash = numPart.indexOf('?');
        if (dash >= 0) {
            numPart = numPart.substring(0, dash);
        }
        int number;
        try {
            number = Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (owner.isBlank() || repo.isBlank() || number <= 0) {
            return Optional.empty();
        }
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - ".git".length());
        }
        return Optional.of(new PullRequestRef(owner, repo, number));
    }
}
