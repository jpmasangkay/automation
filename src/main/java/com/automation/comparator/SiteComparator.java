package com.automation.comparator;

import com.automation.comparator.rules.CheckResult;
import com.automation.comparator.rules.RuleRegistry;
import com.automation.model.ComparisonResult;
import com.automation.model.ComparisonResult.*;
import com.automation.model.ImageData;
import com.automation.model.LinkData;
import com.automation.model.SimilarityScores;
import com.automation.model.SiteData;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class SiteComparator {

    public ComparisonResult compare(SiteData a, SiteData b) {
        long startTime = System.currentTimeMillis();

        // ── TEXT ─────────────────────────────────────────────────────────────
        List<String> linesA = splitLines(a.getRawText());
        List<String> linesB = splitLines(b.getRawText());

        Set<String> setA = new LinkedHashSet<>(linesA);
        Set<String> setB = new LinkedHashSet<>(linesB);

        List<String> textOnlyInA = setA.stream().filter(l -> !setB.contains(l)).collect(Collectors.toList());
        List<String> textOnlyInB = setB.stream().filter(l -> !setA.contains(l)).collect(Collectors.toList());
        int matchedLineCount = (int) setA.stream().filter(setB::contains).count();
        boolean textMatches = textOnlyInA.isEmpty() && textOnlyInB.isEmpty();
        TextDiff textDiff = new TextDiff(textMatches, linesA.size(), linesB.size(), matchedLineCount, textOnlyInA,
                textOnlyInB);

        // Text similarity: Jaccard = |A ∩ B| / |A ∪ B|
        int textUnion = setA.size() + setB.size() - matchedLineCount;
        double textScore = textUnion == 0 ? 100.0 : (matchedLineCount * 100.0 / textUnion);

        // ── IMAGES ───────────────────────────────────────────────────────────
        List<ImageData> imagesA = a.getImages();
        List<ImageData> imagesB = b.getImages();

        List<ImageMatch> matchesList = new ArrayList<>();
        int matchedImagesCount = 0;
        int visualMatchImagesCount = 0;
        int mismatchCount = 0;

        // Keep a list of all images in B that are available to be matched
        List<ImageData> availableB = new ArrayList<>(imagesB);

        for (ImageData imgA : imagesA) {
            ImageData matchB = null;

            // 1. First try exact MD5 hash match
            for (ImageData imgB : availableB) {
                if (imgA.getHash().equals(imgB.getHash())) {
                    matchB = imgB;
                    break;
                }
            }

            // 2. If no hash match, try filename slug match
            if (matchB == null) {
                String slugA = extractFilename(imgA.getSrc());
                if (!slugA.isEmpty()) {
                    for (ImageData imgB : availableB) {
                        String slugB = extractFilename(imgB.getSrc());
                        if (slugA.equals(slugB)) {
                            matchB = imgB;
                            break;
                        }
                    }
                }
            }

            if (matchB != null) {
                availableB.remove(matchB);
                boolean hashMatch = imgA.getHash().equals(matchB.getHash());
                if (hashMatch) {
                    matchedImagesCount++;
                    matchesList.add(new ImageMatch(imgA, matchB, "MATCH"));
                } else {
                    String phA = imgA.getPerceptualHash();
                    String phB = matchB.getPerceptualHash();
                    boolean perceptualMatch = !"phash-error".equals(phA) && !"phash-error".equals(phB)
                            && phA.equals(phB);
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

        // Remaining images in availableB are missing in A
        for (ImageData imgB : availableB) {
            mismatchCount++;
            matchesList.add(new ImageMatch(null, imgB, "MISSING IN A"));
        }

        boolean imagesMatch = mismatchCount == 0;
        ImageDiff imageDiff = new ImageDiff(imagesMatch, matchesList, matchedImagesCount, visualMatchImagesCount,
                mismatchCount);

        // Image similarity: matched / max(|A|, |B|)
        int maxImages = Math.max(imagesA.size(), imagesB.size());
        double imageScore = maxImages == 0 ? 100.0 : (matchedImagesCount * 100.0 / maxImages);

        // ── LINKS ─────────────────────────────────────────────────────────────
        Map<String, LinkData> normLinksA = new LinkedHashMap<>();
        Map<String, LinkData> normLinksB = new LinkedHashMap<>();

        for (LinkData ld : a.getLinks())
            normLinksA.put(normalizeLinkPath(ld.getOriginalHref()), ld);
        for (LinkData ld : b.getLinks())
            normLinksB.put(normalizeLinkPath(ld.getOriginalHref()), ld);

        List<LinkData> linksOnlyInA = new ArrayList<>();
        List<LinkData> linksOnlyInB = new ArrayList<>();
        List<LinkData> commonLinks = new ArrayList<>();

        for (String pathA : normLinksA.keySet()) {
            if (normLinksB.containsKey(pathA))
                commonLinks.add(normLinksA.get(pathA));
            else
                linksOnlyInA.add(normLinksA.get(pathA));
        }
        for (String pathB : normLinksB.keySet()) {
            if (!normLinksA.containsKey(pathB))
                linksOnlyInB.add(normLinksB.get(pathB));
        }

        boolean linksMatch = linksOnlyInA.isEmpty() && linksOnlyInB.isEmpty();
        LinkDiff linkDiff = new LinkDiff(linksMatch, linksOnlyInA, linksOnlyInB, commonLinks.size());

        // Link similarity: Jaccard
        int linkUnion = normLinksA.size() + normLinksB.size() - commonLinks.size();
        double linkScore = linkUnion == 0 ? 100.0 : (commonLinks.size() * 100.0 / linkUnion);

        // ── METADATA & DATALAYER ───────────────────────────────────────────────
        MapDiff metaDiff = compareMaps(a.getMetadata(), b.getMetadata());
        MapDiff dlDiff = compareMaps(a.getDataLayer(), b.getDataLayer());
        MapDiff funcDiff = compareMaps(a.getFunctionalityComponents(), b.getFunctionalityComponents());

        double metaScore = computeMapScore(a.getMetadata(), b.getMetadata(), metaDiff);
        double dlScore = computeMapScore(a.getDataLayer(), b.getDataLayer(), dlDiff);

        // ── SIMILARITY SCORES ─────────────────────────────────────────────────
        double overall = (textScore + imageScore + linkScore + metaScore + dlScore) / 5.0;
        SimilarityScores scores = new SimilarityScores(textScore, imageScore, linkScore, metaScore, dlScore, overall);

        // ── PLUGIN RULES ──────────────────────────────────────────────────────
        List<CheckResult> additionalResults = new ArrayList<>();
        for (var rule : RuleRegistry.getInstance().getRules()) {
            try {
                additionalResults.add(rule.apply(a, b));
            } catch (Exception e) {
                additionalResults.add(new CheckResult(rule.getName(), false, 0.0,
                        "error", "error", List.of("Rule threw exception: " + e.getMessage())));
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new ComparisonResult(a, b, textDiff, imageDiff, linkDiff, metaDiff, dlDiff, funcDiff,
                elapsed, scores, additionalResults);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double computeMapScore(Map<String, String> mapA, Map<String, String> mapB, MapDiff diff) {
        int totalUnion = mapA.size() + diff.onlyInB().size(); // = |A| + keys only in B
        if (totalUnion == 0)
            return 100.0;
        int mismatches = diff.onlyInA().size() + diff.onlyInB().size() + diff.valueDiffs().size();
        int matched = totalUnion - mismatches;
        return Math.max(0.0, matched * 100.0 / totalUnion);
    }

    private String normalizeLinkPath(String href) {
        if (href == null)
            return "";
        try {
            if (href.startsWith("http")) {
                URI uri = new URI(href);
                String path = uri.getPath();
                String query = uri.getQuery();
                String norm = (path == null ? "" : path) + (query == null ? "" : "?" + query);
                // treat /en/ and /en as the same path
                if (norm.endsWith("/") && norm.length() > 1)
                    norm = norm.substring(0, norm.length() - 1);
                return norm;
            }
        } catch (Exception ignored) {
        }
        String result = href;
        if (result.endsWith("/") && result.length() > 1)
            result = result.substring(0, result.length() - 1);
        return result;
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
            } else if (!Objects.equals(mapA.get(key), mapB.get(key))) {
                valueDiffs.put(key, new String[] { mapA.get(key), mapB.get(key) });
            }
        }
        boolean matches = onlyInA.isEmpty() && onlyInB.isEmpty() && valueDiffs.isEmpty();
        return new MapDiff(matches, onlyInA, onlyInB, valueDiffs);
    }

    private List<String> splitLines(String raw) {
        return Arrays.stream(raw.split("\\n"))
                .map(s -> s.replace("\u00A0", " ").trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String extractFilename(String src) {
        if (src == null || src.isBlank())
            return "";
        try {
            String path = src.startsWith("http") ? new java.net.URI(src).getPath() : src.split("\\?")[0];
            String[] parts = path.split("/");
            if (parts.length > 0)
                return parts[parts.length - 1];
        } catch (Exception ignored) {
        }
        return "";
    }
}
