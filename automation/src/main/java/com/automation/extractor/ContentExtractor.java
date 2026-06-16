package com.automation.extractor;

import com.automation.model.ImageData;
import com.automation.model.LinkData;
import com.automation.model.SiteData;
import com.microsoft.playwright.*;

import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

public class ContentExtractor {

    public SiteData extractSiteData(String url, String label) {
        long startTime = System.currentTimeMillis();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();

            System.out.println("[INFO] Navigating to Site " + label + ": " + url);
            page.navigate(url);
            page.waitForLoadState();
            System.out.println("[INFO] Site " + label + " loaded.");

            System.out.println("[INFO] Extracting content from Site " + label + "...");
            String rawText = extractText(page);
            List<ImageData> images = extractImages(page, label);
            List<LinkData> links = extractLinks(page, label);
            Map<String, String> metadata = extractMetadata(page, label);
            String dataLayerJson = extractDataLayer(page, label);

            browser.close();

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[INFO] Site " + label + " extraction complete (" + elapsed + " ms).");
            return new SiteData(label, url, rawText, normalize(rawText), images, links, metadata, dataLayerJson,
                    elapsed);

        } catch (Exception e) {
            System.out.println("[ERROR] Site " + label + " failed: " + e.getMessage());
            return new SiteData(label, url, "", "", List.of(), List.of(), Map.of(), "NO_DATALAYER", 0);
        }
    }

    // -------------------------------------------------------
    // TEXT
    // -------------------------------------------------------
    private String extractText(Page page) {
        return (String) page.evaluate("""
                    () => {
                        document.querySelectorAll('script, style, noscript').forEach(el => el.remove());
                        return document.body ? document.body.innerText : '';
                    }
                """);
    }

    // -------------------------------------------------------
    // IMAGES — pixel-level via per-element screenshot
    // -------------------------------------------------------
    private List<ImageData> extractImages(Page page, String siteLabel) {
        List<ElementHandle> elements = page.querySelectorAll("img");
        List<ImageData> images = new ArrayList<>();
        int index = 0;

        for (ElementHandle img : elements) {
            try {
                String src = img.getAttribute("src");
                if (src == null || src.isBlank())
                    continue;
                img.scrollIntoViewIfNeeded();
                byte[] png = img.screenshot();
                String hash = md5(png);
                images.add(new ImageData(index, src, png, hash));
                index++;
            } catch (Exception e) {
                System.out.println("[WARN] Site " + siteLabel + " - skipped image " + index + ": " + e.getMessage());
            }
        }
        System.out.println("[INFO] Site " + siteLabel + " - captured " + images.size() + " image(s).");
        return images;
    }

    // -------------------------------------------------------
    // LINKS — collects hrefs from <a>, onclick, data-href, data-url
    // -------------------------------------------------------
    private List<LinkData> extractLinks(Page page, String siteLabel) {
        @SuppressWarnings("unchecked")
        List<String> hrefs = (List<String>) page.evaluate("""
                    () => {
                        const links = new Set();
                        document.querySelectorAll('a[href]').forEach(a => {
                            links.add(a.getAttribute('href'));
                        });
                        document.querySelectorAll('button[onclick]').forEach(btn => {
                            const onclick = btn.getAttribute('onclick');
                            const sq = onclick.split("'");
                            for (let i = 1; i < sq.length; i += 2) {
                                const v = sq[i].trim();
                                if (v.startsWith('/') || v.includes('/')) links.add(v);
                            }
                            const dq = onclick.split('"');
                            for (let i = 1; i < dq.length; i += 2) {
                                const v = dq[i].trim();
                                if (v.startsWith('/') || v.includes('/')) links.add(v);
                            }
                        });
                        document.querySelectorAll('[data-href]').forEach(el =>
                            links.add(el.getAttribute('data-href')));
                        document.querySelectorAll('[data-url]').forEach(el =>
                            links.add(el.getAttribute('data-url')));
                        return Array.from(links);
                    }
                """);

        List<LinkData> links = new ArrayList<>();
        for (String href : hrefs) {
            if (href == null || href.isBlank())
                continue;
            if (href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:"))
                continue;
            String slug = extractSlug(href);
            if (!slug.isBlank())
                links.add(new LinkData(href, slug));
        }

        List<LinkData> unique = links.stream().distinct().collect(Collectors.toList());
        System.out.println("[INFO] Site " + siteLabel + " - found " + unique.size() + " unique link slug(s).");
        return unique;
    }

    // -------------------------------------------------------
    // METADATA — <title>, <meta name>, <meta property> (OpenGraph, etc.)
    // -------------------------------------------------------
    private Map<String, String> extractMetadata(Page page, String siteLabel) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) page.evaluate(
                """
                            () => {
                                const meta = {};
                                const title = document.querySelector('title');
                                if (title) meta['title'] = title.textContent.trim();

                                document.querySelectorAll('meta').forEach(tag => {
                                    const name     = tag.getAttribute('name')     || tag.getAttribute('property') || tag.getAttribute('http-equiv');
                                    const content  = tag.getAttribute('content');
                                    const charset  = tag.getAttribute('charset');
                                    if (charset) {
                                        meta['charset'] = charset;
                                    } else if (name && content !== null) {
                                        meta[name] = content;
                                    }
                                });
                                return meta;
                            }
                        """);

        Map<String, String> metadata = new LinkedHashMap<>();
        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                metadata.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        System.out.println("[INFO] Site " + siteLabel + " - extracted " + metadata.size() + " metadata tag(s).");
        return metadata;
    }

    // -------------------------------------------------------
    // DATALAYER — reads window.dataLayer as a JSON string.
    // Compatible with Google Tag Manager / Google Analytics.
    // -------------------------------------------------------
    private String extractDataLayer(Page page, String siteLabel) {
        String result = (String) page.evaluate("""
                    () => {
                        try {
                            if (!window.dataLayer || !Array.isArray(window.dataLayer)) {
                                return 'NO_DATALAYER';
                            }
                            return JSON.stringify(window.dataLayer, null, 2);
                        } catch (e) {
                            return 'ERROR: ' + e.message;
                        }
                    }
                """);

        if ("NO_DATALAYER".equals(result)) {
            System.out.println("[INFO] Site " + siteLabel + " - no dataLayer found.");
        } else {
            System.out.println("[INFO] Site " + siteLabel + " - dataLayer extracted.");
        }
        return result;
    }

    // -------------------------------------------------------
    // SLUG EXTRACTION
    // -------------------------------------------------------
    private String extractSlug(String href) {
        Set<String> generic = new HashSet<>(Arrays.asList(
                "index", "index.html", "home", "default", "main", "..", "."));
        try {
            String path = href.startsWith("http")
                    ? new URI(href).getPath()
                    : href.split("\\?")[0];

            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (part.isEmpty())
                    continue;
                String slug = part.replaceAll("\\.[a-zA-Z0-9]+$", "");
                if (!slug.isBlank() && !generic.contains(slug.toLowerCase()))
                    return slug;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // -------------------------------------------------------
    // NORMALIZE TEXT
    // -------------------------------------------------------
    private String normalize(String raw) {
        return raw.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[^a-z0-9 ]", "")
                .trim();
    }

    // -------------------------------------------------------
    // MD5 HASH
    // -------------------------------------------------------
    private String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
