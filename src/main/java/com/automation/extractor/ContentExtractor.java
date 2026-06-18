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
    private static final Set<String> GENERIC_SLUGS = new HashSet<>(Arrays.asList(
            "index", "index.html", "home", "default", "main", "..", "."));

    public ContentExtractor(Browser browser) {
        this.browser = browser;
    }

    public SiteData extractSiteData(String url, String label) {
        long startTime = System.currentTimeMillis();

        int timeout = Config.getTimeoutSeconds() * 1000;
        int maxRetries = Config.getRetries();

        try (BrowserContext preCheckContext = browser.newContext()) {
            APIResponse response = preCheckContext.request().head(url,
                    com.microsoft.playwright.options.RequestOptions.create().setTimeout(10000));
            if (!response.ok() && response.status() != 405 && response.status() != 403) {
                logger.warn("Site {} returned HTTP {} on HEAD pre-check. Proceeding anyway...", label, response.status());
            }
        } catch (Exception e) {
            logger.warn("Site {} HTTP pre-check failed: {}. Proceeding anyway...", label, e.getMessage());
        }

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // Memory Leak Fix & Isolation: Context and Pages in try-with-resources
            try (BrowserContext context = browser.newContext();
                 Page page = context.newPage();
                 Page helperPage = context.newPage()) {

                // Block third-party popups, surveys, and cookie banners at the network level
                page.route("**/*qualtrics.com**", Route::abort);
                page.route("**/*cookielaw.org**", Route::abort);
                page.route("**/*onetrust.com**", Route::abort);
                page.route("**/*tealiumiq.com**", Route::abort);
                page.route("**/*google-analytics.com**", Route::abort);
                page.route("**/*googletagmanager.com**", Route::abort); // GTM Blocking

                logger.info("Navigating to Site {} (Attempt {}/{}): {}", label, attempt, maxRetries, url);
                page.navigate(url, new Page.NavigateOptions().setTimeout(timeout));
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(timeout / 2));
                logger.info("Site {} loaded.", label);

                // Scroll the full page
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
                    logger.warn("Page scroll failed for {}: {}", label, e.getMessage());
                }

                helperPage.setViewportSize(64, 64);
                helperPage.setContent(
                        "<html><body style='margin:0;padding:0;width:64px;height:64px;overflow:hidden;background:#fff;'>" +
                        "<img id='ph' style='width:64px;height:64px;display:block;object-fit:fill;' /></body></html>");
                helperPage.waitForLoadState();

                logger.info("Extracting content from Site {}...", label);
                cleanDom(page);
                String rawText = extractText(page);
                List<ImageData> images = extractImages(page, helperPage, label);
                List<LinkData> links = extractLinks(page, label);
                Map<String, String> metadata = extractMetadata(page, label);
                Map<String, String> dataLayer = extractDataLayer(page, label);
                
                // Visual Heatmap Setup: take full page screenshot
                byte[] fullPageScreenshot = new byte[0];
                try {
                    fullPageScreenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                } catch (Exception e) {
                    logger.warn("Failed to capture full page screenshot for Site {}: {}", label, e.getMessage());
                }

                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("Site {} extraction complete ({} ms).", label, elapsed);
                return new SiteData(label, url, rawText, images, links, metadata, dataLayer, elapsed, fullPageScreenshot);

            } catch (Exception e) {
                logger.warn("Site {} attempt {} failed: {}", label, attempt, e.getMessage());
                if (attempt == maxRetries) {
                    logger.error("Site {} failed after {} attempts.", label, maxRetries);
                    return new SiteData(label, url, "", List.of(), List.of(), Map.of(), Map.of(), 0, new byte[0]);
                }
            }
        }
        return new SiteData(label, url, "", List.of(), List.of(), Map.of(), Map.of(), 0, new byte[0]);
    }

    private void cleanDom(Page page) {
        String ignoreSelectors = Config.getIgnoreSelectors();
        String js = String.format("""
            () => {
                const ignoreList = '%s';
                
                // 1. Remove non-visible elements
                document.querySelectorAll('script, style, noscript').forEach(el => el.remove());
                
                // Hidden Elements filtering
                document.querySelectorAll('*').forEach(el => {
                    const style = window.getComputedStyle(el);
                    if (el.hasAttribute('hidden') || style.display === 'none' || style.visibility === 'hidden') {
                        el.remove();
                    }
                });

                // 2. Remove standard page headers, footers, and navigation bars
                const selectors = [
                    'header', 'footer', 'nav',
                    '#header', '#footer', '#navigation', '#navbar',
                    '.header', '.footer', '.navigation', '.navigation__sticky',
                    '.page-header', '.page-footer',
                    '.site-header', '.site-footer',
                    '.navbar', '.nav-container',
                    '#onetrust-consent-sdk', '.cookie-banner', '#cookie-law-info-bar'
                ];
                if (ignoreList) {
                    ignoreList.split(',').forEach(s => {
                        if (s.trim()) selectors.push(s.trim());
                    });
                }
                
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
        """, ignoreSelectors != null ? ignoreSelectors : "");
        
        page.evaluate(js);
    }

    private String extractText(Page page) {
        return (String) page.evaluate("""
            () => document.body ? document.body.innerText : ''
        """);
    }

    private List<ImageData> extractImages(Page page, Page helperPage, String siteLabel) {
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
            logger.warn("Image load wait failed for {}: {}", siteLabel, e.getMessage());
        }
        page.waitForTimeout(500);

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
                        "  })");

        List<ImageData> images = new ArrayList<>();
        int index = 0;
        for (Map<String, String> info : imgInfoList) {
            String src = info.get("src");
            String currentSrc = info.get("currentSrc");
            try {
                byte[] imageBytes = page.context().request().get(currentSrc).body();
                String hash = md5(imageBytes);
                String perceptualHash = computePerceptualHash(currentSrc, helperPage);
                images.add(new ImageData(index, src.isBlank() ? currentSrc : src, hash, perceptualHash));
                index++;
            } catch (Exception e) {
                logger.warn("Site {} - skipped image {} ({}): {}", siteLabel, index, currentSrc, e.getMessage());
            }
        }
        logger.info("Site {} - captured {} image(s).", siteLabel, images.size());
        return images;
    }

    private String computePerceptualHash(String imageUrl, Page helperPage) {
        try {
            helperPage.evaluate(
                    "(url) => new Promise((res, rej) => {" +
                            "  const img = document.getElementById('ph');" +
                            "  const load = () => res();" +
                            "  const err  = () => rej(new Error('load failed'));" +
                            "  img.addEventListener('load',  load, {once: true});" +
                            "  img.addEventListener('error', err,  {once: true});" +
                            "  img.src = ''; img.src = url;" +
                            "})",
                    imageUrl);
            byte[] shot = helperPage.locator("#ph").screenshot();
            return md5(shot);
        } catch (Exception e) {
            return "phash-error";
        }
    }

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

                document.querySelectorAll('a[href]').forEach(a => addLink(a.getAttribute('href'), a));
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
                document.querySelectorAll('[data-href]').forEach(el => addLink(el.getAttribute('data-href'), el));
                document.querySelectorAll('[data-url]').forEach(el => addLink(el.getAttribute('data-url'), el));

                const result = [];
                linksMap.forEach((text, href) => result.push({ href: href, text: text }));
                return result;
            }
        """);

        List<LinkData> links = new ArrayList<>();
        for (Map<String, String> item : extracted) {
            String href = item.get("href");
            String text = item.get("text");
            if (href == null || href.isBlank() || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:"))
                continue;
            String slug = extractSlug(href);
            if (!slug.isBlank())
                links.add(new LinkData(href, slug, text != null ? text : ""));
        }

        List<LinkData> unique = links.stream().distinct().collect(Collectors.toList());
        logger.info("Site {} - found {} unique link slug(s).", siteLabel, unique.size());
        return unique;
    }

    private Map<String, String> extractMetadata(Page page, String siteLabel) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) page.evaluate("""
            () => {
                const meta = {};
                const titleTag = document.querySelector('title');
                let titleVal = titleTag ? titleTag.textContent.trim() : null;
                if (!titleVal) { const ogTitle = document.querySelector('meta[property="og:title"]'); if (ogTitle) titleVal = ogTitle.getAttribute('content'); }
                if (!titleVal) { const metaTitle = document.querySelector('meta[name="title"]'); if (metaTitle) titleVal = metaTitle.getAttribute('content'); }
                if (titleVal !== null) meta['Title'] = titleVal.trim();

                let descVal = null;
                const metaDesc = document.querySelector('meta[name="description"]');
                if (metaDesc) descVal = metaDesc.getAttribute('content');
                if (!descVal) { const ogDesc = document.querySelector('meta[property="og:description"]'); if (ogDesc) descVal = ogDesc.getAttribute('content'); }
                if (descVal !== null) meta['Description'] = descVal.trim();

                const metaKw = document.querySelector('meta[name="keywords"]');
                if (metaKw) { const kwVal = metaKw.getAttribute('content'); if (kwVal !== null) meta['Keywords'] = kwVal.trim(); }

                let authorVal = null;
                const metaAuthor = document.querySelector('meta[name="author"]');
                if (metaAuthor) authorVal = metaAuthor.getAttribute('content');
                if (!authorVal) { const ogAuthor = document.querySelector('meta[property="og:author"]') || document.querySelector('meta[property="article:author"]'); if (ogAuthor) authorVal = ogAuthor.getAttribute('content'); }
                if (authorVal !== null) meta['Author'] = authorVal.trim();

                let publisherVal = null;
                const metaPublisher = document.querySelector('meta[name="publisher"]');
                if (metaPublisher) publisherVal = metaPublisher.getAttribute('content');
                if (!publisherVal) { const ogPublisher = document.querySelector('meta[property="og:publisher"]') || document.querySelector('meta[property="article:publisher"]'); if (ogPublisher) publisherVal = ogPublisher.getAttribute('content'); }
                if (publisherVal !== null) meta['Publisher'] = publisherVal.trim();

                return meta;
            }
        """);

        Map<String, String> metadata = new LinkedHashMap<>();
        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                metadata.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        logger.info("Site {} - extracted {} metadata tag(s).", siteLabel, metadata.size());
        return metadata;
    }

    private Map<String, String> extractDataLayer(Page page, String siteLabel) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) page.evaluate("""
            () => {
                const result = {};
                const targetMapping = {
                  'sitetype': 'Site Type', 'brandwebsitetype': 'Brand Website Type',
                  'globalbrandr360': 'Global Brand - R360', 'globalbrand': 'Global Brand - R360',
                  'gbu': 'GBU', 'region': 'Region', 'country': 'Country',
                  'targetenus': 'Target (en_US)', 'target': 'Target (en_US)', 'targetaudience': 'Target (en_US)', 'targetpersona': 'Target (en_US)',
                  'franchiser360': 'Franchise - R360', 'franchise': 'Franchise - R360',
                  'therapeuticearear360': 'Therapeutic Area - R360', 'therapeuticarear360': 'Therapeutic Area - R360', 'therapeuticarea': 'Therapeutic Area - R360',
                  'indicationr360': 'Indication - R360', 'indication': 'Indication - R360', 'specialty': 'Specialty',
                  'customfield1value': 'Custom Field 1 Value', 'customfield1': 'Custom Field 1 Value',
                  'customfield2value': 'Custom Field 2 Value', 'customfield2': 'Custom Field 2 Value',
                  'customfield3value': 'Custom Field 3 Value', 'customfield3': 'Custom Field 3 Value',
                  'customfield4value': 'Custom Field 4 Value', 'customfield4': 'Custom Field 4 Value',
                  'customfield5value': 'Custom Field 5 Value', 'customfield5': 'Custom Field 5 Value'
                };
                const normalizeKey = (key) => key.toLowerCase().replace(/[^a-z0-9]/g, '');

                if (window.dataLayer && Array.isArray(window.dataLayer)) {
                    window.dataLayer.forEach(item => {
                        if (item && typeof item === 'object') {
                            Object.keys(item).forEach(k => {
                                const nk = normalizeKey(k);
                                if (targetMapping[nk]) result[targetMapping[nk]] = String(item[k]).trim();
                            });
                        }
                    });
                }

                const checkObjectRecursively = (obj, depth = 0) => {
                    if (!obj || typeof obj !== 'object' || depth > 5) return;
                    Object.keys(obj).forEach(k => {
                        const nk = normalizeKey(k);
                        if (targetMapping[nk]) result[targetMapping[nk]] = String(obj[k]).trim();
                        else if (obj[k] && typeof obj[k] === 'object') checkObjectRecursively(obj[k], depth + 1);
                    });
                };
                if (window.digitalData) checkObjectRecursively(window.digitalData);
                if (window.siteData) checkObjectRecursively(window.siteData);
                if (window.siteDataLayer) checkObjectRecursively(window.siteDataLayer);

                document.querySelectorAll('meta').forEach(tag => {
                    const name = tag.getAttribute('name') || tag.getAttribute('property') || tag.getAttribute('http-equiv');
                    const content = tag.getAttribute('content');
                    if (name && content !== null) {
                        const nk = normalizeKey(name);
                        if (targetMapping[nk]) result[targetMapping[nk]] = content.trim();
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
        logger.info("Site {} - extracted {} data layer property(ies).", siteLabel, dataLayer.size());
        return dataLayer;
    }

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
