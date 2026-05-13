package com.example.aiteamconsole;

public enum AgentRole {
    BACKEND_ENGINEER("Backend Engineer"),
    FRONTEND_ENGINEER("Frontend Engineer"),
    QA_ENGINEER("QA Engineer"),
    CODE_REVIEWER("Code Reviewer"),
    DEVOPS_ENGINEER("DevOps Engineer"),
    PRODUCT_ANALYST("Product Analyst");

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
