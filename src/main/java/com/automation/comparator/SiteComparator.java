package com.automation.comparator;

import com.automation.model.ComparisonResult;
import com.automation.model.ComparisonResult.*;
import com.automation.model.ImageData;
import com.automation.model.LinkData;
import com.automation.model.SiteData;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
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
        // Link Normalization: compare relative paths instead of absolute
        Map<String, LinkData> normLinksA = new LinkedHashMap<>();
        Map<String, LinkData> normLinksB = new LinkedHashMap<>();
        
        for (LinkData ld : a.getLinks()) normLinksA.put(normalizeLinkPath(ld.getOriginalHref()), ld);
        for (LinkData ld : b.getLinks()) normLinksB.put(normalizeLinkPath(ld.getOriginalHref()), ld);

        List<LinkData> linksOnlyInA = new ArrayList<>();
        List<LinkData> linksOnlyInB = new ArrayList<>();
        List<LinkData> commonLinks = new ArrayList<>();

        for (String pathA : normLinksA.keySet()) {
            if (normLinksB.containsKey(pathA)) {
                commonLinks.add(normLinksA.get(pathA));
            } else {
                linksOnlyInA.add(normLinksA.get(pathA));
            }
        }
        for (String pathB : normLinksB.keySet()) {
            if (!normLinksA.containsKey(pathB)) {
                linksOnlyInB.add(normLinksB.get(pathB));
            }
        }

        boolean linksMatch = linksOnlyInA.isEmpty() && linksOnlyInB.isEmpty();
        LinkDiff linkDiff = new LinkDiff(linksMatch, linksOnlyInA, linksOnlyInB, commonLinks.size());

        // METADATA
        MapDiff metaDiff = compareMaps(a.getMetadata(), b.getMetadata());
        MapDiff dlDiff = compareMaps(a.getDataLayer(), b.getDataLayer());

        // HEATMAP
        byte[] heatmap = generateHeatmap(a.getFullPageScreenshot(), b.getFullPageScreenshot());

        long elapsed = System.currentTimeMillis() - startTime;

        return new ComparisonResult(a, b, textDiff, imageDiff, linkDiff, metaDiff, dlDiff, elapsed, heatmap);
    }

    private byte[] generateHeatmap(byte[] imgA, byte[] imgB) {
        if (imgA == null || imgA.length == 0 || imgB == null || imgB.length == 0) return new byte[0];
        try {
            BufferedImage biA = ImageIO.read(new ByteArrayInputStream(imgA));
            BufferedImage biB = ImageIO.read(new ByteArrayInputStream(imgB));
            if (biA == null || biB == null) return new byte[0];

            int width = Math.max(biA.getWidth(), biB.getWidth());
            int height = Math.max(biA.getHeight(), biB.getHeight());

            BufferedImage diffImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int red = new Color(255, 0, 0, 150).getRGB();
            int highlight = new Color(255, 0, 0, 255).getRGB();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean inA = x < biA.getWidth() && y < biA.getHeight();
                    boolean inB = x < biB.getWidth() && y < biB.getHeight();
                    if (inA && inB) {
                        int rgbA = biA.getRGB(x, y);
                        int rgbB = biB.getRGB(x, y);
                        if (rgbA == rgbB) {
                            diffImg.setRGB(x, y, fade(rgbA));
                        } else {
                            diffImg.setRGB(x, y, highlight);
                        }
                    } else if (inA) {
                        diffImg.setRGB(x, y, highlight);
                    } else if (inB) {
                        diffImg.setRGB(x, y, highlight);
                    } else {
                        diffImg.setRGB(x, y, Color.WHITE.getRGB());
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(diffImg, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private int fade(int rgb) {
        Color c = new Color(rgb, true);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 80).getRGB();
    }

    private String normalizeLinkPath(String href) {
        if (href == null) return "";
        try {
            if (href.startsWith("http")) {
                URI uri = new URI(href);
                String path = uri.getPath();
                String query = uri.getQuery();
                return (path == null ? "" : path) + (query == null ? "" : "?" + query);
            }
        } catch (Exception ignored) {}
        return href;
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
            } else if (!Objects.equals(mapA.get(key), mapB.get(key))) { // Fix: NPE Risk
                valueDiffs.put(key, new String[]{mapA.get(key), mapB.get(key)});
            }
        }
        boolean matches = onlyInA.isEmpty() && onlyInB.isEmpty() && valueDiffs.isEmpty();
        return new MapDiff(matches, onlyInA, onlyInB, valueDiffs);
    }

    private List<String> splitLines(String raw) {
        return Arrays.stream(raw.split("\\n"))
                .map(s -> s.replace("\u00A0", " ").trim()) // Fix: Non-Breaking Spaces
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
