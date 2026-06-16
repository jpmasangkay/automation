package com.automation.model;

import java.util.List;
import java.util.Map;

public class ComparisonResult {
    private final SiteData siteA;
    private final SiteData siteB;

    // Text — line-level diff on raw text
    private final boolean textMatches;
    private final int totalLinesA;
    private final int totalLinesB;
    private final int matchedLineCount;
    private final List<String> textOnlyInA;
    private final List<String> textOnlyInB;

    // Images
    private final boolean imagesMatch;
    private final List<Integer> mismatchedImageIndices;
    private final int matchedImagesCount;
    private final int visualMatchImagesCount; // same image, different path/format/rendition

    // Links
    private final boolean linksMatch;
    private final List<LinkData> linksOnlyInA;
    private final List<LinkData> linksOnlyInB;
    private final int matchedLinksCount;

    // Metadata
    private final boolean metadataMatches;
    private final Map<String, String> metaOnlyInA;
    private final Map<String, String> metaOnlyInB;
    private final Map<String, String[]> metaValueDiffs;

    // DataLayer
    private final boolean dataLayerMatches;
    private final Map<String, String> dataLayerOnlyInA;
    private final Map<String, String> dataLayerOnlyInB;
    private final Map<String, String[]> dataLayerValueDiffs;

    // Timing
    private final long comparisonTimeMillis;

    public ComparisonResult(SiteData siteA, SiteData siteB,
            boolean textMatches, int totalLinesA, int totalLinesB, int matchedLineCount,
            List<String> textOnlyInA, List<String> textOnlyInB,
            boolean imagesMatch, List<Integer> mismatchedImageIndices, int matchedImagesCount, int visualMatchImagesCount,
            boolean linksMatch, List<LinkData> linksOnlyInA, List<LinkData> linksOnlyInB, int matchedLinksCount,
            boolean metadataMatches, Map<String, String> metaOnlyInA, Map<String, String> metaOnlyInB,
            Map<String, String[]> metaValueDiffs,
            boolean dataLayerMatches, Map<String, String> dataLayerOnlyInA, Map<String, String> dataLayerOnlyInB,
            Map<String, String[]> dataLayerValueDiffs,
            long comparisonTimeMillis) {
        this.siteA = siteA;
        this.siteB = siteB;
        this.textMatches = textMatches;
        this.totalLinesA = totalLinesA;
        this.totalLinesB = totalLinesB;
        this.matchedLineCount = matchedLineCount;
        this.textOnlyInA = textOnlyInA;
        this.textOnlyInB = textOnlyInB;
        this.imagesMatch = imagesMatch;
        this.mismatchedImageIndices = mismatchedImageIndices;
        this.matchedImagesCount = matchedImagesCount;
        this.visualMatchImagesCount = visualMatchImagesCount;
        this.linksMatch = linksMatch;
        this.linksOnlyInA = linksOnlyInA;
        this.linksOnlyInB = linksOnlyInB;
        this.matchedLinksCount = matchedLinksCount;
        this.metadataMatches = metadataMatches;
        this.metaOnlyInA = metaOnlyInA;
        this.metaOnlyInB = metaOnlyInB;
        this.metaValueDiffs = metaValueDiffs;
        this.dataLayerMatches = dataLayerMatches;
        this.dataLayerOnlyInA = dataLayerOnlyInA;
        this.dataLayerOnlyInB = dataLayerOnlyInB;
        this.dataLayerValueDiffs = dataLayerValueDiffs;
        this.comparisonTimeMillis = comparisonTimeMillis;
    }

    public boolean isAllMatch() {
        return textMatches && imagesMatch && linksMatch && metadataMatches && dataLayerMatches;
    }

    public SiteData getSiteA() {
        return siteA;
    }

    public SiteData getSiteB() {
        return siteB;
    }

    public boolean isTextMatches() {
        return textMatches;
    }

    public int getTotalLinesA() {
        return totalLinesA;
    }

    public int getTotalLinesB() {
        return totalLinesB;
    }

    public int getMatchedLineCount() {
        return matchedLineCount;
    }

    public List<String> getTextOnlyInA() {
        return textOnlyInA;
    }

    public List<String> getTextOnlyInB() {
        return textOnlyInB;
    }

    public boolean isImagesMatch() {
        return imagesMatch;
    }

    public List<Integer> getMismatchedImageIndices() {
        return mismatchedImageIndices;
    }

    public int getMatchedImagesCount() {
        return matchedImagesCount;
    }

    public int getVisualMatchImagesCount() {
        return visualMatchImagesCount;
    }

    public boolean isLinksMatch() {
        return linksMatch;
    }

    public List<LinkData> getLinksOnlyInA() {
        return linksOnlyInA;
    }

    public List<LinkData> getLinksOnlyInB() {
        return linksOnlyInB;
    }

    public int getMatchedLinksCount() {
        return matchedLinksCount;
    }

    public boolean isMetadataMatches() {
        return metadataMatches;
    }

    public Map<String, String> getMetaOnlyInA() {
        return metaOnlyInA;
    }

    public Map<String, String> getMetaOnlyInB() {
        return metaOnlyInB;
    }

    public Map<String, String[]> getMetaValueDiffs() {
        return metaValueDiffs;
    }

    public boolean isDataLayerMatches() {
        return dataLayerMatches;
    }

    public Map<String, String> getDataLayerOnlyInA() {
        return dataLayerOnlyInA;
    }

    public Map<String, String> getDataLayerOnlyInB() {
        return dataLayerOnlyInB;
    }

    public Map<String, String[]> getDataLayerValueDiffs() {
        return dataLayerValueDiffs;
    }

    public long getComparisonTimeMillis() {
        return comparisonTimeMillis;
    }
}
