package com.example.aiteamconsole;

public enum TaskStatus {
    /** Vague or new backlog item; run Product Analyst from here first when using PA-TASK prefix. */
    DRAFT,
    /**
     * Refined backlog: acceptance criteria and scope are clear enough for BE/FE/QA implementation runs.
     * Reached only after an operator accepts the Product Analyst output ({@link #SPEC_REVIEW} → OPEN).
     */
    OPEN,
    /**
     * Product Analyst run finished successfully; description may include merged analyst output.
     * Operator must review or edit the description, then use Accept spec to move the task to {@link #OPEN}
     * before implementation runs.
     */
    SPEC_REVIEW,
    QUEUED,
    RUNNING,
    WAITING_REVIEW,
    DONE,
    FAILED,
    CANCELLED
}
