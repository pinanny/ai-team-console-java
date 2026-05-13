package com.example.aiteamconsole;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatchParseUtil {

    private static final Pattern FENCE_DIFF = Pattern.compile("```(?:diff|patch)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private PatchParseUtil() {
    }

    /**
     * Extracts first unified diff from model output (raw or fenced).
     */
    public static String extractUnifiedDiff(String modelOutput) {
        if (modelOutput == null) {
            return "";
        }
        String t = modelOutput.strip();
        if (t.equals("NO_PATCH") || t.startsWith("NO_PATCH")) {
            return "";
        }
        Matcher m = FENCE_DIFF.matcher(t);
        if (m.find()) {
            String inner = m.group(1).strip();
            if (inner.startsWith("diff --git")) {
                return prepareUnifiedDiffForGitApply(inner);
            }
        }
        int idx = t.indexOf("diff --git");
        if (idx >= 0) {
            return prepareUnifiedDiffForGitApply(t.substring(idx).strip());
        }
        return "";
    }

    /**
     * Normalizes line endings, strips BOM, removes trailing non-diff lines (model commentary after the patch).
     */
    public static String prepareUnifiedDiffForGitApply(String diff) {
        if (diff == null || diff.isBlank()) {
            return "";
        }
        String lf = diff.replace("\r\n", "\n").replace('\r', '\n');
        if (lf.startsWith("\uFEFF")) {
            lf = lf.substring(1);
        }
        String[] lines = lf.split("\n", -1);
        int end = lines.length;
        while (end > 0) {
            String line = lines[end - 1];
            if (line.isEmpty()) {
                end--;
                continue;
            }
            if (isLikelyUnifiedDiffLine(line)) {
                break;
            }
            end--;
        }
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, end)).strip();
        if (body.isEmpty()) {
            return "";
        }
        return body.endsWith("\n") ? body : body + "\n";
    }

    private static boolean isLikelyUnifiedDiffLine(String line) {
        if (line.isEmpty()) {
            return true;
        }
        if (line.startsWith("diff --git ")) {
            return true;
        }
        if (line.startsWith("index ")) {
            return true;
        }
        if (line.startsWith("--- ") || line.startsWith("+++ ")) {
            return true;
        }
        if (line.startsWith("@@")) {
            return true;
        }
        if (line.startsWith("\\")) {
            return true;
        }
        if (line.startsWith("new file mode ")
                || line.startsWith("deleted file mode ")
                || line.startsWith("similarity index ")
                || line.startsWith("dissimilarity index ")
                || line.startsWith("rename from ")
                || line.startsWith("rename to ")
                || line.startsWith("Binary files ")) {
            return true;
        }
        if (line.startsWith("+") || line.startsWith("-")) {
            return true;
        }
        return line.charAt(0) == ' ';
    }

    public static String summarizeRepository(java.nio.file.Path root, int maxFiles, int maxPathLength) throws java.io.IOException {
        List<String> paths = new ArrayList<>();
        java.nio.file.Files.walk(root)
                .filter(p -> java.nio.file.Files.isRegularFile(p))
                .filter(p -> !isIgnored(p))
                .limit(maxFiles)
                .forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (rel.length() > maxPathLength) {
                        rel = rel.substring(0, maxPathLength) + "…";
                    }
                    paths.add(rel);
                });
        return String.join("\n", paths);
    }

    private static boolean isIgnored(java.nio.file.Path p) {
        String s = p.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return s.contains("/.git/")
                || s.contains("/node_modules/")
                || s.contains("/target/")
                || s.contains("/build/")
                || s.contains("/.idea/")
                || s.endsWith(".class")
                || s.endsWith(".jar");
    }

    public static List<TextChunk> chunkFile(java.nio.file.Path absoluteFile, java.nio.file.Path repoRoot, int maxChars)
            throws java.io.IOException {
        String rel = repoRoot.relativize(absoluteFile).toString().replace('\\', '/');
        String content = java.nio.file.Files.readString(absoluteFile);
        List<TextChunk> out = new ArrayList<>();
        if (content.length() <= maxChars) {
            out.add(new TextChunk(rel, content));
            return out;
        }
        for (int i = 0; i < content.length(); i += maxChars) {
            int end = Math.min(content.length(), i + maxChars);
            out.add(new TextChunk(rel + "#" + i, content.substring(i, end)));
        }
        return out;
    }

    public record TextChunk(String path, String text) {
    }

    /**
     * A "new file" block extracted from a unified diff: target path under repo root and the file content
     * (joined from the "+"-prefixed lines of the single {@code @@ -0,0 +1,N @@} hunk).
     */
    public record NewFileBlock(String path, String content) {
    }

    /**
     * Returns every {@code diff --git} block as a (path, additions-only content) pair, regardless of whether the
     * header declares the block as "new file mode" or modifies an existing path. The caller decides whether to
     * create the file (e.g. only when it does not already exist).
     *
     * <p>Body extraction collects "+" lines verbatim (without the leading "+"); " " (context) and "-" lines are skipped,
     * which gives the right content for additions-only hunks. Mixed hunks may yield partial content; callers should
     * fall back to {@code git apply} when the patch is a real modification.
     */
    public static List<NewFileBlock> extractAdditionsByPath(String diff) {
        return extractBlocks(diff, false);
    }

    /**
     * Scans the unified diff for blocks that introduce new files (header has "new file mode" or source is /dev/null)
     * and returns parsed paths + reconstructed content. Existing-file modifications are ignored here.
     */
    public static List<NewFileBlock> extractNewFileBlocks(String diff) {
        return extractBlocks(diff, true);
    }

    private static List<NewFileBlock> extractBlocks(String diff, boolean newFileOnly) {
        List<NewFileBlock> out = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return out;
        }
        String[] lines = diff.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (!line.startsWith("diff --git ")) {
                i++;
                continue;
            }
            int blockStart = i;
            int blockEnd = lines.length;
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].startsWith("diff --git ")) {
                    blockEnd = j;
                    break;
                }
            }
            String path = pathFromDiffHeader(line);
            boolean isNewFile = false;
            int hunkStart = -1;
            for (int j = blockStart; j < blockEnd; j++) {
                String l = lines[j];
                if (l.startsWith("new file mode ")) {
                    isNewFile = true;
                }
                if (l.startsWith("--- /dev/null")) {
                    isNewFile = true;
                }
                if (l.startsWith("+++ b/") && (path == null || path.isBlank())) {
                    path = l.substring(6).trim();
                }
                if (l.startsWith("@@")) {
                    hunkStart = j + 1;
                    break;
                }
            }
            boolean accept = path != null && !path.isBlank() && hunkStart > 0 && (!newFileOnly || isNewFile);
            if (accept) {
                StringBuilder body = new StringBuilder();
                for (int j = hunkStart; j < blockEnd; j++) {
                    String l = lines[j];
                    if (l.isEmpty()) {
                        continue;
                    }
                    char c = l.charAt(0);
                    if (c == '+') {
                        body.append(l.substring(1)).append('\n');
                    } else if (c == '\\') {
                        if (l.startsWith("\\ No newline at end of file") && body.length() > 0
                                && body.charAt(body.length() - 1) == '\n') {
                            body.setLength(body.length() - 1);
                        }
                    } else if (c == '-' || c == ' ') {
                    } else if (l.startsWith("@@")) {
                        break;
                    } else {
                        break;
                    }
                }
                out.add(new NewFileBlock(path, body.toString()));
            }
            i = blockEnd;
        }
        return out;
    }

    private static String pathFromDiffHeader(String line) {
        int sp = line.indexOf(" b/");
        if (sp < 0) {
            return "";
        }
        String tail = line.substring(sp + 3).trim();
        return tail;
    }
}
