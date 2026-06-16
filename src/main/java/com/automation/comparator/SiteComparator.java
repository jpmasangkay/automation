package com.automation.comparator;

import com.automation.model.ComparisonResult;
import com.automation.model.LinkData;
import com.automation.model.SiteData;

import java.util.*;
import java.util.stream.Collectors;

public class SiteComparator {

    // Date/time metadata keys excluded from comparison.
    private static final Set<String> IGNORED_META_KEYS = new HashSet<>(Arrays.asList(
            "created", "date", "last-modified", "revised", "expires",
            "article:published_time", "article:modified_time"
    ));

    public ComparisonResult compare(SiteData a, SiteData b) {
        long startTime = System.currentTimeMillis();

        // TEXT — line-level diff on raw text
        List<String> linesA = splitLines(a.getRawText());
        List<String> linesB = splitLines(b.getRawText());

        Set<String> setA = new LinkedHashSet<>(linesA);
        Set<String> setB = new LinkedHashSet<>(linesB);

        List<String> textOnlyInA = setA.stream().filter(l -> !setB.contains(l)).collect(Collectors.toList());
        List<String> textOnlyInB = setB.stream().filter(l -> !setA.contains(l)).collect(Collectors.toList());
        int matchedLineCount = (int) setA.stream().filter(setB::contains).count();
        boolean textMatches = textOnlyInA.isEmpty() && textOnlyInB.isEmpty();

        // IMAGES
        int totalComparable = Math.min(a.getImages().size(), b.getImages().size());
        int matchedImagesCount = 0;
        List<Integer> mismatchedImageIndices = new ArrayList<>();

        for (int i = 0; i < totalComparable; i++) {
            if (a.getImages().get(i).getHash().equals(b.getImages().get(i).getHash())) {
                matchedImagesCount++;
            } else {
                mismatchedImageIndices.add(i);
            }
        }
        boolean imagesMatch = mismatchedImageIndices.isEmpty()
                && a.getImages().size() == b.getImages().size();

        // LINKS
        List<LinkData> linksOnlyInA = new ArrayList<>(a.getLinks()); linksOnlyInA.removeAll(b.getLinks());
        List<LinkData> linksOnlyInB = new ArrayList<>(b.getLinks()); linksOnlyInB.removeAll(a.getLinks());
        List<LinkData> commonLinks  = new ArrayList<>(a.getLinks()); commonLinks.retainAll(b.getLinks());
        boolean linksMatch = linksOnlyInA.isEmpty() && linksOnlyInB.isEmpty();

        // METADATA
        Map<String, String> metaA = a.getMetadata();
        Map<String, String> metaB = b.getMetadata();

        Map<String, String> metaOnlyInA    = new LinkedHashMap<>();
        Map<String, String> metaOnlyInB    = new LinkedHashMap<>();
        Map<String, String[]> metaValueDiffs = new LinkedHashMap<>();

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(metaA.keySet());
        allKeys.addAll(metaB.keySet());

        for (String key : allKeys) {
            if (IGNORED_META_KEYS.contains(key.toLowerCase())) continue;
            boolean inA = metaA.containsKey(key);
            boolean inB = metaB.containsKey(key);
            if (inA && !inB) {
                metaOnlyInA.put(key, metaA.get(key));
            } else if (!inA && inB) {
                metaOnlyInB.put(key, metaB.get(key));
            } else if (!metaA.get(key).equals(metaB.get(key))) {
                metaValueDiffs.put(key, new String[]{metaA.get(key), metaB.get(key)});
            }
        }
        boolean metadataMatches = metaOnlyInA.isEmpty() && metaOnlyInB.isEmpty() && metaValueDiffs.isEmpty();

        // DATALAYER
        String dlA = a.getDataLayerJson();
        String dlB = b.getDataLayerJson();
        boolean dataLayerMatches = dlA.equals(dlB);

        long elapsed = System.currentTimeMillis() - startTime;

        return new ComparisonResult(
                a, b,
                textMatches, linesA.size(), linesB.size(), matchedLineCount, textOnlyInA, textOnlyInB,
                imagesMatch, mismatchedImageIndices, matchedImagesCount,
                linksMatch, linksOnlyInA, linksOnlyInB, commonLinks.size(),
                metadataMatches, metaOnlyInA, metaOnlyInB, metaValueDiffs,
                dataLayerMatches, dlA, dlB,
                elapsed
        );
    }

    private List<String> splitLines(String raw) {
        return Arrays.stream(raw.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }
}
