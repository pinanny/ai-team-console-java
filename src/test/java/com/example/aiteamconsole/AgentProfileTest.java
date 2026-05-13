package com.example.aiteamconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentProfileTest {
    @Test
    void autoBranchPrefixSlugifiesName() {
        assertEquals("ia-agent-backend-agent", AgentProfile.autoBranchPrefix("Backend Agent"));
        assertEquals("ia-agent-qa-bot", AgentProfile.autoBranchPrefix("  QA  Bot  "));
        assertEquals("ia-agent", AgentProfile.autoBranchPrefix(""));
        assertEquals("ia-agent", AgentProfile.autoBranchPrefix(null));
    }

    @Test
    void createFallsBackToAutoBranchPrefixWhenNotProvided() {
        AgentProfile agent = AgentProfile.create(
                "Frontend Dev",
                AgentRole.FRONTEND_ENGINEER,
                ProviderType.CURSOR_CLOUD,
                "",
                "",
                "",
                true
        );
        assertEquals("ia-agent-frontend-dev", agent.branchPrefix());
    }

    @Test
    void createKeepsExplicitBranchPrefix() {
        AgentProfile agent = AgentProfile.create(
                "Frontend Dev",
                AgentRole.FRONTEND_ENGINEER,
                ProviderType.CURSOR_CLOUD,
                "",
                "",
                "custom-prefix",
                true
        );
        assertEquals("custom-prefix", agent.branchPrefix());
    }
}
