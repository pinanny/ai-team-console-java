package com.example.aiteamconsole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} on the local machine (must be on {@code PATH}).
 */
public final class LocalGitOperations {

    private LocalGitOperations() {
    }

    public static void runGit(Path workDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("git timeout: " + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            throw new IOException("git failed (" + p.exitValue() + "): " + String.join(" ", args) + "\n" + out);
        }
    }

    /**
     * Runs git; returns exit code and combined stdout/stderr (never throws on non-zero exit).
     */
    public static GitRunResult runGitCapture(Path workDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return new GitRunResult(-1, "git timeout: " + String.join(" ", args));
        }
        return new GitRunResult(p.exitValue(), out);
    }

    public record GitRunResult(int exitCode, String output) {
    }

    public static void cloneAuthenticated(Path parentDir, String cloneUrl, String folderName) throws IOException, InterruptedException {
        Files.createDirectories(parentDir);
        Path target = parentDir.resolve(folderName);
        if (Files.exists(target)) {
            deleteRecursive(target);
        }
        ProcessBuilder pb = new ProcessBuilder("git", "clone", cloneUrl, folderName);
        pb.directory(parentDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("git clone timeout");
        }
        if (p.exitValue() != 0) {
            throw new IOException("git clone failed: " + out);
        }
    }

    public static void writePatchAndApply(Path repoRoot, String unifiedDiff) throws IOException, InterruptedException {
        String body = PatchParseUtil.prepareUnifiedDiffForGitApply(unifiedDiff);
        Path patch = repoRoot.resolve(".ai-team-ollama.patch");
        Files.writeString(patch, body, StandardCharsets.UTF_8);
        String patchArg = patch.toString();
        String[][] variants = {
                {"apply", "--whitespace=nowarn", "--ignore-space-change", "--recount", patchArg},
                {"apply", "--whitespace=nowarn", "--ignore-space-change", patchArg},
                {"apply", "--whitespace=nowarn", patchArg},
        };
        StringBuilder failures = new StringBuilder();
        try {
            for (String[] tail : variants) {
                GitRunResult r = runGitCapture(repoRoot, tail);
                if (r.exitCode() == 0) {
                    return;
                }
                failures.append("git ").append(String.join(" ", tail)).append(" → ").append(r.exitCode()).append("\n")
                        .append(r.output()).append("\n---\n");
            }
            throw new IOException("git apply failed after " + variants.length + " attempts:\n" + failures);
        } finally {
            Files.deleteIfExists(patch);
        }
    }

    public static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
