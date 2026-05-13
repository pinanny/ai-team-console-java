package com.example.aiteamconsole;

/** Public OAuth App client id (no secret on device flow). */
public record GitHubOAuthAppSettings(String clientId) {
    public static GitHubOAuthAppSettings empty() {
        return new GitHubOAuthAppSettings("");
    }

    public GitHubOAuthAppSettings normalized() {
        return new GitHubOAuthAppSettings(clientId == null ? "" : clientId.strip());
    }
}
