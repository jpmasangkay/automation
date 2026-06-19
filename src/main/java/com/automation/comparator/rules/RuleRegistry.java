package com.automation.comparator.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe singleton registry for custom {@link ComparisonRule} implementations.
 * Register rules before the comparison run starts; registered rules are executed
 * by {@code SiteComparator} after all built-in checks complete.
 */
public final class RuleRegistry {

    private static final RuleRegistry INSTANCE = new RuleRegistry();
    private final List<ComparisonRule> rules = Collections.synchronizedList(new ArrayList<>());

    private RuleRegistry() {}

    public static RuleRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a custom comparison rule.
     * Rules are executed in registration order during each comparison.
     */
    public void register(ComparisonRule rule) {
        if (rule != null) rules.add(rule);
    }

    /** Returns an unmodifiable snapshot of currently registered rules. */
    public List<ComparisonRule> getRules() {
        return List.copyOf(rules);
    }

    /** Removes all registered custom rules (useful for tests). */
    public void clear() {
        rules.clear();
    }
}
