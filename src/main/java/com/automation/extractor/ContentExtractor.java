package com.automation.extractor;

import com.automation.model.ImageData;
import com.automation.model.LinkData;
import com.automation.model.SiteData;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

public class ContentExtractor {

    private final Browser browser;
    private static final Set<String> GENERIC_SLUGS = new HashSet<>(Arrays.asList(
            "index", "index.html", "home", "default", "main", "..", "."
    ));

    public ContentExtractor(Browser browser) {
        this.browser = browser;
    }

    public SiteData extractSiteData(String url, String label) {
        long startTime = System.currentTimeMillis();

        try {
            APIResponse response = browser.newContext().request().head(url, 
                com.microsoft.playwright.options.RequestOptions.create().setTimeout(10000));
            if (!response.ok() && response.status() != 405 && response.status() != 403) {
                System.out.println("[WARN] Site " + label + " returned HTTP " + response.status() + " on HEAD pre-check. Proceeding anyway...");
            }
        } catch (Exception e) {
            System.out.println("[WARN] Site " + label + " HTTP pre-check failed: " + e.getMessage() + ". Proceeding anyway...");
        }

        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Page page = browser.newPage();

                // Block third-party popups, surveys, and cookie banners at the network level
                page.route("**/*qualtrics.com**", Route::abort);
                page.route("**/*cookielaw.org**", Route::abort);
                page.route("**/*onetrust.com**", Route::abort);
                page.route("**/*tealiumiq.com**", Route::abort);
                page.route("**/*google-analytics.com**", Route::abort);

                System.out.println("[INFO] Navigating to Site " + label + " (Attempt " + attempt + "/" + maxRetries + "): " + url);
                page.navigate(url, new Page.NavigateOptions().setTimeout(60000));
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(30000));
                System.out.println("[INFO] Site " + label + " loaded.");

                // Scroll the full page to force all lazy-loaded components (images, feeds) to resolve
                try {
                    page.evaluate("() => new Promise(resolve => {" +
                        "let pos = 0;" +
                        "const step = () => {" +
                        "  window.scrollBy(0, 400);" +
                        "  pos += 400;" +
                        "  if (pos < document.body.scrollHeight && pos < 20000) { setTimeout(step, 80); }" +
                        "  else { window.scrollTo(0, 0); setTimeout(resolve, 500); }" +
                        "}; step();" +
                    "})");
                } catch (Exception e) {
                    System.out.println("[WARN] Page scroll failed: " + e.getMessage());
                }

                // Create a dedicated helper page for perceptual hashing (64x64, shared across all images)
                Page helperPage = browser.newPage();
                helperPage.setViewportSize(64, 64);
                helperPage.setContent("<html><body style='margin:0;padding:0;width:64px;height:64px;overflow:hidden;background:#fff;'>" +
                        "<img id='ph' style='width:64px;height:64px;display:block;object-fit:fill;' /></body></html>");
                helperPage.waitForLoadState();

                System.out.println("[INFO] Extracting content from Site " + label + "...");
                cleanDom(page);
                String rawText = extractText(page);
                List<ImageData> images = extractImages(page, helperPage, label);
                List<LinkData> links = extractLinks(page, label);
                Map<String, String> metadata = extractMetadata(page, label);
                Map<String, String> dataLayer = extractDataLayer(page, label);

