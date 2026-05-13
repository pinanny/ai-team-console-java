package com.example.aiteamconsole;

import java.time.Instant;

/**
 * GitHub OAuth access token and resolved login. Stored locally per OS user — do not commit or share files.
 */
public record GitHubSession(
        String accessToken,
        String login,
        String scope,
        Instant savedAt
) {
}
