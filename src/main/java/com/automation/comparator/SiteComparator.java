package com.automation.comparator;

import com.automation.model.ComparisonResult;
import com.automation.model.ComparisonResult.*;
import com.automation.model.ImageData;
import com.automation.model.LinkData;
import com.automation.model.SiteData;

import java.util.*;
import java.util.stream.Collectors;

public class SiteComparator {

    public ComparisonResult compare(SiteData a, SiteData b) {
        long startTime = System.currentTimeMillis();

        // TEXT
        List<String> linesA = splitLines(a.getRawText());
        List<String> linesB = splitLines(b.getRawText());

        Set<String> setA = new LinkedHashSet<>(linesA);
        Set<String> setB = new LinkedHashSet<>(linesB);

        List<String> textOnlyInA = setA.stream().filter(l -> !setB.contains(l)).collect(Collectors.toList());
        List<String> textOnlyInB = setB.stream().filter(l -> !setA.contains(l)).collect(Collectors.toList());
        int matchedLineCount = (int) setA.stream().filter(setB::contains).count();
        boolean textMatches = textOnlyInA.isEmpty() && textOnlyInB.isEmpty();
        TextDiff textDiff = new TextDiff(textMatches, linesA.size(), linesB.size(), matchedLineCount, textOnlyInA, textOnlyInB);

        // IMAGES
        List<ImageData> imagesA = a.getImages();
        List<ImageData> imagesB = b.getImages();
        
        List<ImageMatch> matchesList = new ArrayList<>();
        int matchedImagesCount = 0;
        int visualMatchImagesCount = 0;
        int mismatchCount = 0;

        List<ImageData> unmatchedB = new ArrayList<>(imagesB);
        for (ImageData imgA : imagesA) {
            String slugA = extractFilename(imgA.getSrc());
            ImageData matchB = null;
            if (!slugA.isEmpty()) {
                for (ImageData imgB : unmatchedB) {
                    if (extractFilename(imgB.getSrc()).equals(slugA)) {
                        matchB = imgB;
                        break;
                    }
                }
            }
            if (matchB == null && !unmatchedB.isEmpty()) {
                matchB = unmatchedB.get(0);
            }

            if (matchB != null) {
                unmatchedB.remove(matchB);
                boolean hashMatch = imgA.getHash().equals(matchB.getHash());
                if (hashMatch) {
                    matchedImagesCount++;
                    matchesList.add(new ImageMatch(imgA, matchB, "MATCH"));
                } else {
                    String phA = imgA.getPerceptualHash();
                    String phB = matchB.getPerceptualHash();
                    boolean perceptualMatch = !"phash-error".equals(phA) && !"phash-error".equals(phB) && phA.equals(phB);
                    if (perceptualMatch) {
                        matchedImagesCount++;
                        visualMatchImagesCount++;
                        matchesList.add(new ImageMatch(imgA, matchB, "VISUAL MATCH"));
                    } else {
                        mismatchCount++;
                        boolean srcMatch = imgA.getSrc().equals(matchB.getSrc());
                        matchesList.add(new ImageMatch(imgA, matchB, srcMatch ? "HASH DIFF" : "DIFF"));
                    }
                }
            } else {
                mismatchCount++;
                matchesList.add(new ImageMatch(imgA, null, "MISSING IN B"));
            }
        }
        for (ImageData imgB : unmatchedB) {
            mismatchCount++;
            matchesList.add(new ImageMatch(null, imgB, "MISSING IN A"));
        }
        boolean imagesMatch = mismatchCount == 0;
        ImageDiff imageDiff = new ImageDiff(imagesMatch, matchesList, matchedImagesCount, visualMatchImagesCount, mismatchCount);

        // LINKS
        List<LinkData> linksOnlyInA = new ArrayList<>(a.getLinks()); linksOnlyInA.removeAll(b.getLinks());
        List<LinkData> linksOnlyInB = new ArrayList<>(b.getLinks()); linksOnlyInB.removeAll(a.getLinks());
        List<LinkData> commonLinks  = new ArrayList<>(a.getLinks()); commonLinks.retainAll(b.getLinks());
        boolean linksMatch = linksOnlyInA.isEmpty() && linksOnlyInB.isEmpty();
        LinkDiff linkDiff = new LinkDiff(linksMatch, linksOnlyInA, linksOnlyInB, commonLinks.size());

        // METADATA
        MapDiff metaDiff = compareMaps(a.getMetadata(), b.getMetadata());
        MapDiff dlDiff = compareMaps(a.getDataLayer(), b.getDataLayer());

        long elapsed = System.currentTimeMillis() - startTime;

        return new ComparisonResult(a, b, textDiff, imageDiff, linkDiff, metaDiff, dlDiff, elapsed);
    }

    private MapDiff compareMaps(Map<String, String> mapA, Map<String, String> mapB) {
        Map<String, String> onlyInA = new LinkedHashMap<>();
        Map<String, String> onlyInB = new LinkedHashMap<>();
        Map<String, String[]> valueDiffs = new LinkedHashMap<>();

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(mapA.keySet());
        allKeys.addAll(mapB.keySet());

        for (String key : allKeys) {
            boolean inA = mapA.containsKey(key);
            boolean inB = mapB.containsKey(key);
            if (inA && !inB) {
                onlyInA.put(key, mapA.get(key));
            } else if (!inA && inB) {
                onlyInB.put(key, mapB.get(key));
            } else if (!mapA.get(key).equals(mapB.get(key))) {
                valueDiffs.put(key, new String[]{mapA.get(key), mapB.get(key)});
            }
        }
        boolean matches = onlyInA.isEmpty() && onlyInB.isEmpty() && valueDiffs.isEmpty();
        return new MapDiff(matches, onlyInA, onlyInB, valueDiffs);
    }

    private List<String> splitLines(String raw) {
        return Arrays.stream(raw.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private String extractFilename(String src) {
        if (src == null || src.isBlank()) return "";
        try {
            String path = src.startsWith("http") ? new java.net.URI(src).getPath() : src.split("\\?")[0];
            String[] parts = path.split("/");
            if (parts.length > 0) return parts[parts.length - 1];
        } catch (Exception e) {}
        return "";
    }
}