                page.close();
                helperPage.close();

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("[INFO] Site " + label + " extraction complete (" + elapsed + " ms).");
                return new SiteData(label, url, rawText, images, links, metadata, dataLayer, elapsed);

            } catch (Exception e) {
                System.out.println("[WARN] Site " + label + " attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == maxRetries) {
                    System.out.println("[ERROR] Site " + label + " failed after " + maxRetries + " attempts.");
                    return new SiteData(label, url, "", List.of(), List.of(), Map.of(), Map.of(), 0);
                }
            }
        }
        return new SiteData(label, url, "", List.of(), List.of(), Map.of(), Map.of(), 0);
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
                            '.navbar', '.nav-container',
                            '#onetrust-consent-sdk', '.cookie-banner', '#cookie-law-info-bar' // common cookie banners
                        ];
                        selectors.forEach(sel => {
                            document.querySelectorAll(sel).forEach(el => el.remove());
                        });

                        // 3. Freeze all animations and transitions to prevent screenshot flakiness
                        const style = document.createElement('style');
                        style.innerHTML = `
                            * {
                                transition: none !important;
                                animation: none !important;
                                scroll-behavior: auto !important;
                            }
                        `;
                        document.head.appendChild(style);
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
    // IMAGES — deterministic via full-page load + binary download + perceptual hash
    // -------------------------------------------------------
    private List<ImageData> extractImages(Page page, Page helperPage, String siteLabel) {
        // Step 1: Wait for every img on the page to reach a 'complete' state
        try {
            page.evaluate("() => Promise.all(" +
                "  Array.from(document.images)" +
                "    .filter(img => !img.complete)" +
                "    .map(img => new Promise(resolve => {" +
                "      img.onload = resolve;" +
                "      img.onerror = resolve;" +
                "    }))" +
                ")");
        } catch (Exception e) {
            System.out.println("[WARN] Image load wait failed: " + e.getMessage());
        }
        page.waitForTimeout(500);

        // Step 3: Collect all resolved (currentSrc) image URLs from the page
        @SuppressWarnings("unchecked")
        List<Map<String, String>> imgInfoList = (List<Map<String, String>>) page.evaluate(
            "() => Array.from(document.images)" +
            "  .map(img => ({" +
            "    src: img.getAttribute('src') || ''," +
            "    currentSrc: img.currentSrc || img.src || ''" +
            "  }))" +
            "  .filter(info => {" +
            "    const u = info.currentSrc.toLowerCase();" +
            "    return u.startsWith('http') &&" +
            "           !u.includes('placeholder') &&" +
            "           !u.includes('blank.gif') &&" +
            "           !u.includes('spacer') &&" +
            "           !u.includes('cookielaw') &&" +
            "           !u.includes('qualtrics') &&" +
            "           !u.includes('data:image');" +
            "  })"
        );

        List<ImageData> images = new ArrayList<>();
        int index = 0;
        for (Map<String, String> info : imgInfoList) {
            String src = info.get("src");
            String currentSrc = info.get("currentSrc");
            try {
                // Primary: download exact binary
                byte[] imageBytes = page.context().request().get(currentSrc).body();
                String hash = md5(imageBytes);
                // Secondary: render at fixed 64x64 for visual fingerprint
                String perceptualHash = computePerceptualHash(currentSrc, helperPage);
                images.add(new ImageData(index, src.isBlank() ? currentSrc : src, hash, perceptualHash));
                index++;
            } catch (Exception e) {
                System.out.println("[WARN] Site " + siteLabel + " - skipped image " + index + " (" + currentSrc + "): " + e.getMessage());
            }
        }
        System.out.println("[INFO] Site " + siteLabel + " - captured " + images.size() + " image(s).");
        return images;
    }

    /**
     * Renders the image at a fixed 64x64px viewport using Playwright and returns
     * an MD5 of the screenshot. This produces a visual fingerprint that is
     * independent of CDN path, image format (webp/png/jpg), or rendition size.
     */
    private String computePerceptualHash(String imageUrl, Page helperPage) {
        try {
            helperPage.evaluate(
                "(url) => new Promise((res, rej) => {" +
                "  const img = document.getElementById('ph');" +
                "  const load = () => res();" +
                "  const err  = () => rej(new Error('load failed'));" +
                "  img.addEventListener('load',  load, {once: true});" +
                "  img.addEventListener('error', err,  {once: true});" +
                "  img.src = ''; img.src = url;" +  // force re-load even if same URL
                "})", imageUrl);
            byte[] shot = helperPage.locator("#ph").screenshot();
            return md5(shot);
        } catch (Exception e) {
            return "phash-error";
        }
    }

    // -------------------------------------------------------
    // LINKS — collects hrefs from <a>, onclick, data-href, data-url
    // -------------------------------------------------------
    private List<LinkData> extractLinks(Page page, String siteLabel) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> extracted = (List<Map<String, String>>) page.evaluate("""
                    () => {
                        const linksMap = new Map();
                        const addLink = (href, el) => {
                            if (!href) return;
                            const text = (el.innerText || el.textContent || "").trim();
                            if (!linksMap.has(href) || (linksMap.get(href) === "" && text !== "")) {
                                linksMap.set(href, text);
                            }
                        };

                        document.querySelectorAll('a[href]').forEach(a => {
                            addLink(a.getAttribute('href'), a);
                        });
                        document.querySelectorAll('button[onclick]').forEach(btn => {
                            const onclick = btn.getAttribute('onclick');
                            const sq = onclick.split("'");
                            for (let i = 1; i < sq.length; i += 2) {
                                const v = sq[i].trim();
                                if (v.startsWith('/') || v.includes('/')) addLink(v, btn);
                            }
                            const dq = onclick.split('"');
                            for (let i = 1; i < dq.length; i += 2) {
                                const v = dq[i].trim();
                                if (v.startsWith('/') || v.includes('/')) addLink(v, btn);
                            }
                        });
                        document.querySelectorAll('[data-href]').forEach(el =>
                            addLink(el.getAttribute('data-href'), el));
                        document.querySelectorAll('[data-url]').forEach(el =>
                            addLink(el.getAttribute('data-url'), el));

                        const result = [];
                        linksMap.forEach((text, href) => {
                            result.push({ href: href, text: text });
                        });
                        return result;
                    }
                """);

        List<LinkData> links = new ArrayList<>();
        for (Map<String, String> item : extracted) {
            String href = item.get("href");
            String text = item.get("text");
            if (href == null || href.isBlank())
                continue;
            if (href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:"))
                continue;
            String slug = extractSlug(href);
            if (!slug.isBlank())
                links.add(new LinkData(href, slug, text != null ? text : ""));
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
                if (!slug.isBlank() && !GENERIC_SLUGS.contains(slug.toLowerCase()))
                    return slug;
            }
        } catch (Exception ignored) {
        }
        return "";
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
