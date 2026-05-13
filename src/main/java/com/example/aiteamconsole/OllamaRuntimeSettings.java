package com.example.aiteamconsole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record OllamaRuntimeSettings(
        String ollamaBaseUrl,
        String ollamaModel,
        String embeddingModel,
        boolean qdrantEnabled,
        String qdrantBaseUrl,
        String qdrantCollection,
        int embeddingDimensions,
        int ragMaxFiles,
        int ragChunkChars
) {
    public static OllamaRuntimeSettings defaults() {
        return new OllamaRuntimeSettings(
                "http://127.0.0.1:11434",
                defaultChatModelFromEnv(),
                "nomic-embed-text",
                false,
                "http://127.0.0.1:6333",
                "ai-team-console-code",
                768,
                120,
                1200
        ).normalized();
    }

    public OllamaRuntimeSettings normalized() {
        String ob = ollamaBaseUrl == null || ollamaBaseUrl.isBlank() ? "http://127.0.0.1:11434" : ollamaBaseUrl.strip();
        String om = ollamaModel == null || ollamaModel.isBlank() ? defaultChatModelFromEnv() : ollamaModel.strip();
        // Ollama CLI typically registers e.g. llama3.2:3b; bare "llama3.2" often 404s.
        if ("llama3.2".equals(om)) {
            om = "llama3.2:3b";
        }
        String em = embeddingModel == null || embeddingModel.isBlank() ? "nomic-embed-text" : embeddingModel.strip();
        String qb = qdrantBaseUrl == null ? "" : qdrantBaseUrl.strip();
        String qc = qdrantCollection == null || qdrantCollection.isBlank() ? "ai-team-console-code" : qdrantCollection.strip();
        int dim = embeddingDimensions <= 0 ? 768 : embeddingDimensions;
        int mf = ragMaxFiles <= 0 ? 120 : Math.min(ragMaxFiles, 500);
        int cc = ragChunkChars <= 0 ? 1200 : Math.min(ragChunkChars, 8000);
        return new OllamaRuntimeSettings(ob, om, em, qdrantEnabled, qb, qc, dim, mf, cc);
    }

    private static String defaultChatModelFromEnv() {
        String v = System.getenv("OLLAMA_CHAT_MODEL");
        return (v == null || v.isBlank()) ? "llama3.2:3b" : v.strip();
    }

    public static final class Store {
        private final ObjectMapper mapper;
        private final Path file;

        public Store(Path configDir) {
            this.file = configDir.resolve("ollama-settings.json");
            this.mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .enable(SerializationFeature.INDENT_OUTPUT);
        }

        public static Store defaultStore() {
            return new Store(StateStore.defaultStore().stateFile().getParent());
        }

        public OllamaRuntimeSettings load() {
            if (!Files.exists(file)) {
                return OllamaRuntimeSettings.defaults();
            }
            try {
                OllamaRuntimeSettings raw = mapper.readValue(file.toFile(), OllamaRuntimeSettings.class);
                OllamaRuntimeSettings normalized = raw.normalized();
                boolean legacyBareLlama = raw.ollamaModel() != null && "llama3.2".equals(raw.ollamaModel().strip());
                if (legacyBareLlama && !normalized.ollamaModel().equals(raw.ollamaModel().strip())) {
                    save(normalized);
                }
                return normalized;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load " + file, e);
            }
        }

        public void save(OllamaRuntimeSettings settings) {
            try {
                Files.createDirectories(file.getParent());
                mapper.writeValue(file.toFile(), settings.normalized());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to save " + file, e);
            }
        }

        public Path filePath() {
            return file;
        }
    }
}
