package com.example.aiteamconsole;

public enum RunStatus {
    CREATING,
    RUNNING,
    FINISHED,
    CANCELLED,
    ERROR,
    UNKNOWN;

    public boolean terminal() {
        return this == FINISHED || this == CANCELLED || this == ERROR;
    }

    public static RunStatus fromCursorStatus(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        return switch (status.trim().toUpperCase()) {
            case "CREATING" -> CREATING;
            case "RUNNING" -> RUNNING;
            case "FINISHED" -> FINISHED;
            case "CANCELLED", "CANCELED" -> CANCELLED;
            case "ERROR", "FAILED" -> ERROR;
            default -> UNKNOWN;
        };
    }
}
