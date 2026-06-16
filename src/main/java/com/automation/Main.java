package com.automation;

import com.automation.comparator.SiteComparator;
import com.automation.extractor.ContentExtractor;
import com.automation.model.ComparisonResult;
import com.automation.model.SiteData;
import com.automation.report.ReportFormatter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {

    static final String SITE_A = "https://books.toscrape.com";
    static final String SITE_B = "https://books.toscrape.com/catalogue/page-1.html";

    public static void main(String[] args) throws Exception {
        System.out.println("[INFO] Content Comparator Started");
        
        ContentExtractor extractor = new ContentExtractor();
        SiteComparator comparator = new SiteComparator();
        ReportFormatter reportFormatter = new ReportFormatter();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<SiteData> futureA = pool.submit(() -> extractor.extractSiteData(SITE_A, "A"));
        Future<SiteData> futureB = pool.submit(() -> extractor.extractSiteData(SITE_B, "B"));

        SiteData dataA = futureA.get();
        SiteData dataB = futureB.get();
        pool.shutdown();

        System.out.println("[INFO] Both sites extracted. Starting comparison...");
        ComparisonResult result = comparator.compare(dataA, dataB);

        reportFormatter.generateReport(result);
        
        System.out.println("[INFO] Content Comparator Finished");
    }
}