package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePromptCatalogTest {
    @Test
    void loadsSpecializedPromptForEveryRole() {
        RolePromptCatalog catalog = RolePromptCatalog.defaultCatalog();

        for (AgentRole role : AgentRole.values()) {
            String prompt = catalog.promptFor(role);

            assertTrue(prompt.contains("source: https://github.com/msitarzewski/agency-agents/"), role.name());
            assertTrue(prompt.length() > 500, role.name());
        }
    }
}
