package com.example.aiteamconsole;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Determines whether an Ollama model is likely to run on the current machine
 * based on available system RAM.
 *
 * <p>For locally installed models, the RAM estimate comes from the model's disk size
 * reported by {@code GET /api/tags} (quantized GGUF models need roughly the same amount
 * of RAM as their file size, plus ~20% overhead).
 *
 * <p>For models not yet installed, a hardcoded table of well-known models is used.
 */
public final class OllamaModelCompatibility {

    /** Compatibility verdict for a model. */
    public enum Fit {
        /** Model should run comfortably — required RAM ≤ 75% of system RAM. */
        FITS("✓", "#2ecc71", "Should run comfortably on this machine"),
        /** Tight fit — required RAM is 75–100% of system RAM; may be slow or cause swapping. */
        TIGHT("⚠", "#e67e22", "May run but could be slow — RAM is tight"),
        /** Model almost certainly requires more RAM than available. */
        TOO_LARGE("✗", "#e74c3c", "Likely too large for this machine's RAM"),
        /** No information available to make a determination. */
        UNKNOWN("?", "#999999", "RAM requirements unknown");

        public final String icon;
        public final String color;
        public final String description;

        Fit(String icon, String color, String description) {
            this.icon = icon;
            this.color = color;
            this.description = description;
        }
    }

    /**
     * Known RAM requirements (in GB) for popular uninstalled models.
     * Values are the minimum RAM needed; comfortable usage is ~1.5× this.
     * Source: Ollama documentation and community benchmarks.
     */
    private static final Map<String, Double> KNOWN_RAM_GB = new LinkedHashMap<>();

    static {
        // llama3 family
        KNOWN_RAM_GB.put("llama3.2:1b",              2.0);
        KNOWN_RAM_GB.put("llama3.2:3b",              3.5);
        KNOWN_RAM_GB.put("llama3.1:8b",              8.0);
        KNOWN_RAM_GB.put("llama3.1:70b",            48.0);
        KNOWN_RAM_GB.put("llama3:8b",                8.0);
        KNOWN_RAM_GB.put("llama3:70b",              48.0);
        // qwen2.5-coder family
        KNOWN_RAM_GB.put("qwen2.5-coder:1.5b",      2.0);
        KNOWN_RAM_GB.put("qwen2.5-coder:3b",         3.5);
        KNOWN_RAM_GB.put("qwen2.5-coder:7b",         8.0);
        KNOWN_RAM_GB.put("qwen2.5-coder:14b",       14.0);
        KNOWN_RAM_GB.put("qwen2.5-coder:32b",       22.0);
        // mistral family
        KNOWN_RAM_GB.put("mistral:7b",               8.0);
        KNOWN_RAM_GB.put("mistral-nemo",            12.0);
        // gemma2 family
        KNOWN_RAM_GB.put("gemma2:2b",                3.0);
        KNOWN_RAM_GB.put("gemma2:9b",               10.0);
        KNOWN_RAM_GB.put("gemma2:27b",              20.0);
        // deepseek-coder
        KNOWN_RAM_GB.put("deepseek-coder-v2:16b",   18.0);
        KNOWN_RAM_GB.put("deepseek-coder-v2:236b", 140.0);
        // phi family
        KNOWN_RAM_GB.put("phi3.5:3.8b",              5.0);
        KNOWN_RAM_GB.put("phi4:14b",                16.0);
        // codellama
        KNOWN_RAM_GB.put("codellama:7b",             8.0);
        KNOWN_RAM_GB.put("codellama:13b",           14.0);
        KNOWN_RAM_GB.put("codellama:34b",           24.0);
    }

    private OllamaModelCompatibility() {
    }

    /**
     * Returns the total system RAM in gigabytes.
     * Uses {@link com.sun.management.OperatingSystemMXBean} when available;
     * falls back to {@link Runtime#maxMemory()} (JVM heap limit) as a conservative estimate.
     */
    public static double systemRamGb() {
        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            // Use reflection to avoid a hard dependency on com.sun.management
            java.lang.reflect.Method method = bean.getClass().getMethod("getTotalMemorySize");
            method.setAccessible(true);
            long bytes = (long) method.invoke(bean);
            if (bytes > 0) {
                return bytes / 1_000_000_000.0;
            }
        } catch (Exception ignored) {
            // Fall through to JVM fallback
        }
        // JVM max memory is a lower bound (usually 25% of physical RAM by default)
        return Runtime.getRuntime().maxMemory() * 4.0 / 1_000_000_000.0;
    }

    /**
     * Returns a human-readable label for system RAM, e.g. {@code "16 GB"}.
     */
    public static String systemRamLabel() {
        double gb = systemRamGb();
        if (gb >= 1.0) return "%.0f GB".formatted(gb);
        return "%.0f MB".formatted(gb * 1000);
    }

    /**
     * Evaluates whether {@code model} is likely to run on this machine.
     *
     * <p>For installed models ({@code diskBytes > 0}): uses the on-disk size + 20% overhead.
     * For uninstalled models: looks up the known RAM table; returns {@link Fit#UNKNOWN} if not found.
     */
    public static Fit evaluate(String modelName, long diskBytes) {
        double systemGb = systemRamGb();
        double requiredGb = requiredRamGb(modelName, diskBytes);
        if (requiredGb <= 0) return Fit.UNKNOWN;
        double ratio = requiredGb / systemGb;
        if (ratio <= 0.75) return Fit.FITS;
        if (ratio <= 1.0)  return Fit.TIGHT;
        return Fit.TOO_LARGE;
    }

    /**
     * Returns the estimated RAM required in GB, or 0 if unknown.
     * For installed models uses disk size; for others uses the known table.
     */
    public static double requiredRamGb(String modelName, long diskBytes) {
        if (diskBytes > 0) {
            // GGUF quantized: RAM ≈ disk size × 1.2
            return (diskBytes * 1.2) / 1_000_000_000.0;
        }
        // Try exact match first, then prefix match
        String key = normalizeModelName(modelName);
        if (KNOWN_RAM_GB.containsKey(key)) {
            return KNOWN_RAM_GB.get(key);
        }
        // Fuzzy: match by prefix (e.g. "llama3.2" → smallest known variant)
        for (Map.Entry<String, Double> entry : KNOWN_RAM_GB.entrySet()) {
            if (key.startsWith(entry.getKey().split(":")[0])) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /**
     * Builds a tooltip string with model details and compatibility verdict.
     */
    public static String buildTooltip(String modelName, long diskBytes,
                                       String paramSize, String quantization) {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append(modelName).append("\n");
        if (!paramSize.isBlank())    sb.append("Parameters: ").append(paramSize).append("\n");
        if (!quantization.isBlank()) sb.append("Quantization: ").append(quantization).append("\n");

        double required = requiredRamGb(modelName, diskBytes);
        if (diskBytes > 0) {
            sb.append("Disk size: ").append(formatGb(diskBytes / 1_000_000_000.0)).append("\n");
        }
        if (required > 0) {
            sb.append("Est. RAM needed: ").append(formatGb(required)).append("\n");
        }

        sb.append("Your RAM: ").append(systemRamLabel()).append("\n");
        Fit fit = evaluate(modelName, diskBytes);
        sb.append("Compatibility: ").append(fit.icon).append(" ").append(fit.description);
        return sb.toString();
    }

    private static String normalizeModelName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase();
    }

    private static String formatGb(double gb) {
        return "%.1f GB".formatted(gb);
    }
}
