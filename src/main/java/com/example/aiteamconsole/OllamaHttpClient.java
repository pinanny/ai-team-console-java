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
import java.util.ArrayList;
import java.util.List;

public final class OllamaHttpClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaHttpClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    OllamaHttpClient(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public String chat(String baseUrl, String model, String system, String user) throws IOException, InterruptedException {
        String url = trimSlash(baseUrl) + "/api/chat";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", system);
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", user);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Ollama /api/chat HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        return root.path("message").path("content").asText("");
    }

    public float[] embed(String baseUrl, String embeddingModel, String text) throws IOException, InterruptedException {
        String url = trimSlash(baseUrl) + "/api/embeddings";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", embeddingModel);
        body.put("prompt", text);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Ollama /api/embeddings HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode emb = root.path("embedding");
        if (!emb.isArray() || emb.isEmpty()) {
            throw new IOException("Ollama embeddings response had no embedding array");
        }
        List<Float> floats = new ArrayList<>();
        for (JsonNode n : emb) {
            floats.add((float) n.asDouble());
        }
        float[] arr = new float[floats.size()];
        for (int i = 0; i < floats.size(); i++) {
            arr[i] = floats.get(i);
        }
        return arr;
    }

    private static String trimSlash(String u) {
        String s = u == null ? "" : u.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
