package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class ClaudeApiHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public ClaudeApiHttpClient(String baseUrl) {
        this(HttpClient.newHttpClient(), new ObjectMapper(), baseUrl);
    }

    ClaudeApiHttpClient(HttpClient httpClient, ObjectMapper mapper, String baseUrl) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.stripTrailing().replaceAll("/$", "");
    }

    /**
     * Calls POST /v1/messages (non-streaming).
     *
     * @param apiKey       Anthropic API key (x-api-key header)
     * @param model        e.g. "claude-sonnet-4-6"
     * @param systemPrompt role prompt injected as the system message
     * @param userMessage  the coding task description
     * @param maxTokens    maximum output tokens (default 8192)
     * @return the text content of the first content block in the response
     */
    public String complete(
            String apiKey,
            String model,
            String systemPrompt,
            String userMessage,
            int maxTokens
    ) throws IOException, InterruptedException, AgentProviderException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", systemPrompt == null ? "" : systemPrompt);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage == null ? "" : userMessage);

        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .header("x-api-key", apiKey == null ? "" : apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        String respBody = resp.body() == null ? "" : resp.body();
        if (code == 401) {
            throw new AgentProviderException("Invalid Claude API key");
        }
        if (code == 429) {
            throw new AgentProviderException("Claude API rate limit exceeded");
        }
        if (code < 200 || code >= 300) {
            throw new AgentProviderException("Claude API error: " + code + " " + respBody);
        }
        JsonNode root = mapper.readTree(respBody);
        JsonNode content0 = root.path("content").path(0);
        if (content0.isMissingNode()) {
            return "";
        }
        return content0.path("text").asText("");
    }
}
