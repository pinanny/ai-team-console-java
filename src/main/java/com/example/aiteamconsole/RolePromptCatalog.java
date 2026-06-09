package com.example.aiteamconsole;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class RolePromptCatalog {
    private static final RolePromptCatalog DEFAULT = new RolePromptCatalog();
    private static final Map<AgentRole, String> ROLE_PROMPTS = Map.of(
            AgentRole.BACKEND_ENGINEER, "agent-prompts/backend-engineer.md",
            AgentRole.FRONTEND_ENGINEER, "agent-prompts/frontend-engineer.md",
            AgentRole.QA_ENGINEER, "agent-prompts/qa-engineer.md",
            AgentRole.CODE_REVIEWER, "agent-prompts/code-reviewer.md",
            AgentRole.DEVOPS_ENGINEER, "agent-prompts/devops-engineer.md",
            AgentRole.PRODUCT_ANALYST, "agent-prompts/product-analyst.md",
            AgentRole.IMPLEMENTATION_PLANNER, "agent-prompts/implementation-planner.md"
    );

    static RolePromptCatalog defaultCatalog() {
        return DEFAULT;
    }

    String promptFor(AgentRole role) {
        String resourcePath = ROLE_PROMPTS.get(role);
        if (resourcePath == null) {
            return fallback(role);
        }
        try (InputStream in = RolePromptCatalog.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return fallback(role);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load role prompt resource: " + resourcePath, e);
        }
    }

    private static String fallback(AgentRole role) {
        return "Act as " + role.label() + ". Follow the task description, keep changes focused, and run relevant checks.";
    }
}
