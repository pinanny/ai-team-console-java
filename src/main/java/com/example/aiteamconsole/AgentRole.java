package com.example.aiteamconsole;

public enum AgentRole {
    BACKEND_ENGINEER("Backend Engineer"),
    FRONTEND_ENGINEER("Frontend Engineer"),
    QA_ENGINEER("QA Engineer"),
    CODE_REVIEWER("Code Reviewer"),
    DEVOPS_ENGINEER("DevOps Engineer"),
    PRODUCT_ANALYST("Product Analyst"),
    /**
     * One-time project memory onboarding role.
     * An agent with this role analyzes the repository once and produces a structured
     * markdown summary that is saved to {@link ProjectMemoryStore}. All subsequent
     * agent runs (regardless of provider) load this summary and inject it into their
     * prompt, avoiding repeated full-codebase analysis.
     *
     * <p>Typical usage: create one agent profile with this role, run it once per repository
     * (or whenever the project structure changes significantly), then leave it idle.
     */
    PROJECT_MEMORY("Project Memory Analyst"),

    /**
     * Planner/executor split role.
     *
     * <p>An agent with this role runs on a powerful model (Cursor Cloud / Claude) <b>after</b>
     * Product Analyst spec acceptance and <b>before</b> a Backend / Frontend implementation
     * run. It produces an extremely detailed implementation plan (file-by-file anchors,
     * code snippets, API contracts, test scenarios, out-of-scope guards, verification
     * checklist) that a small local code model can apply mechanically.
     *
     * <p>Token economics: one expensive planning pass replaces multiple expensive
     * implementation passes. The local executor (Ollama) consumes only the plan +
     * referenced files, dropping repository-layout and retrieval bloat from the prompt.
     *
     * <p>See {@code src/main/resources/agent-prompts/implementation-planner.md} for
     * the full plan contract.
     */
    IMPLEMENTATION_PLANNER("Implementation Planner");

    private final String label;

    AgentRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
