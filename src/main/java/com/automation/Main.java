package com.automation;

import com.automation.comparator.SiteComparator;
import com.automation.extractor.ContentExtractor;
import com.automation.model.ComparisonResult;
import com.automation.model.SiteData;
import com.automation.report.ReportFormatter;
import com.automation.extractor.FingerprintCache;
import com.automation.report.DashboardGenerator;
import com.automation.report.DashboardGenerator.DashboardEntry;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    private static final ThreadLocal<Playwright> playwrightThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Browser> browserThreadLocal = new ThreadLocal<>();

    private static Browser getThreadLocalBrowser() {
        Browser browser = browserThreadLocal.get();
        if (browser == null) {
            Playwright playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setArgs(java.util.List.of("--headless=new")));
            playwrightThreadLocal.set(playwright);
            browserThreadLocal.set(browser);
        }
        return browser;
    }

    private static class UrlPair {
        final String urlA;
        final String urlB;

        UrlPair(String urlA, String urlB) {
            this.urlA = urlA;
            this.urlB = urlB;
        }
    }

    public static void main(String[] args) throws Exception {
        logger.info("========================================================");
        logger.info(" SITE CONTENT COMPARATOR");
        logger.info(" Automated Web Page Comparison Tool");
        logger.info("========================================================");

        File excelFile = resolveExcelFile(args);
        if (excelFile == null) {
            System.exit(1);
        }

        logger.info("Input: {}", excelFile.getAbsolutePath());
        List<UrlPair> pairs = readUrlPairs(excelFile);

        if (pairs.isEmpty()) {
            logger.error("No valid URL pairs found in the Excel file. Make sure Column A has Site A URLs and Column B has Site B URLs.");
            return;
        }

        logger.info("Found {} URL pair(s) to compare", pairs.size());

        FingerprintCache cache = new FingerprintCache(Config.getCacheTtlHours());
        SiteComparator comparator = new SiteComparator();

        int threads = Config.getThreads();
        logger.info("Starting thread pool with {} workers for parallel pair processing", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        
        AtomicInteger passed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger count = new AtomicInteger(1);

        List<Future<DashboardEntry>> futures = new ArrayList<>();

        try {
            for (UrlPair pair : pairs) {
                futures.add(pool.submit(() -> {
                    int currentCount = count.getAndIncrement();
                    logger.info("--- Pair {} / {} ------------------------------------------", currentCount, pairs.size());
                    logger.info("   Site A : {}", pair.urlA);
                    logger.info("   Site B : {}", pair.urlB);
                    
                    try {
                        Browser browser = getThreadLocalBrowser();
                        
                        ContentExtractor extractor = new ContentExtractor(browser, cache);
                        ReportFormatter reportFormatter = new ReportFormatter(browser);

                        logger.info("   [1/3] Loading both pages for Pair {}...", currentCount);
                        SiteData dataA = extractor.extractSiteData(pair.urlA, "A-Pair" + currentCount);
                        SiteData dataB;
                        if (normalizeUrl(pair.urlA).equals(normalizeUrl(pair.urlB))) {
                            logger.info("   [SAME URL] Identical URLs detected — reusing Site A data for Site B (no double load).");
                            dataB = new SiteData("B-Pair" + currentCount, pair.urlB,
                                    dataA.getRawText(), dataA.getImages(), dataA.getLinks(),
                                    dataA.getMetadata(), dataA.getDataLayer(), dataA.getFunctionalityComponents(),
                                    dataA.getExtractionTimeMillis());
                        } else {
                            dataB = extractor.extractSiteData(pair.urlB, "B-Pair" + currentCount);
                        }

                        logger.info("   [2/3] Comparing content for Pair {}...", currentCount);
                        ComparisonResult result = comparator.compare(dataA, dataB);

                        logger.info("   [3/3] Generating PDF report for Pair {}...", currentCount);
                        String basename = reportFormatter.generateReport(result);

                        if (result.isAllMatch()) {
                            logger.info("--- Result for Pair {}: PASS", currentCount);
                            passed.incrementAndGet();
                        } else {
                            logger.info("--- Result for Pair {}: MISMATCH", currentCount);
                            failed.incrementAndGet();
                        }
                        
                        return new DashboardEntry(currentCount, result, basename);

                    } catch (Exception e) {
                        logger.error("--- [ERROR] Pair {} failed: {}", currentCount, e.getMessage(), e);
                        failed.incrementAndGet();
                        return null;
                    }
                }));
            }

            List<DashboardEntry> entries = new ArrayList<>();
            for (Future<DashboardEntry> f : futures) {
                DashboardEntry entry = f.get();
                if (entry != null) {
                    entries.add(entry);
                }
            }
            
            // Generate the consolidated dashboard HTML
            DashboardGenerator.generate(entries, Paths.get("reports"));
            // Persist the fingerprint cache
            cache.persist();

        } finally {
            // Gracefully close all thread-local browser instances
            List<Future<?>> cleanups = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                cleanups.add(pool.submit(() -> {
                    Browser browser = browserThreadLocal.get();
                    if (browser != null) {
                        try { browser.close(); } catch (Exception ignored) {}
                        browserThreadLocal.remove();
                    }
                    Playwright playwright = playwrightThreadLocal.get();
                    if (playwright != null) {
                        try { playwright.close(); } catch (Exception ignored) {}
                        playwrightThreadLocal.remove();
                    }
                }));
            }
            for (Future<?> f : cleanups) {
                try { f.get(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            pool.shutdown();
        }
        
        logger.info("========================================================");
        logger.info(" DONE! Passed: {}   Mismatches: {}   Total: {}", passed.get(), failed.get(), (passed.get() + failed.get()));
        logger.info(" Reports saved to: reports\\");
        logger.info("========================================================");
    }

    private static File resolveExcelFile(String[] args) {
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.exists()) return f;
            logger.error("File not found: {}", f.getAbsolutePath());
            return null;
        }

        File inputDir = new File("data");
        if (inputDir.isDirectory()) {
            File[] xlsxFiles = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xlsx"));
            if (xlsxFiles != null && xlsxFiles.length == 1) {
                logger.info("Detected Excel file in data/ folder: {}", xlsxFiles[0].getName());
                return xlsxFiles[0];
            }
            if (xlsxFiles != null && xlsxFiles.length > 1) {
                java.util.Arrays.sort(xlsxFiles, java.util.Comparator.comparingLong(File::lastModified).reversed());
                logger.info("Multiple files in data/ folder — using the newest: {}", xlsxFiles[0].getName());
                return xlsxFiles[0];
            }
        }

        File fallback = new File("test_comparisons.xlsx");
        if (fallback.exists()) {
            logger.info("Using default file: test_comparisons.xlsx");
            return fallback;
        }

        logger.error("No Excel file found. Place your .xlsx file in the data\\ folder and try again.");
        return null;
    }

    /**
     * Normalises a URL for equality comparison:
     * lower-cases it, removes a trailing slash, and strips a leading "www.".
     */
    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String u = url.trim().toLowerCase();
        // strip trailing slash
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // treat www.example.com same as example.com
        u = u.replace("://www.", "://");
        return u;
    }

    private static List<UrlPair> readUrlPairs(File file) {
        List<UrlPair> pairs = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                Cell cellA = row.getCell(0);
                Cell cellB = row.getCell(1);
                if (cellA == null || cellB == null) continue;

                String valA = getCellStringValue(cellA);
                String valB = getCellStringValue(cellB);

                if (valA == null || valB == null) continue;

                if (valA.isBlank() || valB.isBlank()) continue;

                valA = valA.trim();
                valB = valB.trim();

                if (!valA.toLowerCase().startsWith("http") || !valB.toLowerCase().startsWith("http")) {
                    continue;
                }

                pairs.add(new UrlPair(valA, valB));
            }
        } catch (IOException e) {
            logger.error("Failed to read Excel file: {}", e.getMessage(), e);
        }
        return pairs;
    }

    private static String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                return new DataFormatter().formatCellValue(cell);
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return null;
                }
            default:
                return null;
        }
    }
}