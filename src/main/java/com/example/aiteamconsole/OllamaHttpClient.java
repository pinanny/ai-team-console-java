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

    /**
     * Result of one {@link #chatWithMetrics} call.
     *
     * <p>{@code promptEvalCount} and {@code evalCount} come straight from Ollama's
     * {@code /api/chat} response. They count tokens evaluated for the prompt and
     * generated for the completion respectively — used by the console to measure
     * the effect of token-saving optimizations (planner/executor split, project
     * memory tiers, etc.) and to surface per-run cost in the UI.
     *
     * <p>Both counts default to {@code 0} when Ollama omits them (older servers).
     */
    public record OllamaChatResult(String content, int promptEvalCount, int evalCount) {
        public OllamaChatResult {
            content = content == null ? "" : content;
            if (promptEvalCount < 0) promptEvalCount = 0;
            if (evalCount < 0) evalCount = 0;
        }
    }

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaHttpClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    OllamaHttpClient(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    /**
     * Backwards-compatible wrapper around {@link #chatWithMetrics(String, String, String, String)}.
     * Drops token-count metrics on the floor — prefer the metric-aware variant for
     * code that needs to log or display token cost.
     */
    public String chat(String baseUrl, String model, String system, String user) throws IOException, InterruptedException {
        return chatWithMetrics(baseUrl, model, system, user).content();
    }

    /**
     * Sends a chat request and returns the response text together with Ollama's
     * native token-eval metrics from the same response payload.
     */
    public OllamaChatResult chatWithMetrics(String baseUrl, String model, String system, String user)
            throws IOException, InterruptedException {
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
        String content = root.path("message").path("content").asText("");
        int promptEval = root.path("prompt_eval_count").asInt(0);
        int eval = root.path("eval_count").asInt(0);
        return new OllamaChatResult(content, promptEval, eval);
    }

    /**
     * Rich metadata about a locally installed Ollama model.
     *
     * @param name           full model tag, e.g. {@code "llama3.2:3b"}
     * @param parameterSize  human-readable parameter count from Ollama, e.g. {@code "3.2B"} (may be empty)
     * @param quantization   quantization level, e.g. {@code "Q4_K_M"} (may be empty)
     * @param diskBytes      model file size on disk in bytes (0 if unknown)
     */
    public record OllamaModelInfo(
            String name,
            String parameterSize,
            String quantization,
            long diskBytes
    ) {
        /** Disk size formatted as a human-readable string, e.g. {@code "2.0 GB"}. */
        public String diskSizeLabel() {
            if (diskBytes <= 0) return "";
            if (diskBytes >= 1_000_000_000L)
                return "%.1f GB".formatted(diskBytes / 1_000_000_000.0);
            if (diskBytes >= 1_000_000L)
                return "%.0f MB".formatted(diskBytes / 1_000_000.0);
            return "%.0f KB".formatted(diskBytes / 1_000.0);
        }

        /** Approximate RAM needed in GB (disk size + ~20% overhead for quantized GGUF models). */
        public double estimatedRamGb() {
            if (diskBytes <= 0) return 0;
            return (diskBytes * 1.2) / 1_000_000_000.0;
        }
    }

    /**
     * Returns names of locally installed Ollama models ({@code GET /api/tags}).
     * Equivalent to {@code ollama list}. For richer metadata use {@link #listLocalModelDetails}.
     */
    public List<String> listLocalModels(String baseUrl) throws IOException, InterruptedException {
        return listLocalModelDetails(baseUrl).stream()
                .map(OllamaModelInfo::name)
                .toList();
    }

    /**
     * Returns rich metadata for all locally installed models ({@code GET /api/tags}).
     * Includes parameter count, quantization level, and disk size from Ollama's response.
     */
    public List<OllamaModelInfo> listLocalModelDetails(String baseUrl) throws IOException, InterruptedException {
        String url = trimSlash(baseUrl) + "/api/tags";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Ollama /api/tags HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        List<OllamaModelInfo> result = new ArrayList<>();
        for (JsonNode model : root.path("models")) {
            String name = model.path("name").asText("").strip();
            if (name.isBlank()) continue;
            JsonNode details = model.path("details");
            String paramSize  = details.path("parameter_size").asText("").strip();
            String quant      = details.path("quantization_level").asText("").strip();
            long   diskBytes  = model.path("size").asLong(0);
            result.add(new OllamaModelInfo(name, paramSize, quant, diskBytes));
        }
        return result;
    }

    /**
     * Pulls (downloads) a model from the Ollama registry, streaming progress lines to
     * {@code onProgress}. Blocks until the pull completes or throws.
     *
     * @param modelName  e.g. {@code "llama3.2:3b"} or {@code "qwen2.5-coder:7b"}
     * @param onProgress receives raw JSON status strings on a background thread
     */
    public void pullModel(String baseUrl, String modelName,
                          java.util.function.Consumer<String> onProgress)
            throws IOException, InterruptedException {
        String url = trimSlash(baseUrl) + "/api/pull";
        ObjectNode body = mapper.createObjectNode();
        body.put("name", modelName);
        body.put("stream", true);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<java.io.InputStream> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Ollama /api/pull HTTP " + resp.statusCode());
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty()) continue;
                onProgress.accept(t);
                if (t.contains("\"status\":\"success\"")) break;
                if (t.contains("\"error\"")) throw new IOException("Ollama pull error: " + t);
            }
        }
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
