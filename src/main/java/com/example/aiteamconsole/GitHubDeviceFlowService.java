package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * GitHub OAuth 2.0 device authorization grant. Requires an OAuth App with "Device flow" enabled.
 */
public final class GitHubDeviceFlowService {
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_API = "https://api.github.com/user";
    private static final String DEFAULT_SCOPE = "repo read:user";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public GitHubDeviceFlowService() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    GitHubDeviceFlowService(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public DeviceAuthorizationStart requestDeviceCode(String clientId) throws IOException, InterruptedException {
        String body = form("client_id", clientId, "scope", DEFAULT_SCOPE);
        HttpRequest request = HttpRequest.newBuilder(URI.create(DEVICE_CODE_URL))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub device code HTTP %d: %s".formatted(response.statusCode(), response.body()));
        }
        JsonNode n = mapper.readTree(response.body());
        return new DeviceAuthorizationStart(
                n.path("device_code").asText(),
                n.path("user_code").asText(),
                n.path("verification_uri").asText("https://github.com/login/device"),
                Math.max(5, n.path("interval").asInt(5)),
                n.path("expires_in").asInt(900)
        );
    }

    /**
     * Polls until success, failure, or timeout. Call from a background thread.
     */
    public GitHubSession pollUntilAuthorized(String clientId, DeviceAuthorizationStart device) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + (long) device.expiresInSeconds() * 1_000_000_000L;
        int intervalSeconds = device.intervalSeconds();
        while (System.nanoTime() < deadlineNanos) {
            Thread.sleep(Math.max(1, intervalSeconds) * 1000L);
            String form = form(
                    "client_id", clientId,
                    "device_code", device.deviceCode(),
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"
            );
            HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(ACCESS_TOKEN_URL))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() != 200) {
                throw new IOException("GitHub token HTTP %d: %s".formatted(tokenResponse.statusCode(), tokenResponse.body()));
            }
            JsonNode n = mapper.readTree(tokenResponse.body());
            Optional<String> tokenOpt = textIfPresent(n, "access_token");
            if (tokenOpt.isPresent()) {
                String token = tokenOpt.get();
                String scopeFromBody = n.path("scope").asText("");
                UserProbe probe = fetchUser(token);
                String scope = scopeFromBody.isBlank() ? probe.scopeHeader : scopeFromBody;
                if (scope == null) {
                    scope = "";
                }
                return new GitHubSession(token, probe.login, scope, Instant.now());
            }
            String error = n.path("error").asText("");
            if ("authorization_pending".equals(error) || error.isBlank()) {
                // keep polling
            } else if ("slow_down".equals(error)) {
                intervalSeconds += 5;
            } else if ("expired_token".equals(error)) {
                throw new IOException("GitHub device authorization expired.");
            } else {
                throw new IOException(
                        "GitHub OAuth error: %s — %s".formatted(error, n.path("error_description").asText("")));
            }
        }
        throw new IOException("GitHub device authorization expired before you completed sign-in.");
    }

    private UserProbe fetchUser(String accessToken) throws IOException, InterruptedException {
        HttpRequest userRequest = HttpRequest.newBuilder(URI.create(USER_API))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> userResponse = httpClient.send(userRequest, HttpResponse.BodyHandlers.ofString());
        if (userResponse.statusCode() != 200) {
            throw new IOException("GitHub /user HTTP %d: %s".formatted(userResponse.statusCode(), userResponse.body()));
        }
        JsonNode u = mapper.readTree(userResponse.body());
        String login = u.path("login").asText("unknown");
        String scopeHeader = userResponse.headers().firstValue("X-OAuth-Scopes").orElse("");
        return new UserProbe(login, scopeHeader);
    }

    private record UserProbe(String login, String scopeHeader) {
    }

    private static Optional<String> textIfPresent(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(v.asText());
    }

    private static String form(String... keyValues) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                b.append('&');
            }
            b.append(URLEncoder.encode(keyValues[i], StandardCharsets.UTF_8));
            b.append('=');
            b.append(URLEncoder.encode(keyValues[i + 1], StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    public record DeviceAuthorizationStart(
            String deviceCode,
            String userCode,
            String verificationUri,
            int intervalSeconds,
            int expiresInSeconds
    ) {
    }
}
