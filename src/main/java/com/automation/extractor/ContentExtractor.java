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
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ContentExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ContentExtractor.class);
    private final Browser browser;
    private final FingerprintCache cache;
    private static final Set<String> GENERIC_SLUGS = new HashSet<>(Arrays.asList(
            "index", "index.html", "home", "default", "main", "..", "."));
    private static final Semaphore GLOBAL_IMAGE_SEMAPHORE = new Semaphore(3); // Global concurrency limit
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
            .build();

    public ContentExtractor(Browser browser, FingerprintCache cache) {
        this.browser = browser;
        this.cache   = cache;
    }

    public SiteData extractSiteData(String url, String label) {
        long startTime = System.currentTimeMillis();

        int timeoutMs  = Config.getTimeoutSeconds() * 1000;
        int maxRetries = Config.getRetries();

        // ── HEAD pre-check via native lightweight HttpClient (takes ~150ms instead of 1.5s) ──
        String etag    = null;
        String lastMod = null;
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(java.time.Duration.ofSeconds(Config.getTimeoutSeconds()))
                    .build();
            java.net.http.HttpResponse<Void> response = httpClient.send(request, 
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            etag = response.headers().firstValue("etag").orElse(null);
            lastMod = response.headers().firstValue("last-modified").orElse(null);
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
                page.route("**/*.woff2",                  Route::abort);
                page.route("**/*.woff",                   Route::abort);

                logger.info("   Loading page: {}", url);
                page.navigate(url, new Page.NavigateOptions().setTimeout(timeoutMs));
                try {
                    // Wait for initial DOM load state (LOAD state is highly sufficient)
                    page.waitForLoadState(LoadState.LOAD,
                            new Page.WaitForLoadStateOptions().setTimeout(4000));
                } catch (Exception e) {
                    logger.debug("   Load state wait timed out: {}", e.getMessage());
                }

                // Short sleep buffer for page elements to settle (0.3s)
                page.waitForTimeout(300);
 
                // Quick scroll to trigger lazy-loaded images
                try {
                    page.evaluate(
                        "() => new Promise(resolve => {" +
                        "  let pos = 0;" +
                        "  const step = () => {" +
                        "    window.scrollBy(0, 600); pos += 600;" +
                        "    if (pos < document.body.scrollHeight && pos < 8000) { setTimeout(step, 40); }" +
                        "    else { window.scrollTo(0, 0); resolve(); }" +
                        "  }; step();" +
                        "})");
                } catch (Exception e) {
                    logger.debug("Scroll skipped for {}: {}", label, e.getMessage());
                }
 
                // Wait very briefly for final layout elements
                page.waitForTimeout(200);

                logger.info("   Extracting content...");
                cleanDom(page);
                Map<String, String> funcComponents = extractFunctionalityComponents(page);
                String rawText              = extractText(page);
                List<ImageData> images      = extractImages(page, context, label);
                List<LinkData> links        = extractLinks(page, label);
                Map<String, String> metadata  = extractMetadata(page);
                Map<String, String> dataLayer = extractDataLayer(page);

                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("   Done in {}ms — {} image(s), {} link(s) found.", elapsed, images.size(), links.size());

                SiteData result = new SiteData(label, url, rawText, images, links,
                        metadata, dataLayer, funcComponents, elapsed);

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
                            Map.of(), Map.of(), Map.of(), 0);
                }
            }
        }
        return new SiteData(label, url, "", List.of(), List.of(), Map.of(), Map.of(), Map.of(), 0);
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
            ".map(img => ({ src: img.getAttribute('src')||'', currentSrc: img.currentSrc||img.src||'', alt: img.getAttribute('alt')||'' }))" +
            ".filter(info => info.currentSrc.toLowerCase().startsWith('http'))");

        List<ImageData> images = java.util.Collections.synchronizedList(new ArrayList<>());
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Map<String, String> info : imgInfoList) {
                futures.add(executor.submit(() -> {
                    String src        = info.get("src");
                    String currentSrc = info.get("currentSrc");
                    String downloadUrl = stripCacheBustingParams(currentSrc);
                    try {
                        GLOBAL_IMAGE_SEMAPHORE.acquire();
                        try {
                            // Small delay to prevent instant bursts across threads
                            Thread.sleep(150);
                            
                            // Dynamic User-Agent Routing
                            String userAgent;
                            String lowerUrl = downloadUrl.toLowerCase();
                            if (lowerUrl.contains("wikipedia.org") || lowerUrl.contains("wikimedia.org")) {
                                userAgent = "SiteContentComparator/1.0 (Contact: admin@example.com)";
                            } else {
                                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
                            }
                            
                            // Native HTTP request to download image bytes concurrently
                            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(downloadUrl))
                                    .header("User-Agent", userAgent)
                                    .timeout(java.time.Duration.ofSeconds(Config.getTimeoutSeconds()))
                                    .build();
                            
                            int maxRetries = 2;
                            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                                java.net.http.HttpResponse<byte[]> response = this.httpClient.send(request, 
                                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                                
                                if (response.statusCode() == 200) {
                                    byte[] imageBytes = response.body();
                                    String hash = md5(imageBytes);
                                    images.add(new ImageData(0, src.isBlank() ? currentSrc : src, info.get("alt"), hash, hash));
                                    break;
                                } else if (response.statusCode() == 429 && attempt < maxRetries) {
                                    // Rate limited, wait much longer to cool down
                                    int waitTime = 4000 + (attempt * 3000);
                                    logger.debug("Rate limited (429) downloading {}, retrying in {}ms (attempt {})...", downloadUrl, waitTime, attempt + 1);
                                    Thread.sleep(waitTime);
                                } else {
                                    logger.debug("Failed to download image {}, status code: {}", downloadUrl, response.statusCode());
                                    break;
                                }
                            }
                        } finally {
                            GLOBAL_IMAGE_SEMAPHORE.release();
                        }
                    } catch (Exception e) {
                        logger.debug("Skipped image {}: {}", downloadUrl, e.getMessage());
                    }
                }));
            }
            // Wait for all downloads to finish
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {}
            }
        }

        // Re-index images sequentially
        List<ImageData> sortedImages = new ArrayList<>();
        int index = 0;
        for (ImageData img : images) {
            sortedImages.add(new ImageData(index++, img.getSrc(), img.getAltText(), img.getHash(), img.getPerceptualHash()));
        }
        return sortedImages;
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

    // ── Functionality Components ──────────────────────────────────────────────

    private Map<String, String> extractFunctionalityComponents(Page page) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) page.evaluate("""
            () => {
                const getDetails = (selectors, formatter) => {
                    const elements = Array.from(document.querySelectorAll(selectors));
                    const count = elements.length.toString();
                    const details = elements.map(formatter)
                                             .map(d => d.trim().replace(/\\s+/g, ' '))
                                             .filter(d => d.length > 0);
                    const detailsStr = details.join(', ');
                    return count + '|' + (detailsStr ? detailsStr : '[empty]');
                };
                
                // ── Helper: detect button-like <a> tags ──
                const isVisualButton = (el) => {
                    if (el.tagName !== 'A') return false;
                    const cs = window.getComputedStyle(el);
                    // Check for button-like classes
                    const cls = el.className || '';
                    if (/\\b(btn|button|cta)\\b/i.test(cls)) return true;
                    // Check for pill/rounded shape with bg color
                    const bg = cs.backgroundColor;
                    const hasBackground = bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent';
                    const br = parseFloat(cs.borderRadius);
                    const hasBorder = cs.border && cs.border !== 'none' && !cs.border.startsWith('0');
                    if (hasBackground && br > 3) return true;
                    if (hasBorder && br > 3) return true;
                    // Check role="button"
                    if (el.getAttribute('role') === 'button') return true;
                    return false;
                };

                // ── Helper: filter out carousel pagination/dot buttons ──
                const isCarouselDot = (el) => {
                    const cls = (el.className || '').toLowerCase();
                    const aria = (el.getAttribute('aria-label') || '').toLowerCase();
                    const role = (el.getAttribute('role') || '').toLowerCase();
                    // Swiper/carousel pagination bullets
                    if (/pagination|bullet|dot|swiper-pagination|carousel-indicator|slick-dot/.test(cls)) return true;
                    // aria-label like "Carousel Page 1", "Go to slide 2", "Page 1"
                    if (/carousel\\s*page|go to slide|slide \\d|page \\d/i.test(aria)) return true;
                    // data-slide-to attribute (Bootstrap carousel)
                    if (el.hasAttribute('data-slide-to') || el.hasAttribute('data-bs-slide-to')) return true;
                    // Previous/Next carousel navigation arrows
                    if (/carousel-control|slick-prev|slick-next|swiper-button/.test(cls)) return true;
                    if (/previous|next\\s*(slide|page)|prev\\s*(slide|page)/i.test(aria)) return true;
                    return false;
                };

                // ── Buttons: real action buttons only, excluding carousel pagination ──
                const buttonEls = Array.from(document.querySelectorAll('button, input[type="button"], input[type="submit"], .btn, [role="button"]'))
                    .filter(el => !isCarouselDot(el) && el.tagName !== 'A');
                const buttonCount = buttonEls.length;
                const buttonDetails = buttonEls.map(el => (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.getAttribute('title') || '').trim().replace(/\\s+/g, ' '))
                    .filter(d => d.length > 0);

                // ── CTA Links: <a> tags that visually look like buttons ──
                const ctaEls = Array.from(document.querySelectorAll('a'))
                    .filter(el => isVisualButton(el))
                    .filter(el => {
                        const text = (el.innerText || '').trim();
                        return text.length > 0 && text.length < 100; // reasonable button text
                    });
                const ctaCount = ctaEls.length;
                const ctaDetails = ctaEls.map(el => {
                    const text = (el.innerText || '').trim().replace(/\\s+/g, ' ');
                    const href = el.getAttribute('href') || '';
                    return text + (href ? ' -> ' + href : '');
                }).filter(d => d.length > 0);

                // ── Zoomable Images: broad detection ──
                const zoomSelectors = [
                    'img[data-zoom]', 'img[data-zoom-src]', 'img.zoom', 'img.zoomable',
                    'img[data-fancybox]', '[data-fancybox] img',
                    'img[data-lightbox]', '[data-lightbox] img',
                    'img[data-mfp-src]', '.mfp-image img',
                    '.magnific-popup img', '.lightbox img',
                    'img[data-action="zoom"]',
                    '.gallery img', '.zoom-image img',
                    '.magnify img', '[class*="magnif"] img', '[class*="zoom"] img'
                ];
                // Also detect images wrapped in <a> tags pointing to image files
                const zoomableSet = new Set();
                document.querySelectorAll(zoomSelectors.join(', ')).forEach(el => {
                    const img = el.tagName === 'IMG' ? el : el.querySelector('img');
                    if (img) zoomableSet.add(img);
                });
                // Images inside <a> tags where href points to an image file
                document.querySelectorAll('a[href] img').forEach(img => {
                    const a = img.closest('a');
                    const href = (a && a.getAttribute('href')) || '';
                    if (/\\.(jpg|jpeg|png|gif|webp|svg|bmp|avif)(\\?|$)/i.test(href)) {
                        zoomableSet.add(img);
                    }
                });
                // Images with onclick/event handlers containing magnify or zoom
                document.querySelectorAll('img').forEach(img => {
                    if (zoomableSet.has(img)) return;
                    
                    const hasZoomHandler = (el) => {
                        if (!el || !el.attributes) return false;
                        for (let j = 0; j < el.attributes.length; j++) {
                            const attr = el.attributes[j];
                            if (/magnif|zoom/i.test(attr.value) && /click|tap|action|on/i.test(attr.name)) {
                                return true;
                            }
                        }
                        return false;
                    };

                    // Check the image itself
                    if (hasZoomHandler(img)) {
                        zoomableSet.add(img);
                        return;
                    }

                    // Check parent elements (up to 3 levels)
                    let parent = img.parentElement;
                    for (let i = 0; i < 3 && parent; i++) {
                        if (hasZoomHandler(parent)) {
                            zoomableSet.add(img);
                            return;
                        }
                        parent = parent.parentElement;
                    }
                });
                const zoomableArr = Array.from(zoomableSet);
                const zoomCount = zoomableArr.length;
                const zoomDetails = zoomableArr.map(el => {
                    const src = el.getAttribute('src') || el.src || '';
                    const parts = src.split('/');
                    const filename = parts[parts.length - 1] || src;
                    const alt = el.getAttribute('alt') || '';
                    return alt ? filename + ' ("' + alt + '")' : filename;
                }).filter(d => d.length > 0);

                return {
                    'Buttons': buttonCount + '|' + (buttonDetails.length ? buttonDetails.join(', ') : '[empty]'),
                    'CTA Links (Button-styled)': ctaCount + '|' + (ctaDetails.length ? ctaDetails.join(', ') : '[empty]'),
                    'Clickable Images': getDetails('a img, img[onclick], img[style*="cursor: pointer"], img[style*="cursor:pointer"]', 
                        el => {
                            const src = el.getAttribute('src') || el.src || '';
                            const parts = src.split('/');
                            const filename = parts[parts.length - 1] || src;
                            const alt = el.getAttribute('alt') || '';
                            return alt ? filename + ' ("' + alt + '")' : filename;
                        }),
                    'Zoomable Images': zoomCount + '|' + (zoomDetails.length ? zoomDetails.join(', ') : '[empty]'),
                    'Modals': getDetails('.modal, dialog', el => el.id ? '#' + el.id : (el.className ? '.' + el.className.split(' ')[0] : 'dialog')),
                    'Popups': getDetails('[data-toggle="modal"], [data-bs-toggle="modal"]', el => el.innerText || el.getAttribute('aria-label') || el.id || ''),
                    'Carousels': getDetails('.carousel, .swiper-container, .swiper', el => el.id ? '#' + el.id : (el.className ? '.' + el.className.split(' ')[0] : '.carousel')),
                    'Sliders': getDetails('.slick-slider, .owl-carousel', el => el.id ? '#' + el.id : (el.className ? '.' + el.className.split(' ')[0] : '.slider')),
                    'Accordions': getDetails('.accordion', el => el.id ? '#' + el.id : '.accordion'),
                    'Toggles': getDetails('details', el => {
                        const summary = el.querySelector('summary');
                        return summary ? summary.innerText : 'details';
                    }),
                    'Video Players': getDetails('video, iframe[src*="youtube.com"], iframe[src*="vimeo.com"]', 
                        el => {
                            const src = el.getAttribute('src') || el.src || '';
                            const name = src ? (src.includes('youtube.com') ? 'YouTube' : src.includes('vimeo.com') ? 'Vimeo' : 'Video') : 'HTML5 Video';
                            const parts = src.split('/');
                            const filename = parts[parts.length - 1] || '';
                            return filename ? name + ' (' + filename.split('?')[0] + ')' : name;
                        }),
                    'Audio Players': getDetails('audio', el => {
                        const src = el.getAttribute('src') || el.src || '';
                        const parts = src.split('/');
                        return parts[parts.length - 1] ? 'Audio (' + parts[parts.length - 1].split('?')[0] + ')' : 'Audio Player';
                    }),
                    'Dropdowns': getDetails('.dropdown, [data-toggle="dropdown"], [data-bs-toggle="dropdown"]', 
                        el => el.innerText.trim().split('\\n')[0] || el.id || 'Dropdown'),
                    'Selects': getDetails('select', el => el.name || el.id || 'select'),
                    'Forms': getDetails('form', el => el.id ? '#' + el.id : (el.action ? el.action.split('/').pop() : 'form')),
                    'Inputs': getDetails('input[type="text"], input[type="checkbox"], input[type="radio"], textarea', 
                        el => (el.name ? el.name : '') + (el.type ? ' (' + el.type + ')' : '')),
                    'Tabs': getDetails('[role="tab"], .nav-tabs', el => el.innerText || ''),
                    'Tooltips': getDetails('[data-toggle="tooltip"], [data-bs-toggle="tooltip"], [title]', 
                        el => el.getAttribute('title') || el.innerText || 'Tooltip'),
                    'Iframes': getDetails('iframe', el => {
                        const src = el.getAttribute('src') || '';
                        return src ? src.split('/').pop() : 'iframe';
                    })
                };
            }
        """);

        Map<String, String> components = new LinkedHashMap<>();
        if (raw != null) raw.forEach((k, v) -> { if (v != null) components.put(k, v.toString()); });
        return components;
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
