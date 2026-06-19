package com.automation.extractor;

import com.automation.Config;
import com.automation.model.ImageData;
import com.automation.model.LinkData;
import com.automation.model.SiteData;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

public class ContentExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ContentExtractor.class);
    private final Browser browser;
    private final FingerprintCache cache;
    private static final Set<String> GENERIC_SLUGS = new HashSet<>(Arrays.asList(
            "index", "index.html", "home", "default", "main", "..", "."));

    public ContentExtractor(Browser browser, FingerprintCache cache) {
        this.browser = browser;
        this.cache   = cache;
    }

    public SiteData extractSiteData(String url, String label) {
        long startTime = System.currentTimeMillis();

        int timeoutMs  = Config.getTimeoutSeconds() * 1000;
        int maxRetries = Config.getRetries();

        // ── HEAD pre-check (also extracts fingerprint headers) ────────────────
        String etag    = null;
        String lastMod = null;
        try (BrowserContext preCtx = browser.newContext()) {
            APIResponse head = preCtx.request().head(url,
                    com.microsoft.playwright.options.RequestOptions.create().setTimeout(8_000));
            etag    = head.headers().get("etag");
            lastMod = head.headers().get("last-modified");
        } catch (Exception e) {
            logger.debug("Site {} HEAD check skipped: {}", label, e.getMessage());
        }

        // ── Fingerprint cache lookup ───────────────────────────────────────────
        if (Config.isCacheEnabled() && cache != null) {
            SiteData cached = cache.get(url, etag, lastMod);
            if (cached != null) {
                logger.info("   [CACHE] {} - using saved data, no page load needed.", url);
                return cached;
            }
        }

        // ── Full Playwright extraction ─────────────────────────────────────────
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (BrowserContext context = browser.newContext();
                 Page page = context.newPage()) {

                // Block analytics, tag managers, and heavy 3rd-party scripts
                page.route("**/*qualtrics.com**",        Route::abort);
                page.route("**/*cookielaw.org**",         Route::abort);
                page.route("**/*onetrust.com**",          Route::abort);
                page.route("**/*tealiumiq.com**",         Route::abort);
                page.route("**/*google-analytics.com**",  Route::abort);
                page.route("**/*googletagmanager.com**",  Route::abort);
                // Also block fonts/ads which add network idle delay
                page.route("**/*.woff2",                  Route::abort);
                page.route("**/*.woff",                   Route::abort);

                logger.info("   Loading page: {}", url);
                page.navigate(url, new Page.NavigateOptions().setTimeout(timeoutMs));
                try {
                    // Wait for network requests to settle (dynamic content/AJAX)
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(8000));
                } catch (Exception e) {
                    logger.debug("   Network idle wait timed out for {} (proceeding with parsed DOM): {}", label, e.getMessage());
                    try {
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                                new Page.WaitForLoadStateOptions().setTimeout(2000));
                    } catch (Exception ignored) {}
                }
                // Wait an extra short buffer for layout shifts to settle
                page.waitForTimeout(500);

                // Quick scroll to trigger lazy-loaded images, then wait for them to settle
                try {
                    page.evaluate(
                        "() => new Promise(resolve => {" +
                        "  let pos = 0;" +
                        "  const step = () => {" +
                        "    window.scrollBy(0, 600); pos += 600;" +
                        "    if (pos < document.body.scrollHeight && pos < 8000) { setTimeout(step, 60); }" +
                        "    else { window.scrollTo(0, 0); resolve(); }" +
                        "  }; step();" +
                        "})");
                } catch (Exception e) {
                    logger.debug("Scroll skipped for {}: {}", label, e.getMessage());
                }

                // Wait for all lazy images to finish loading before extracting
                try {
                    page.waitForFunction(
                        "() => Array.from(document.images).every(img => img.complete)",
                        new Page.WaitForFunctionOptions().setTimeout(6_000));
                } catch (Exception e) {
                    logger.debug("Image completion wait timed out for {} — proceeding with partial load.", label);
                }

                logger.info("   Extracting content...");
                cleanDom(page);
                String rawText              = extractText(page);
                List<ImageData> images      = extractImages(page, context, label);
                List<LinkData> links        = extractLinks(page, label);
                Map<String, String> metadata  = extractMetadata(page);
                Map<String, String> dataLayer = extractDataLayer(page);

                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("   Done in {}ms — {} image(s), {} link(s) found.", elapsed, images.size(), links.size());

                SiteData result = new SiteData(label, url, rawText, images, links,
                        metadata, dataLayer, elapsed);

                // Store in cache
                if (Config.isCacheEnabled() && cache != null) {
                    cache.put(url, etag, lastMod, result);
                }

                return result;

            } catch (Exception e) {
                if (attempt < maxRetries) {
                    logger.warn("   Attempt {}/{} failed for {}. Retrying...", attempt, maxRetries, url);
                } else {
                    logger.error("   Failed to load {} after {} attempts: {}", url, maxRetries, e.getMessage());
                    return new SiteData(label, url, "", List.of(), List.of(),
                            Map.of(), Map.of(), 0);
                }
            }
        }
        return new SiteData(label, url, "", List.of(), List.of(), Map.of(), Map.of(), 0);
    }

    // ── DOM Cleaning ─────────────────────────────────────────────────────────

    private void cleanDom(Page page) {
        String ignoreSelectors = Config.getIgnoreSelectors();
        String safeIgnore = ignoreSelectors != null ? ignoreSelectors.replace("'", "\\'") : "";
        page.evaluate(String.format("""
            () => {
                const ignoreList = '%s';

                // Remove scripts, styles, noscripts
                document.querySelectorAll('script, style, noscript').forEach(el => el.remove());

                // Keep hidden elements (commented out to avoid removing inactive accordion/tabs/carousel texts)
                /*
                document.querySelectorAll('body *').forEach(el => {
                    try {
                        const s = window.getComputedStyle(el);
                        if (el.hasAttribute('hidden') || s.display === 'none' || s.visibility === 'hidden') {
                            el.remove();
                        }
                    } catch(_) {}
                });
                */

                // Remove structural chrome and cookie banners
                const selectors = [
                    'header','footer','nav','#header','#footer','#navigation','#navbar',
                    '.header','.footer','.navigation','.navigation__sticky',
                    '.page-header','.page-footer','.site-header','.site-footer',
                    '.navbar','.nav-container',
                    '#onetrust-consent-sdk','.cookie-banner','#cookie-law-info-bar'
                ];
                if (ignoreList.trim()) {
                    ignoreList.split(',').forEach(s => { if (s.trim()) selectors.push(s.trim()); });
                }
                selectors.forEach(sel => document.querySelectorAll(sel).forEach(el => el.remove()));

                // Freeze animations
                const style = document.createElement('style');
                style.innerHTML = '* { transition:none!important; animation:none!important; scroll-behavior:auto!important; }';
                if (document.head) {
                    document.head.appendChild(style);
                } else if (document.documentElement) {
                    document.documentElement.appendChild(style);
                }
            }
        """, safeIgnore));
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    private String extractText(Page page) {
        return (String) page.evaluate("() => document.body ? document.body.innerText : ''");
    }

    // ── Images ───────────────────────────────────────────────────────────────

    private List<ImageData> extractImages(Page page, BrowserContext context, String siteLabel) {
        // Collect image URLs from the DOM in one JS call
        @SuppressWarnings("unchecked")
        List<Map<String, String>> imgInfoList = (List<Map<String, String>>) page.evaluate(
            "() => Array.from(document.images)" +
            ".map(img => ({ src: img.getAttribute('src')||'', currentSrc: img.currentSrc||img.src||'' }))" +
            ".filter(info => info.currentSrc.toLowerCase().startsWith('http'))");

        List<ImageData> images = new ArrayList<>();
        int index = 0;
        for (Map<String, String> info : imgInfoList) {
            String src        = info.get("src");
            String currentSrc = info.get("currentSrc");
            // Strip CDN cache-busting query params so the same image always produces the same hash
            String downloadUrl = stripCacheBustingParams(currentSrc);
            try {
                // Download image bytes once via the existing context's request API (no new page)
                byte[] imageBytes = context.request().get(downloadUrl,
                        com.microsoft.playwright.options.RequestOptions.create().setTimeout(8_000)).body();
                String hash = md5(imageBytes);
                images.add(new ImageData(index, src.isBlank() ? currentSrc : src, hash, hash));
                index++;
            } catch (Exception e) {
                logger.debug("Skipped image {}: {}", downloadUrl, e.getMessage());
            }
        }
        return images;
    }

    /**
     * Strips well-known CDN cache-busting query parameters from an image URL so that
     * the same image served with different cache tokens still yields the same MD5 hash.
     */
    private String stripCacheBustingParams(String url) {
        if (url == null || !url.contains("?")) return url;
        try {
            java.net.URI uri = new java.net.URI(url);
            String query = uri.getQuery();
            if (query == null) return url;
            // Keep only non-cache-busting params; drop known volatile keys
            java.util.Set<String> BUST_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
                    "v", "ver", "t", "ts", "_", "cb", "bust", "rand", "nocache",
                    "cachebuster", "cachekey", "rev", "hash"));
            String[] pairs = query.split("&");
            StringBuilder kept = new StringBuilder();
            for (String p : pairs) {
                String key = p.split("=")[0].toLowerCase();
                if (!BUST_KEYS.contains(key)) {
                    if (kept.length() > 0) kept.append("&");
                    kept.append(p);
                }
            }
            String newQuery = kept.length() > 0 ? "?" + kept : "";
            String base = url.substring(0, url.indexOf("?"));
            return base + newQuery;
        } catch (Exception e) {
            return url;
        }
    }

    // ── Links ─────────────────────────────────────────────────────────────────

    private List<LinkData> extractLinks(Page page, String siteLabel) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> extracted = (List<Map<String, String>>) page.evaluate("""
            () => {
                const m = new Map();
                const add = (href, el) => {
                    if (!href) return;
                    const t = (el.innerText||el.textContent||'').trim();
                    if (!m.has(href) || (m.get(href)==='' && t!=='')) m.set(href, t);
                };
                document.querySelectorAll('a[href]').forEach(a => add(a.getAttribute('href'), a));
                document.querySelectorAll('button[onclick]').forEach(btn => {
                    const oc = btn.getAttribute('onclick');
                    [oc.split("'"), oc.split('"')].forEach(parts => {
                        for (let i=1; i<parts.length; i+=2) {
                            const v = parts[i].trim();
                            if (v.startsWith('/') || v.includes('/')) add(v, btn);
                        }
                    });
                });
                document.querySelectorAll('[data-href]').forEach(el => add(el.getAttribute('data-href'), el));
                document.querySelectorAll('[data-url]').forEach(el  => add(el.getAttribute('data-url'),  el));
                const res = [];
                m.forEach((text, href) => res.push({href, text}));
                return res;
            }
        """);

        List<LinkData> links = new ArrayList<>();
        for (Map<String, String> item : extracted) {
            String href = item.get("href");
            String text = item.get("text");
            if (href == null || href.isBlank() || href.startsWith("#")) continue;
            String slug = extractSlug(href);
            if (!slug.isBlank()) links.add(new LinkData(href, slug, text != null ? text : ""));
        }
        return links.stream().distinct().collect(Collectors.toList());
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    private Map<String, String> extractMetadata(Page page) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) page.evaluate("""
            () => {
                const meta = {};
                const title = document.querySelector('title');
                if (title) {
                    meta['Title'] = title.textContent.trim();
                }

                document.querySelectorAll('meta').forEach(el => {
                    const name = el.getAttribute('name') || el.getAttribute('property') || el.getAttribute('http-equiv');
                    const content = el.getAttribute('content');
                    if (name && content !== null && content !== undefined) {
                        meta[name] = content.trim();
                    }
                });
                return meta;
            }
        """);

        Map<String, String> metadata = new LinkedHashMap<>();
        if (raw != null) raw.forEach((k, v) -> { if (v != null) metadata.put(k, v.toString()); });
        return metadata;
    }

    // ── DataLayer ─────────────────────────────────────────────────────────────

    private Map<String, String> extractDataLayer(Page page) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) page.evaluate("""
            () => {
                const result = {};
                const scan = (obj, prefix='') => {
                    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return;
                    Object.keys(obj).forEach(k => {
                        const val = obj[k];
                        if (val === null || val === undefined) return;
                        if (typeof val === 'string' || typeof val === 'number' || typeof val === 'boolean') {
                            result[prefix + k] = String(val).trim();
                        } else if (typeof val === 'object' && !Array.isArray(val)) {
                            // Max depth of 5 to prevent infinite recursion
                            if (prefix.split('.').length < 5) {
                                scan(val, prefix + k + '.');
                            }
                        }
                    });
                };

                if (window.dataLayer && Array.isArray(window.dataLayer)) {
                    const isMetadataObject = (item) => {
                        if (!item || typeof item !== 'object') return false;
                        if (item.event === 'metadata') return true;
                        const keys = ['brand', 'brand_id', 'country', 'pageName'];
                        return keys.some(k => k in item);
                    };
                    let metaObj = window.dataLayer.find(isMetadataObject);
                    if (!metaObj && window.dataLayer.length > 1) {
                        metaObj = window.dataLayer[1];
                    }
                    if (metaObj && typeof metaObj === 'object') {
                        scan(metaObj, 'dataLayer.');
                    }
                }
                if (window.digitalData) scan(window.digitalData, 'digitalData.');
                if (window.siteData) scan(window.siteData, 'siteData.');
                if (window.siteDataLayer) scan(window.siteDataLayer, 'siteDataLayer.');

                return result;
            }
        """);

        Map<String, String> dataLayer = new LinkedHashMap<>();
        if (raw != null) raw.forEach((k, v) -> { if (v != null) dataLayer.put(k, v.toString()); });
        return dataLayer;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String extractSlug(String href) {
        try {
            String path = href.startsWith("http") ? new URI(href).getPath() : href.split("\\?")[0];
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (part.isEmpty()) continue;
                String slug = part.replaceAll("\\.[a-zA-Z0-9]+$", "");
                if (!slug.isBlank() && !GENERIC_SLUGS.contains(slug.toLowerCase())) return slug;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
