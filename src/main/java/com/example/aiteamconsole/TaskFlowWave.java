package com.example.aiteamconsole;

import java.util.ArrayList;
import java.util.List;

/**
 * One wave in a task development pipeline: all listed roles are started together (parallel);
 * waves run strictly in order.
 */
public record TaskFlowWave(List<AgentRole> parallelRoles) {
    public TaskFlowWave {
        List<AgentRole> copy = new ArrayList<>();
        if (parallelRoles != null) {
            for (AgentRole r : parallelRoles) {
                if (r != null) {
                    copy.add(r);
                }
            }
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Each wave must contain at least one role");
        }
        parallelRoles = List.copyOf(copy);
    }
}
