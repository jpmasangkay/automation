package com.automation.comparator.rules;

import java.util.List;

/**
 * The result of a single {@link ComparisonRule} execution.
 * All rules produce this common, machine-readable output which powers the dashboard and reports.
 */
public record CheckResult(
    String ruleName,
    boolean matches,
    double similarityPercent,
    String countA,
    String countB,
    List<String> issues
) {
    /** Returns a human-readable verdict badge text. */
    public String verdict() {
        return matches ? "PASS" : "MISMATCH";
    }
}
