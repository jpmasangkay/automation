package com.automation;

import com.automation.comparator.SiteComparator;
import com.automation.extractor.ContentExtractor;
import com.automation.model.ComparisonResult;
import com.automation.model.SiteData;
import com.automation.report.ReportFormatter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {

    private static class UrlPair {
        final String urlA;
        final String urlB;

        UrlPair(String urlA, String urlB) {
            this.urlA = urlA;
            this.urlB = urlB;
        }
    }

    public static void main(String[] args) throws Exception {
        printBanner();

        // ── Resolve Excel file path ──────────────────────────────────────
        File excelFile = resolveExcelFile(args);
        if (excelFile == null) {
            System.exit(1);
        }

        System.out.println("  Input  : " + excelFile.getAbsolutePath());
        System.out.println();

        List<UrlPair> pairs = readUrlPairs(excelFile);

        if (pairs.isEmpty()) {
            System.out.println("  [!] No valid URL pairs found in the Excel file.");
            System.out.println("      Make sure Column A has Site A URLs and Column B has Site B URLs.");
            System.out.println("      Both must start with http:// or https://");
            return;
        }

        System.out.println("  Found  : " + pairs.size() + " URL pair(s) to compare");
        System.out.println();

        ContentExtractor extractor = new ContentExtractor();
        SiteComparator comparator = new SiteComparator();
        ReportFormatter reportFormatter = new ReportFormatter();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int passed = 0, failed = 0;

        try {
            int count = 1;
            for (UrlPair pair : pairs) {
                System.out.println("  ┌─ Pair " + count + " / " + pairs.size()
                        + " ──────────────────────────────────────────");
                System.out.println("  │  Site A : " + pair.urlA);
                System.out.println("  │  Site B : " + pair.urlB);
                System.out.println("  │");
                System.out.println("  │  [1/3] Loading both pages (this may take 30-60 sec)...");

                try {
                    Future<SiteData> futureA = pool.submit(() -> extractor.extractSiteData(pair.urlA, "A"));
                    Future<SiteData> futureB = pool.submit(() -> extractor.extractSiteData(pair.urlB, "B"));

                    SiteData dataA = futureA.get();
                    SiteData dataB = futureB.get();

                    System.out.println("  │  [2/3] Comparing content...");
                    ComparisonResult result = comparator.compare(dataA, dataB);

                    System.out.println("  │  [3/3] Generating PDF report...");
                    reportFormatter.generateReport(result);

                    String verdict = result.isAllMatch() ? "PASS ✓" : "MISMATCH ✗";
                    System.out.println("  │");
                    System.out.println("  └─ Result: " + verdict);

                    if (result.isAllMatch()) passed++;
                    else failed++;

                } catch (Exception e) {
                    System.out.println("  └─ [ERROR] " + e.getMessage());
                    failed++;
                }

                System.out.println();
                count++;
            }
        } finally {
            pool.shutdown();
        }

        // ── Final summary ────────────────────────────────────────────────
        System.out.println("  ════════════════════════════════════════════════════════");
        System.out.println("   DONE!   Passed: " + passed + "   Mismatches: " + failed
                + "   Total: " + (passed + failed));
        System.out.println("   Reports saved to:  reports\\");
        System.out.println("  ════════════════════════════════════════════════════════");
        System.out.println();
    }

    /** Resolves the Excel file: from arg, from data/ folder, or fallback. */
    private static File resolveExcelFile(String[] args) {
        // 1. Explicit argument (e.g. from run.bat drag-and-drop)
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.exists()) return f;
            System.err.println("  [ERROR] File not found: " + f.getAbsolutePath());
            return null;
        }

        // 2. Scan the dedicated data/ folder
        File inputDir = new File("data");
        if (inputDir.isDirectory()) {
            File[] xlsxFiles = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xlsx"));
            if (xlsxFiles != null && xlsxFiles.length == 1) {
                System.out.println("  [AUTO] Detected Excel file in data/ folder: " + xlsxFiles[0].getName());
                return xlsxFiles[0];
            }
            if (xlsxFiles != null && xlsxFiles.length > 1) {
                System.out.println("  [INFO] Multiple files in data/ folder — using the first one found.");
                System.out.println("         Tip: Use run.bat to choose interactively.");
                return xlsxFiles[0];
            }
        }

        // 3. Fallback to test_comparisons.xlsx in the working directory
        File fallback = new File("test_comparisons.xlsx");
        if (fallback.exists()) {
            System.out.println("  [AUTO] Using default file: test_comparisons.xlsx");
            return fallback;
        }

        System.err.println("  [ERROR] No Excel file found.");
        System.err.println("          Place your .xlsx file in the  data\\  folder and try again.");
        return null;
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ════════════════════════════════════════════════════════");
        System.out.println("   SITE CONTENT COMPARATOR");
        System.out.println("   Automated Web Page Comparison Tool");
        System.out.println("  ════════════════════════════════════════════════════════");
        System.out.println();
    }

    private static List<UrlPair> readUrlPairs(File file) {
        List<UrlPair> pairs = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
                Workbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                Cell cellA = row.getCell(0);
                Cell cellB = row.getCell(1);
                if (cellA == null || cellB == null)
                    continue;

                String valA = getCellStringValue(cellA);
                String valB = getCellStringValue(cellB);

                if (valA == null || valB == null)
                    continue;

                valA = valA.trim();
                valB = valB.trim();

                // Skip rows containing titles or non-HTTP links (e.g. headers: "Site A", "Site
                // B")
                if (!valA.toLowerCase().startsWith("http") || !valB.toLowerCase().startsWith("http")) {
                    continue;
                }

                pairs.add(new UrlPair(valA, valB));
            }
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to read Excel file: " + e.getMessage());
        }
        return pairs;
    }

    private static String getCellStringValue(Cell cell) {
        if (cell == null)
            return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
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