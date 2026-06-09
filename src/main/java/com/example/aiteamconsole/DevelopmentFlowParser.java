package com.example.aiteamconsole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses / formats task development pipeline text (waves and parallel roles). */
public final class DevelopmentFlowParser {

    private static final Map<String, AgentRole> TOKEN_TO_ROLE = new LinkedHashMap<>();

    static {
        TOKEN_TO_ROLE.put("BE", AgentRole.BACKEND_ENGINEER);
        TOKEN_TO_ROLE.put("FE", AgentRole.FRONTEND_ENGINEER);
        TOKEN_TO_ROLE.put("QA", AgentRole.QA_ENGINEER);
        TOKEN_TO_ROLE.put("REV", AgentRole.CODE_REVIEWER);
        TOKEN_TO_ROLE.put("DEVOPS", AgentRole.DEVOPS_ENGINEER);
        TOKEN_TO_ROLE.put("PA", AgentRole.PRODUCT_ANALYST);
        TOKEN_TO_ROLE.put("PM", AgentRole.PRODUCT_ANALYST);
        TOKEN_TO_ROLE.put("MEM", AgentRole.PROJECT_MEMORY);
        TOKEN_TO_ROLE.put("PLAN", AgentRole.IMPLEMENTATION_PLANNER);
    }

    private DevelopmentFlowParser() {
    }

    /**
     * One line = one wave. Roles in a line separated by comma (optional spaces) run in parallel in that wave.
     * Empty lines ignored. Empty string → empty list (no pipeline — legacy behaviour).
     */
    public static List<TaskFlowWave> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<TaskFlowWave> waves = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<AgentRole> roles = new ArrayList<>();
            for (String part : trimmed.split(",")) {
                String token = part.strip().toUpperCase(Locale.ROOT);
                if (token.isEmpty()) {
                    continue;
                }
                AgentRole role = TOKEN_TO_ROLE.get(token);
                if (role == null) {
                    throw new IllegalArgumentException(
                            "Unknown role token «" + part.strip() + "». Use: " + String.join(", ", TOKEN_TO_ROLE.keySet()));
                }
                roles.add(role);
            }
            if (roles.isEmpty()) {
                continue;
            }
            waves.add(new TaskFlowWave(roles));
        }
        return List.copyOf(waves);
    }

    public static String format(List<TaskFlowWave> waves) {
        if (waves == null || waves.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (TaskFlowWave w : waves) {
            List<String> tokens = new ArrayList<>();
            for (AgentRole r : w.parallelRoles()) {
                tokens.add(roleToToken(r));
            }
            lines.add(String.join(", ", tokens));
        }
        return String.join("\n", lines);
    }

    private static String roleToToken(AgentRole role) {
        return switch (role) {
            case BACKEND_ENGINEER -> "BE";
            case FRONTEND_ENGINEER -> "FE";
            case QA_ENGINEER -> "QA";
            case CODE_REVIEWER -> "REV";
            case DEVOPS_ENGINEER -> "DEVOPS";
            case PRODUCT_ANALYST -> "PA";
            case PROJECT_MEMORY -> "MEM";
            case IMPLEMENTATION_PLANNER -> "PLAN";
        };
    }
}
