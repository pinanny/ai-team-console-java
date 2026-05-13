package com.example.aiteamconsole;

import java.time.Instant;

public record RunLogEntry(Instant timestamp, String message) {
    public static RunLogEntry now(String message) {
        return new RunLogEntry(Instant.now(), message);
    }
}
