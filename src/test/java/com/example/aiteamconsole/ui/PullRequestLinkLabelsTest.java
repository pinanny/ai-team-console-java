package com.example.aiteamconsole.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestLinkLabelsTest {

    @Test
    void compactLabelFromPullUrl() {
        assertEquals(
                "PR-widgets",
                PullRequestLinkLabels.compactLabel("https://github.com/acme/widgets/pull/42"));
    }

    @Test
    void tooltipIncludesNumberAndUrl() {
        String tip = PullRequestLinkLabels.tooltipForUrl("https://github.com/acme/widgets/pull/42").orElse("");
        assertTrue(tip.contains("acme/widgets"));
        assertTrue(tip.contains("#42"));
        assertTrue(tip.contains("https://"));
    }
}
