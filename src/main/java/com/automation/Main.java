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
        System.out.println("[INFO] Content Comparator Started");

        String excelPath = "automation/test_comparisons.xlsx";
        if (args.length > 0) {
            excelPath = args[0];
        }

        File excelFile = new File(excelPath);
        if (!excelFile.exists()) {
            System.err.println("[ERROR] Excel file not found: " + excelFile.getAbsolutePath());
            System.out.println("Please create comparisons.xlsx or specify file path as program argument.");
            System.exit(1);
        }

        System.out.println("[INFO] Reading comparisons from Excel: " + excelFile.getAbsolutePath());
        List<UrlPair> pairs = readUrlPairs(excelFile);
        System.out.println("[INFO] Found " + pairs.size() + " valid URL pair(s) to process.");

        if (pairs.isEmpty()) {
            System.out.println("[INFO] No pairs to process. Exiting.");
            return;
        }

        ContentExtractor extractor = new ContentExtractor();
        SiteComparator comparator = new SiteComparator();
        ReportFormatter reportFormatter = new ReportFormatter();

        // Create thread pool for parallel extraction (Site A and Site B in parallel per
        // pair)
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            int count = 1;
            for (UrlPair pair : pairs) {
                System.out.println("\n[INFO] --------------------------------------------------");
                System.out.println("[INFO] Processing Pair " + count + " of " + pairs.size());
                System.out.println("[INFO] Site A: " + pair.urlA);
                System.out.println("[INFO] Site B: " + pair.urlB);
                System.out.println("[INFO] --------------------------------------------------");

                try {
                    Future<SiteData> futureA = pool.submit(() -> extractor.extractSiteData(pair.urlA, "A"));
                    Future<SiteData> futureB = pool.submit(() -> extractor.extractSiteData(pair.urlB, "B"));

                    SiteData dataA = futureA.get();
                    SiteData dataB = futureB.get();

                    System.out.println("[INFO] Both sites extracted. Starting comparison...");
                    ComparisonResult result = comparator.compare(dataA, dataB);

                    reportFormatter.generateReport(result);
                } catch (Exception e) {
                    System.err.println("[ERROR] Failed to compare pair: " + e.getMessage());
                }
                count++;
            }
        } finally {
            pool.shutdown();
        }

        System.out.println("\n[INFO] Content Comparator Finished");
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