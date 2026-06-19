package com.automation.extractor;

import com.automation.model.ImageData;
import com.automation.model.LinkData;
import com.automation.model.SiteData;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Thread-safe, persistent fingerprint cache for page extractions.
 *
 * <p>Before each Playwright extraction, {@link ContentExtractor} asks this cache whether
 * a page's HTTP fingerprint (ETag / Last-Modified) still matches a previous extraction.
 * If it does, the cached {@link SiteData} is returned immediately, skipping the expensive
 * Playwright navigation entirely.</p>
 *
 * <p>The cache is backed by {@code cache/fingerprints.json} on disk.
 * Call {@link #persist()} once after all pairs have been processed to flush in-memory
 * changes back to disk.</p>
 */
public final class FingerprintCache {

    private static final Logger logger = LoggerFactory.getLogger(FingerprintCache.class);
    private static final Path   CACHE_DIR  = Paths.get("cache");
    private static final Path   CACHE_FILE = CACHE_DIR.resolve("fingerprints.json");

    private final ConcurrentHashMap<String, CachedEntry> store = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final int ttlHours;

    // ── Serialization types ───────────────────────────────────────────────────

    /** Lightweight on-disk representation of one cached page extraction. */
    static class CachedEntry {
        String   url;
        String   etag;
        String   lastModified;
        long     cachedAtEpochMs;
        String   rawText;
        List<ImageData> images;
        List<LinkData>  links;
        Map<String, String> metadata;
        Map<String, String> dataLayer;
        long extractionTimeMillis;

        CachedEntry() {}
    }

    public FingerprintCache(int ttlHours) {
        this.ttlHours = ttlHours;
        load();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Looks up a cached entry for the given URL.
     *
     * @param url          the page URL
     * @param freshEtag    the ETag returned by the latest HEAD request (may be null)
     * @param freshLastMod the Last-Modified header value (may be null)
     * @return a fully reconstructed {@link SiteData} (without screenshot) if cache hits,
     *         or {@code null} if the page must be re-extracted
     */
    public SiteData get(String url, String freshEtag, String freshLastMod) {
        CachedEntry entry = store.get(url);
        if (entry == null) return null;

        // TTL check
        long ageMs = Instant.now().toEpochMilli() - entry.cachedAtEpochMs;
        if (ageMs > (long) ttlHours * 3600 * 1000) {
            logger.debug("Cache TTL expired for {}", url);
            store.remove(url);
            return null;
        }

        // Fingerprint check – at least one fingerprint must match
        boolean etagMatch = freshEtag != null && !freshEtag.isBlank()
                && freshEtag.equals(entry.etag);
        boolean lastModMatch = freshLastMod != null && !freshLastMod.isBlank()
                && freshLastMod.equals(entry.lastModified);

        // If neither fingerprint is available, do not trust the cache
        boolean hasFingerprint = (freshEtag != null && !freshEtag.isBlank())
                || (freshLastMod != null && !freshLastMod.isBlank());

        if (!hasFingerprint || (!etagMatch && !lastModMatch)) {
            logger.debug("Page has changed since last run for {} - loading fresh.", url);
            return null;
        }

        logger.info("[CACHE HIT] {} – skipping Playwright extraction", url);
        // Reconstruct SiteData; screenshot omitted (empty) for cached entries
        List<ImageData> images   = entry.images   != null ? entry.images   : List.of();
        List<LinkData>  links    = entry.links     != null ? entry.links    : List.of();
        Map<String, String> meta = entry.metadata  != null ? entry.metadata : Map.of();
        Map<String, String> dl   = entry.dataLayer != null ? entry.dataLayer: Map.of();

        return new SiteData(
                extractLabel(url), url,
                entry.rawText != null ? entry.rawText : "",
                images, links, meta, dl,
                entry.extractionTimeMillis
        );
    }

    /**
     * Stores a successfully extracted {@link SiteData} in the cache.
     *
     * @param url         the page URL
     * @param etag        ETag returned by the server (may be null)
     * @param lastMod     Last-Modified header value (may be null)
     * @param data        the freshly extracted site data
     */
    public void put(String url, String etag, String lastMod, SiteData data) {
        CachedEntry entry = new CachedEntry();
        entry.url                  = url;
        entry.etag                 = etag;
        entry.lastModified         = lastMod;
        entry.cachedAtEpochMs      = Instant.now().toEpochMilli();
        entry.rawText              = data.getRawText();
        entry.images               = data.getImages();
        entry.links                = data.getLinks();
        entry.metadata             = data.getMetadata();
        entry.dataLayer            = data.getDataLayer();
        entry.extractionTimeMillis = data.getExtractionTimeMillis();
        store.put(url, entry);
    }

    /**
     * Flushes in-memory cache entries to {@code cache/fingerprints.json}.
     * Call this once after all pairs have been processed.
     */
    public synchronized void persist() {
        try {
            if (!Files.exists(CACHE_DIR)) Files.createDirectories(CACHE_DIR);
            Map<String, CachedEntry> snapshot = new HashMap<>(store);
            String json = gson.toJson(snapshot);
            Files.writeString(CACHE_FILE, json, StandardCharsets.UTF_8);
            logger.info("Cache saved: {} page(s) stored.", snapshot.size());
        } catch (Exception e) {
            logger.warn("Failed to persist fingerprint cache: {}", e.getMessage());
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(CACHE_FILE)) {
            logger.debug("No fingerprint cache found – starting fresh");
            return;
        }
        try {
            String json = Files.readString(CACHE_FILE, StandardCharsets.UTF_8);
            Type mapType = new TypeToken<Map<String, CachedEntry>>() {}.getType();
            Map<String, CachedEntry> loaded = gson.fromJson(json, mapType);
            if (loaded != null) {
                store.putAll(loaded);
                logger.debug("Cache loaded: {} page(s) from previous run.", loaded.size());
            }
        } catch (Exception e) {
            logger.warn("Failed to load fingerprint cache (will start fresh): {}", e.getMessage());
        }
    }

    private static String extractLabel(String url) {
        try { return new java.net.URI(url).getHost(); }
        catch (Exception e) { return "cached"; }
    }
}
