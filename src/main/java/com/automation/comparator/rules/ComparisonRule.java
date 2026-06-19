package com.automation.comparator.rules;

import com.automation.model.SiteData;

/**
 * Pluggable comparison rule interface.
 * <p>
 * Implement this interface and register it via {@link RuleRegistry#register(ComparisonRule)}
 * to add custom checks. Each custom rule's result is collected into
 * {@code ComparisonResult.additionalRuleResults} and rendered in both the
 * individual HTML/PDF report and the run dashboard.
 *
 * <pre>{@code
 * // Example custom rule: validate that both pages have a canonical link
 * RuleRegistry.getInstance().register(new ComparisonRule() {
 *     public String getName() { return "Canonical Link Present"; }
 *     public CheckResult apply(SiteData a, SiteData b) {
 *         boolean aHas = a.getMetadata().containsKey("canonical");
 *         boolean bHas = b.getMetadata().containsKey("canonical");
 *         List<String> issues = new ArrayList<>();
 *         if (!aHas) issues.add("Site A is missing a canonical link");
 *         if (!bHas) issues.add("Site B is missing a canonical link");
 *         return new CheckResult(getName(), issues.isEmpty(), 100.0, "A", "B", issues);
 *     }
 * });
 * }</pre>
 */
public interface ComparisonRule {
    /** Unique, human-readable name for this rule (shown in reports). */
    String getName();

    /**
     * Runs the comparison rule against the two extracted site data objects.
     * @param a SiteData for Site A
     * @param b SiteData for Site B
     * @return a {@link CheckResult} summarising the outcome
     */
    CheckResult apply(SiteData a, SiteData b);
}
