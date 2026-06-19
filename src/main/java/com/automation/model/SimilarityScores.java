package com.automation.model;

/**
 * Normalized similarity scores (0–100%) for each comparison dimension.
 * Computed using Jaccard similarity (|A ∩ B| / |A ∪ B|) for text and links,
 * and ratio-based scoring for images and map-based checks.
 */
public record SimilarityScores(
    double textScore,
    double imageScore,
    double linkScore,
    double metadataScore,
    double dataLayerScore,
    double overallScore
) {
    /** Human-readable quality label for the overall score. */
    public String overallLabel() {
        if (overallScore >= 95) return "EXCELLENT";
        if (overallScore >= 80) return "GOOD";
        if (overallScore >= 60) return "FAIR";
        return "POOR";
    }

    /** CSS color class matching the score level for UI rendering. */
    public String overallColorClass() {
        if (overallScore >= 95) return "score-excellent";
        if (overallScore >= 80) return "score-good";
        if (overallScore >= 60) return "score-fair";
        return "score-poor";
    }

    /** Formats a single score as a rounded percent string, e.g. "87.3%" */
    public static String fmt(double score) {
        return String.format("%.1f%%", score);
    }
}
