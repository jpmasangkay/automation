package com.automation.model;

import com.automation.comparator.rules.CheckResult;

import java.util.List;
import java.util.Map;

public record ComparisonResult(
    SiteData siteA,
    SiteData siteB,
    TextDiff textDiff,
    ImageDiff imageDiff,
    LinkDiff linkDiff,
    MapDiff metadataDiff,
    MapDiff dataLayerDiff,
    long comparisonTimeMillis,
    SimilarityScores similarityScores,
    List<CheckResult> additionalRuleResults
) {
    public boolean isAllMatch() {
        return textDiff.matches() && imageDiff.matches() && linkDiff.matches()
            && metadataDiff.matches() && dataLayerDiff.matches();
    }

    public record TextDiff(boolean matches, int totalLinesA, int totalLinesB,
                           int matchedLineCount, List<String> onlyInA, List<String> onlyInB) {}

    public record ImageDiff(boolean matches, List<ImageMatch> matchesList,
                            int matchedImagesCount, int visualMatchImagesCount, int mismatchCount) {}

    public record ImageMatch(ImageData imgA, ImageData imgB, String status) {}

    public record LinkDiff(boolean matches, List<LinkData> onlyInA, List<LinkData> onlyInB, int matchedLinksCount) {}

    public record MapDiff(boolean matches, Map<String, String> onlyInA, Map<String, String> onlyInB, Map<String, String[]> valueDiffs) {}
}
