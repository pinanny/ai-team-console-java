package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GitHub pull requests via REST API (create PRs and query merge state; user OAuth or GitHub App user token).
 */
public final class GitHubPullsClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI githubApiBase;

    public GitHubPullsClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper(), URI.create("https://api.github.com"));
    }

    GitHubPullsClient(HttpClient httpClient, ObjectMapper mapper, URI githubApiBase) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        String s = githubApiBase.toString();
        this.githubApiBase = s.endsWith("/") ? URI.create(s.substring(0, s.length() - 1)) : githubApiBase;
    }

    GitHubPullsClient(HttpClient httpClient, ObjectMapper mapper) {
        this(httpClient, mapper, URI.create("https://api.github.com"));
    }

    /** @param merged GitHub {@code merged} field; {@code baseRef} short name from {@code base.ref} */
    public record PullRequestMergeState(boolean merged, String baseRef) {
    }

    private URI repoMetadataUri(String owner, String repo) {
        return githubApiBase.resolve("repos/" + owner + "/" + repo);
    }

    private URI pullsCollectionUri(String owner, String repo) {
        return githubApiBase.resolve("repos/" + owner + "/" + repo + "/pulls");
    }

    private URI pullUri(String owner, String repo, int pullNumber) {
        return githubApiBase.resolve("repos/" + owner + "/" + repo + "/pulls/" + pullNumber);
    }

    public String fetchDefaultBranch(String accessToken, String owner, String repo) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(repoMetadataUri(owner, repo))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub GET /repos/%s/%s HTTP %d: %s".formatted(owner, repo, response.statusCode(), response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        return root.path("default_branch").asText("");
    }

    /**
     * @param baseBranch short branch name (e.g. {@code main}); if blank, uses repository default branch
     * @return pull request {@code html_url}
     */
    public String createPull(
            String accessToken,
            String owner,
            String repo,
            String title,
            String body,
            String headBranch,
            String baseBranch
    ) throws IOException, InterruptedException {
        String base = baseBranch == null || baseBranch.isBlank()
                ? fetchDefaultBranch(accessToken, owner, repo)
                : baseBranch.strip();
        if (base.isBlank()) {
            throw new IOException("Could not determine base branch for pull request.");
        }
        Map<String, Object> jsonBody = new LinkedHashMap<>();
        jsonBody.put("title", title);
        jsonBody.put("head", headBranch.strip());
        jsonBody.put("base", base);
        if (body != null && !body.isBlank()) {
            jsonBody.put("body", body);
        }
        String payload = mapper.writeValueAsString(jsonBody);
        HttpRequest request = HttpRequest.newBuilder(pullsCollectionUri(owner, repo))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub POST /repos/%s/%s/pulls HTTP %d: %s".formatted(owner, repo, response.statusCode(), response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        String htmlUrl = root.path("html_url").asText("");
        if (htmlUrl.isBlank()) {
            throw new IOException("GitHub pull response had no html_url: " + response.body());
        }
        return htmlUrl;
    }

    /**
     * Tries several {@code head} values (API-reported branch vs locally expected), optional {@code owner:branch} form,
     * then repeats with backoff while GitHub still reports the head ref missing — common when a run is FINISHED before
     * the branch exists on the remote.
     */
    public String createPullWithHeadCandidatesAndBackoff(
            String accessToken,
            String owner,
            String repo,
            String title,
            String body,
            List<String> headCandidates,
            String baseBranch,
            String optionalGithubLoginForHeadPrefix,
            int maxGlobalCycles
    ) throws IOException, InterruptedException {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String h : headCandidates) {
            if (h != null) {
                String t = h.strip();
                if (!t.isEmpty()) {
                    ordered.add(t);
                }
            }
        }
        String login = optionalGithubLoginForHeadPrefix == null ? "" : optionalGithubLoginForHeadPrefix.strip();
        if (!login.isEmpty()) {
            List<String> snapshot = new ArrayList<>(ordered);
            for (String h : snapshot) {
                if (!h.contains(":")) {
                    ordered.add(login + ":" + h);
                }
            }
        }
        if (ordered.isEmpty()) {
            throw new IOException("No head branch candidates for pull request.");
        }
        List<String> heads = new ArrayList<>(ordered);
        IOException last = null;
        for (int cycle = 1; cycle <= maxGlobalCycles; cycle++) {
            for (String head : heads) {
                try {
                    return createPull(accessToken, owner, repo, title, body, head, baseBranch);
                } catch (IOException e) {
                    last = e;
                    if (isPermanentPullCreateFailure(e)) {
                        throw e;
                    }
                    if (isTryNextHeadCandidateFailure(e)) {
                        continue;
                    }
                    break;
                }
            }
            if (cycle < maxGlobalCycles && last != null && isHeadMissingOrTransient(last)) {
                long pauseMs = Math.min(60_000L, 6000L * (long) cycle * cycle);
                Thread.sleep(pauseMs);
                continue;
            }
            break;
        }
        throw last != null ? last : new IOException("GitHub pull request creation failed.");
    }

    private static String messageLower(IOException e) {
        String m = e.getMessage();
        return m == null ? "" : m.toLowerCase();
    }

    private static boolean isPermanentPullCreateFailure(IOException e) {
        String m = messageLower(e);
        if (m.contains("http 401")) {
            return true;
        }
        if (m.contains("http 403") && m.contains("scope")) {
            return true;
        }
        if (m.contains("http 403") && m.contains("not accessible by integration")) {
            return true;
        }
        if (m.contains("no commits between")) {
            return true;
        }
        if (m.contains("a pull request already exists")) {
            return true;
        }
        if (m.contains("already exists") && m.contains("pull")) {
            return true;
        }
        return false;
    }

    private static boolean isTryNextHeadCandidateFailure(IOException e) {
        String m = messageLower(e);
        if (!m.contains("http 422")) {
            return false;
        }
        return m.contains("head")
                && (m.contains("unknown")
                || m.contains("not a valid")
                || m.contains("was not found")
                || m.contains("does not exist")
                || m.contains("invalid"));
    }

    private static boolean isHeadMissingOrTransient(IOException e) {
        if (isPermanentPullCreateFailure(e)) {
            return false;
        }
        String m = messageLower(e);
        if (m.contains("http 404")) {
            return true;
        }
        if (m.contains("http 422") && isTryNextHeadCandidateFailure(e)) {
            return true;
        }
        if (m.contains("http 429")) {
            return true;
        }
        if (m.contains("http 50")) {
            return true;
        }
        if (m.contains("connection reset") || m.contains("broken pipe")) {
            return true;
        }
        return m.contains("timed out") || m.contains("timeout");
    }

    /**
     * Reads {@code merged} and {@code base.ref} from {@code GET /repos/{owner}/{repo}/pulls/{pullNumber}}.
     *
     * @return empty when GitHub responds {@code 404}
     */
    public Optional<PullRequestMergeState> fetchPullRequestMergeState(
            String accessToken,
            String owner,
            String repo,
            int pullNumber
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(pullUri(owner, repo, pullNumber))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub GET /repos/%s/%s/pulls/%d HTTP %d: %s".formatted(
                    owner, repo, pullNumber, response.statusCode(), response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        boolean merged = root.path("merged").asBoolean(false);
        String baseRef = root.path("base").path("ref").asText("");
        return Optional.of(new PullRequestMergeState(merged, baseRef));
    }

    /**
     * {@code true} if GitHub reports the pull request as merged ({@code merged} field).
     */
    public boolean isPullRequestMerged(String accessToken, String owner, String repo, int pullNumber)
            throws IOException, InterruptedException {
        return fetchPullRequestMergeState(accessToken, owner, repo, pullNumber)
                .map(PullRequestMergeState::merged)
                .orElse(false);
    }
}
