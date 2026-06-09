package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePromptCatalogTest {
    @Test
    void loadsSpecializedPromptForEveryRole() {
        RolePromptCatalog catalog = RolePromptCatalog.defaultCatalog();

        for (AgentRole role : AgentRole.values()) {
            String prompt = catalog.promptFor(role);

            assertTrue(prompt.length() > 80, role.name());
            boolean bundled = prompt.contains("source: https://github.com/msitarzewski/agency-agents/");
            if (bundled) {
                assertTrue(prompt.length() > 500, role.name());
            } else {
                assertTrue(
                        prompt.contains("Act as " + role.label()),
                        role.name() + " expected fallback or label in prompt");
            }
        }
    }
}
