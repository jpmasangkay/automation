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
            cleanDom(page);
            String rawText = extractText(page);
            List<ImageData> images = extractImages(page, label);
            List<LinkData> links = extractLinks(page, label);
            Map<String, String> metadata = extractMetadata(page, label);
            Map<String, String> dataLayer = extractDataLayer(page, label);

            browser.close();

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[INFO] Site " + label + " extraction complete (" + elapsed + " ms).");
            return new SiteData(label, url, rawText, normalize(rawText), images, links, metadata, dataLayer,
                    elapsed);

        } catch (Exception e) {
            System.out.println("[ERROR] Site " + label + " failed: " + e.getMessage());
            return new SiteData(label, url, "", "", List.of(), List.of(), Map.of(), Map.of(), 0);
        }
    }

    // -------------------------------------------------------
    // TEXT
    // -------------------------------------------------------
    private void cleanDom(Page page) {
        page.evaluate("""
                    () => {
                        // 1. Remove non-visible elements
                        document.querySelectorAll('script, style, noscript').forEach(el => el.remove());

                        // 2. Remove standard page headers, footers, and navigation bars
                        const selectors = [
                            'header', 'footer', 'nav',
                            '#header', '#footer', '#navigation', '#navbar',
                            '.header', '.footer', '.navigation', '.navigation__sticky',
                            '.page-header', '.page-footer',
                            '.site-header', '.site-footer',
                            '.navbar', '.nav-container'
                        ];
                        selectors.forEach(sel => {
                            document.querySelectorAll(sel).forEach(el => el.remove());
                        });
                    }
                """);
    }

    private String extractText(Page page) {
        return (String) page.evaluate("""
                    () => {
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
                page.waitForTimeout(300);
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
                                
                                // 1. Title
                                const titleTag = document.querySelector('title');
                                let titleVal = titleTag ? titleTag.textContent.trim() : null;
                                if (!titleVal) {
                                    const ogTitle = document.querySelector('meta[property="og:title"]');
                                    if (ogTitle) titleVal = ogTitle.getAttribute('content');
                                }
                                if (!titleVal) {
                                    const metaTitle = document.querySelector('meta[name="title"]');
                                    if (metaTitle) titleVal = metaTitle.getAttribute('content');
                                }
                                if (titleVal !== null) {
                                    meta['Title'] = titleVal.trim();
                                }

                                // 2. Description
                                let descVal = null;
                                const metaDesc = document.querySelector('meta[name="description"]');
                                if (metaDesc) descVal = metaDesc.getAttribute('content');
                                if (!descVal) {
                                    const ogDesc = document.querySelector('meta[property="og:description"]');
                                    if (ogDesc) descVal = ogDesc.getAttribute('content');
                                }
                                if (descVal !== null) {
                                    meta['Description'] = descVal.trim();
                                }

                                // 3. Keywords
                                const metaKw = document.querySelector('meta[name="keywords"]');
                                if (metaKw) {
                                    const kwVal = metaKw.getAttribute('content');
                                    if (kwVal !== null) {
                                        meta['Keywords'] = kwVal.trim();
                                    }
                                }


                                // 5. Author
                                let authorVal = null;
                                const metaAuthor = document.querySelector('meta[name="author"]');
                                if (metaAuthor) authorVal = metaAuthor.getAttribute('content');
                                if (!authorVal) {
                                    const ogAuthor = document.querySelector('meta[property="og:author"]') || document.querySelector('meta[property="article:author"]');
                                    if (ogAuthor) authorVal = ogAuthor.getAttribute('content');
                                }
                                if (authorVal !== null) {
                                    meta['Author'] = authorVal.trim();
                                }

                                // 6. Publisher
                                let publisherVal = null;
                                const metaPublisher = document.querySelector('meta[name="publisher"]');
                                if (metaPublisher) publisherVal = metaPublisher.getAttribute('content');
                                if (!publisherVal) {
                                    const ogPublisher = document.querySelector('meta[property="og:publisher"]') || document.querySelector('meta[property="article:publisher"]');
                                    if (ogPublisher) publisherVal = ogPublisher.getAttribute('content');
                                }
                                if (publisherVal !== null) {
                                    meta['Publisher'] = publisherVal.trim();
                                }

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
    private Map<String, String> extractDataLayer(Page page, String siteLabel) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) page.evaluate(
                """
                            () => {
                                const result = {};
                                const targetMapping = {
                                  'sitetype': 'Site Type',
                                  'brandwebsitetype': 'Brand Website Type',
                                  'globalbrandr360': 'Global Brand - R360',
                                  'globalbrand': 'Global Brand - R360',
                                  'gbu': 'GBU',
                                  'region': 'Region',
                                  'country': 'Country',
                                  'targetenus': 'Target (en_US)',
                                  'target': 'Target (en_US)',
                                  'targetaudience': 'Target (en_US)',
                                  'targetpersona': 'Target (en_US)',
                                  'franchiser360': 'Franchise - R360',
                                  'franchise': 'Franchise - R360',
                                  'therapeuticearear360': 'Therapeutic Area - R360',
                                  'therapeuticarear360': 'Therapeutic Area - R360',
                                  'therapeuticarea': 'Therapeutic Area - R360',
                                  'indicationr360': 'Indication - R360',
                                  'indication': 'Indication - R360',
                                  'specialty': 'Specialty',
                                  'customfield1value': 'Custom Field 1 Value',
                                  'customfield1': 'Custom Field 1 Value',
                                  'customfield2value': 'Custom Field 2 Value',
                                  'customfield2': 'Custom Field 2 Value',
                                  'customfield3value': 'Custom Field 3 Value',
                                  'customfield3': 'Custom Field 3 Value',
                                  'customfield4value': 'Custom Field 4 Value',
                                  'customfield4': 'Custom Field 4 Value',
                                  'customfield5value': 'Custom Field 5 Value',
                                  'customfield5': 'Custom Field 5 Value'
                                };

                                const normalizeKey = (key) => key.toLowerCase().replace(/[^a-z0-9]/g, '');

                                // 1. Scan window.dataLayer
                                if (window.dataLayer && Array.isArray(window.dataLayer)) {
                                    window.dataLayer.forEach(item => {
                                        if (item && typeof item === 'object') {
                                            Object.keys(item).forEach(k => {
                                                const nk = normalizeKey(k);
                                                if (targetMapping[nk]) {
                                                    result[targetMapping[nk]] = String(item[k]).trim();
                                                }
                                            });
                                        }
                                    });
                                }

                                // 2. Scan window.digitalData / siteData / siteDataLayer
                                const checkObjectRecursively = (obj, depth = 0) => {
                                    if (!obj || typeof obj !== 'object' || depth > 5) return;
                                    Object.keys(obj).forEach(k => {
                                        const nk = normalizeKey(k);
                                        if (targetMapping[nk]) {
                                            result[targetMapping[nk]] = String(obj[k]).trim();
                                        } else if (obj[k] && typeof obj[k] === 'object') {
                                            checkObjectRecursively(obj[k], depth + 1);
                                        }
                                    });
                                };
                                if (window.digitalData) checkObjectRecursively(window.digitalData);
                                if (window.siteData) checkObjectRecursively(window.siteData);
                                if (window.siteDataLayer) checkObjectRecursively(window.siteDataLayer);

                                // 3. Scan meta tags
                                document.querySelectorAll('meta').forEach(tag => {
                                    const name = tag.getAttribute('name') || tag.getAttribute('property') || tag.getAttribute('http-equiv');
                                    const content = tag.getAttribute('content');
                                    if (name && content !== null) {
                                        const nk = normalizeKey(name);
                                        if (targetMapping[nk]) {
                                            result[targetMapping[nk]] = content.trim();
                                        }
                                    }
                                });

                                return result;
                            }
                        """);

        Map<String, String> dataLayer = new LinkedHashMap<>();
        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                dataLayer.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        System.out.println("[INFO] Site " + siteLabel + " - extracted " + dataLayer.size() + " data layer property(ies).");
        return dataLayer;
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
