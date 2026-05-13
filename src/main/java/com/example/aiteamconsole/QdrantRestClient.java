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
import java.util.UUID;

/**
 * Minimal Qdrant REST client (no extra Maven deps). Optional for Ollama RAG.
 */
public final class QdrantRestClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public QdrantRestClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    QdrantRestClient(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public void ensureCollection(String baseUrl, String collection, int vectorSize) throws IOException, InterruptedException {
        String url = trimSlash(baseUrl) + "/collections/" + encode(collection);
        ObjectNode body = mapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", vectorSize);
        vectors.put("distance", "Cosine");
        HttpRequest put = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(put, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            return;
        }
        if (resp.statusCode() == 409) {
            return;
        }
        throw new IOException("Qdrant PUT collection HTTP " + resp.statusCode() + ": " + resp.body());
    }

    public void deleteCollection(String baseUrl, String collection) {
        try {
            String url = trimSlash(baseUrl) + "/collections/" + encode(collection);
            HttpRequest del = HttpRequest.newBuilder(URI.create(url)).DELETE().build();
            httpClient.send(del, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    public void upsertPoints(String baseUrl, String collection, List<QdrantPoint> points) throws IOException, InterruptedException {
        if (points.isEmpty()) {
            return;
        }
        String url = trimSlash(baseUrl) + "/collections/" + encode(collection) + "/points?wait=true";
        ObjectNode root = mapper.createObjectNode();
        ArrayNode arr = root.putArray("points");
        for (QdrantPoint p : points) {
            ObjectNode o = arr.addObject();
            o.put("id", p.id().toString());
            ArrayNode vec = o.putArray("vector");
            for (float f : p.vector()) {
                vec.add(f);
            }
            ObjectNode payload = o.putObject("payload");
            payload.put("path", p.path());
            payload.put("text", p.text());
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Qdrant upsert HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    public List<String> search(String baseUrl, String collection, float[] queryVector, int limit) throws IOException, InterruptedException {
        String url = trimSlash(baseUrl) + "/collections/" + encode(collection) + "/points/search";
        ObjectNode body = mapper.createObjectNode();
        ArrayNode vec = body.putArray("vector");
        for (float f : queryVector) {
            vec.add(f);
        }
        body.put("limit", limit);
        body.put("with_payload", true);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Qdrant search HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        List<String> out = new ArrayList<>();
        for (JsonNode hit : root.path("result")) {
            JsonNode payload = hit.path("payload");
            String path = payload.path("path").asText("");
            String text = payload.path("text").asText("");
            if (!text.isBlank()) {
                out.add(path + " :: " + text);
            }
        }
        return out;
    }

    public record QdrantPoint(UUID id, float[] vector, String path, String text) {
    }

    private static String trimSlash(String u) {
        if (u == null || u.isBlank()) {
            return "";
        }
        String s = u.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String encode(String collection) {
        return java.net.URLEncoder.encode(collection, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
