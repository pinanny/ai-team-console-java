package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Lists repositories using a user's OAuth access token (not Cursor). */
public final class GitHubReposClient {
    private static final String LIST_REPOS = "https://api.github.com/user/repos?per_page=100&sort=updated";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public GitHubReposClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    GitHubReposClient(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public List<String> listHttpsRepoUrls(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LIST_REPOS))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub /user/repos HTTP %d: %s".formatted(response.statusCode(), response.body()));
        }
        JsonNode arr = mapper.readTree(response.body());
        List<String> urls = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String html = item.path("html_url").asText("");
                if (!html.isBlank()) {
                    urls.add(GitHubRepoUrls.normalizeHttpsRepositoryUrl(html));
                }
            }
        }
        return urls;
    }

    /**
     * Returns branch names for {@code https://github.com/<owner>/<repo>} using the user's OAuth token.
     * Up to 100 branches per page; we read the first page (sorted by GitHub default).
     */
    public List<String> listBranches(String accessToken, String repositoryUrl) throws IOException, InterruptedException {
        GitHubRepoUrls.Slug slug = GitHubRepoUrls.parseSlug(GitHubRepoUrls.normalizeHttpsRepositoryUrl(repositoryUrl))
                .orElseThrow(() -> new IOException("Not a GitHub HTTPS repo URL: " + repositoryUrl));
        URI uri = URI.create("https://api.github.com/repos/%s/%s/branches?per_page=100".formatted(slug.owner(), slug.repo()));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub /repos/%s/%s/branches HTTP %d: %s".formatted(
                    slug.owner(), slug.repo(), response.statusCode(), response.body()));
        }
        JsonNode arr = mapper.readTree(response.body());
        List<String> names = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String name = item.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
