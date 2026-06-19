package com.automation.report;

import com.automation.model.ComparisonResult;
import com.automation.model.ComparisonResult.*;
import com.automation.model.LinkData;
import com.automation.model.SimilarityScores;
import com.automation.model.SiteData;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ReportFormatter {

    private static final Logger logger = LoggerFactory.getLogger(ReportFormatter.class);
    private final Browser browser;

    public ReportFormatter(Browser browser) {
        this.browser = browser;
    }

    /**
     * Generates the PDF report for one comparison pair.
     *
     * @return the base filename (without extension) so the dashboard can link to it
     */
    public String generateReport(ComparisonResult result) {
        String html = buildHtml(result);

        Path reportsDir = Paths.get("reports");
        try {
            if (!Files.exists(reportsDir)) Files.createDirectories(reportsDir);
        } catch (IOException e) {
            logger.warn("Failed to create reports directory: {}", e.getMessage());
            reportsDir = Paths.get(".");
        }

        String baseFilename = buildFilename(result.siteA().getUrl(), result.siteB().getUrl());
        Path pdfPath  = reportsDir.resolve(baseFilename + ".pdf");

        try (BrowserContext context = browser.newContext();
             Page page = context.newPage()) {
            page.setContent(html);
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            page.pdf(new Page.PdfOptions()
                    .setPath(pdfPath)
                    .setPrintBackground(true)
                    .setFormat("A4")
                    .setMargin(new Margin().setTop("18mm").setBottom("18mm").setLeft("16mm").setRight("16mm")));
            logger.info("Report saved: {}", pdfPath.toAbsolutePath());
        } catch (Exception e) {
            logger.error("PDF generation failed: {}", e.getMessage());
        }

        return baseFilename;
    }

    // ── HTML ──────────────────────────────────────────────────────────────────

    private String buildHtml(ComparisonResult result) {
        SiteData a = result.siteA();
        SiteData b = result.siteB();
        SimilarityScores s = result.similarityScores();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        sb.append("<title>Content Comparison Report</title>");
        sb.append("<style>").append(css()).append("</style></head><body>");

        // ── Report Header
        sb.append("<div class='page-header'>");
        sb.append("<div class='report-title'>Content Comparison Report</div>");
        sb.append("<table class='header-info'><tbody>");
        sb.append("<tr><td class='info-label'>Generated</td><td>").append(ts).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Site A</td><td>").append(esc(a.getUrl())).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Site B</td><td>").append(esc(b.getUrl())).append("</td></tr>");
        sb.append("<tr><td class='info-label'>Performance</td><td>")
          .append("Extraction A: ").append(a.getExtractionTimeMillis()).append("ms, ")
          .append("Extraction B: ").append(b.getExtractionTimeMillis()).append("ms, ")
          .append("Comparison: ").append(result.comparisonTimeMillis()).append("ms")
          .append("</td></tr>");
        sb.append("</tbody></table></div>");

        // ── Similarity Score Banner
        sb.append("<div class='block score-banner'>");
        sb.append("<div class='section-title'>Similarity Scores</div>");
        sb.append("<div class='score-cards'>");
        scoreCard(sb, "Text",       s.textScore());
        scoreCard(sb, "Images",     s.imageScore());
        scoreCard(sb, "Links",      s.linkScore());
        scoreCard(sb, "Metadata",   s.metadataScore());
        scoreCard(sb, "DataLayer",  s.dataLayerScore());
        sb.append("</div>");
        // Overall score pill
        sb.append("<div class='overall-score-row'>");
        sb.append("<span class='overall-pill ").append(s.overallColorClass()).append("'>")
          .append("Overall Similarity: ").append(String.format("%.1f%%", s.overallScore()))
          .append(" — ").append(s.overallLabel()).append("</span>");
        sb.append("</div>");
        sb.append("</div>");

        // ── Summary Table
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>Summary</div>");
        sb.append("<table><thead><tr><th>Check</th><th>Site A</th><th>Site B</th><th>Similarity</th><th>Result</th></tr></thead><tbody>");
        summaryRow(sb, "Metadata",  a.getMetadata().size() + " tag(s)",           b.getMetadata().size() + " tag(s)",           s.metadataScore(),   result.metadataDiff().matches());
        summaryRow(sb, "DataLayer", a.getDataLayer().size() + " property(ies)",   b.getDataLayer().size() + " property(ies)",   s.dataLayerScore(),  result.dataLayerDiff().matches());
        summaryRow(sb, "Text",      result.textDiff().totalLinesA() + " line(s)", result.textDiff().totalLinesB() + " line(s)", s.textScore(),       result.textDiff().matches());
        summaryRow(sb, "Images",    a.getImages().size() + " image(s)",           b.getImages().size() + " image(s)",           s.imageScore(),      result.imageDiff().matches());
        summaryRow(sb, "Links",     a.getLinks().size() + " link(s)",             b.getLinks().size() + " link(s)",             s.linkScore(),       result.linkDiff().matches());
        
        double funcScore = 100.0;
        if (!result.functionalityDiff().matches()) {
            int fu = a.getFunctionalityComponents().size() + result.functionalityDiff().onlyInB().size();
            int fm = result.functionalityDiff().onlyInA().size() + result.functionalityDiff().onlyInB().size() + result.functionalityDiff().valueDiffs().size();
            funcScore = fu == 0 ? 100.0 : Math.max(0.0, (fu - fm) * 100.0 / fu);
        }
        summaryRow(sb, "Functionality", a.getFunctionalityComponents().size() + " type(s)", b.getFunctionalityComponents().size() + " type(s)", funcScore, result.functionalityDiff().matches());
        
        sb.append("</tbody></table>");
        sb.append("<div class='final-result ").append(result.isAllMatch() ? "final-pass" : "final-fail").append("'>");
        sb.append("Overall: ").append(result.isAllMatch() ? "PASS" : "MISMATCH");
        sb.append("</div></div>");

        // ── Section 1: Metadata
        sb.append(renderMapDiff("1. Metadata", result.metadataDiff(), a.getMetadata(), b.getMetadata()));
        // ── Section 2: DataLayer
        sb.append(renderMapDiff("2. Site Data Layer", result.dataLayerDiff(), a.getDataLayer(), b.getDataLayer()));

        // ── Section 3: Text
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>3. Text Content <span class='result-inline ")
          .append(result.textDiff().matches() ? "pass" : "fail").append("'>")
          .append(result.textDiff().matches() ? "PASS" : "MISMATCH").append("</span></div>");
        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.textDiff().matchedLineCount()).append(" text line(s)</td></tr>");
        sb.append("<tr><td class='stat-label'>Differences</td><td>")
          .append(result.textDiff().onlyInA().size() + result.textDiff().onlyInB().size()).append(" text line(s)</td></tr>");
        sb.append("</tbody></table>");

        if (result.textDiff().matches()) {
            sb.append("<p class='note'>Content is identical. Showing first 60 text lines from each site.</p>");
            sb.append("<table><thead><tr><th style='width:50%'>Site A Text</th><th style='width:50%'>Site B Text</th></tr></thead><tbody>");
            String[] tlA = a.getRawText().split("\\n");
            String[] tlB = b.getRawText().split("\\n");
            int max = Math.min(Math.max(tlA.length, tlB.length), 60);
            for (int i = 0; i < max; i++) {
                String la = (i < tlA.length) ? tlA[i].trim() : "";
                String lb = (i < tlB.length) ? tlB[i].trim() : "";
                if (la.isEmpty() && lb.isEmpty()) continue;
                sb.append("<tr><td>").append(esc(la)).append("</td><td>").append(esc(lb)).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        } else {
            sb.append("<table><thead><tr><th style='width:50%'>Site A (Old)</th><th style='width:50%'>Site B (New)</th></tr></thead><tbody>");
            try {
                DiffRowGenerator gen = DiffRowGenerator.create()
                        .showInlineDiffs(true).inlineDiffByWord(true)
                        .oldTag(f -> f ? "<del style='background:#ffebe9;font-weight:bold;text-decoration:none'>" : "</del>")
                        .newTag(f -> f ? "<ins style='background:#e6ffec;font-weight:bold;text-decoration:none'>" : "</ins>")
                        .build();
                List<String> aLines = Arrays.stream(a.getRawText().split("\\n")).map(String::trim).filter(x -> !x.isEmpty()).toList();
                List<String> bLines = Arrays.stream(b.getRawText().split("\\n")).map(String::trim).filter(x -> !x.isEmpty()).toList();
                List<DiffRow> rows = gen.generateDiffRows(aLines, bLines);
                int shown = 0;
                for (DiffRow row : rows) {
                    if (row.getTag() == DiffRow.Tag.EQUAL) continue;
                    if (shown++ > 100) { sb.append("<tr><td colspan='2' style='text-align:center'><i>…and more differences</i></td></tr>"); break; }
                    sb.append("<tr><td>").append(row.getOldLine()).append("</td><td>").append(row.getNewLine()).append("</td></tr>");
                }
            } catch (Exception e) {
                logger.error("Inline text diff failed", e);
                sb.append("<tr><td colspan='2'>Failed to generate inline diff</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div>");

        // ── Section 4: Images
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>4. Images <span class='result-inline ")
          .append(result.imageDiff().matches() ? "pass" : "fail").append("'>")
          .append(result.imageDiff().matches() ? "PASS" : "MISMATCH").append("</span></div>");
        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.imageDiff().matchedImagesCount()).append("</td></tr>");
        if (result.imageDiff().visualMatchImagesCount() > 0)
            sb.append("<tr><td class='stat-label'>Visual Match</td><td class='status-cell warn'>").append(result.imageDiff().visualMatchImagesCount()).append(" (same image, different path/format)</td></tr>");
        sb.append("<tr><td class='stat-label'>Mismatched</td><td>").append(result.imageDiff().mismatchCount()).append("</td></tr>");
        sb.append("</tbody></table>");
        sb.append("<table><thead><tr><th style='width:31%'>Site A Source</th><th style='width:10%'>A Hash</th><th style='width:31%'>Site B Source</th><th style='width:10%'>B Hash</th><th style='width:14%'>Status</th></tr></thead><tbody>");
        for (ImageMatch m : result.imageDiff().matchesList()) {
            boolean isPass = m.status().equals("MATCH") || m.status().equals("VISUAL MATCH");
            String cls = m.status().equals("MATCH") ? "pass" : m.status().equals("VISUAL MATCH") ? "warn" : "fail";
            sb.append("<tr").append(isPass ? "" : " class='row-fail'").append(">");
            sb.append("<td class='src-cell'>").append(m.imgA() != null ? esc(m.imgA().getSrc()) : "<em class='na'>N/A</em>").append("</td>");
            sb.append("<td class='hash-cell'>").append(m.imgA() != null ? m.imgA().getHash().substring(0, 8) + "…" : "N/A").append("</td>");
            sb.append("<td class='src-cell'>").append(m.imgB() != null ? esc(m.imgB().getSrc()) : "<em class='na'>N/A</em>").append("</td>");
            sb.append("<td class='hash-cell'>").append(m.imgB() != null ? m.imgB().getHash().substring(0, 8) + "…" : "N/A").append("</td>");
            sb.append("<td class='status-cell ").append(cls).append("'>").append(m.status()).append("</td></tr>");
        }
        sb.append("</tbody></table></div>");

        // ── Section 5: Links
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>5. Links <span class='result-inline ")
          .append(result.linkDiff().matches() ? "pass" : "fail").append("'>")
          .append(result.linkDiff().matches() ? "PASS" : "MISMATCH").append("</span></div>");
        sb.append("<table class='stat-table'><tbody>");
        sb.append("<tr><td class='stat-label'>Matched</td><td>").append(result.linkDiff().matchedLinksCount()).append("</td></tr>");
        sb.append("<tr><td class='stat-label'>Only in A</td><td>").append(result.linkDiff().onlyInA().size()).append("</td></tr>");
        sb.append("<tr><td class='stat-label'>Only in B</td><td>").append(result.linkDiff().onlyInB().size()).append("</td></tr>");
        sb.append("</tbody></table>");

        Map<String, LinkData> slugA = new LinkedHashMap<>(), slugB = new LinkedHashMap<>();
        for (LinkData l : a.getLinks()) slugA.put(l.getSlug(), l);
        for (LinkData l : b.getLinks()) slugB.put(l.getSlug(), l);
        Set<String> allSlugs = new TreeSet<>(); allSlugs.addAll(slugA.keySet()); allSlugs.addAll(slugB.keySet());

        sb.append("<table><thead><tr><th style='width:20%'>Link Text</th><th style='width:20%'>Slug</th><th style='width:25%'>Site A Href</th><th style='width:25%'>Site B Href</th><th style='width:10%'>Status</th></tr></thead><tbody>");
        for (String slug : allSlugs) {
            LinkData lA = slugA.get(slug), lB = slugB.get(slug);
            String hA = lA != null ? lA.getOriginalHref() : null;
            String hB = lB != null ? lB.getOriginalHref() : null;
            String lt = lA != null ? lA.getLinkText() : (lB != null ? lB.getLinkText() : "");
            boolean match = hA != null && hB != null;
            sb.append("<tr").append(match ? "" : " class='row-fail'").append(">");
            sb.append("<td class='src-cell'>").append(esc(lt)).append("</td>");
            sb.append("<td class='hash-cell'>").append(esc(slug)).append("</td>");
            sb.append("<td class='src-cell'>").append(hA != null ? esc(hA) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='src-cell'>").append(hB != null ? esc(hB) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='status-cell ").append(match ? "pass" : "fail").append("'>")
              .append(match ? "MATCH" : (hA == null ? "ONLY IN B" : "ONLY IN A")).append("</td></tr>");
        }
        sb.append("</tbody></table></div>");

        // ── Section 6: Functionality Components
        sb.append(renderMapDiff("6. Functionality Components", result.functionalityDiff(), a.getFunctionalityComponents(), b.getFunctionalityComponents()));

        // ── Section 7: Additional Plugin Rule Results
        if (!result.additionalRuleResults().isEmpty()) {
            sb.append("<div class='block'>");
            sb.append("<div class='section-title'>7. Custom Rule Results</div>");
            sb.append("<table><thead><tr><th>Rule</th><th>Site A</th><th>Site B</th><th>Similarity</th><th>Status</th></tr></thead><tbody>");
            for (var cr : result.additionalRuleResults()) {
                String cls = cr.matches() ? "pass" : "fail";
                sb.append("<tr").append(cr.matches() ? "" : " class='row-fail'").append(">");
                sb.append("<td class='key-cell'>").append(esc(cr.ruleName())).append("</td>");
                sb.append("<td>").append(esc(cr.countA())).append("</td>");
                sb.append("<td>").append(esc(cr.countB())).append("</td>");
                sb.append("<td>").append(String.format("%.1f%%", cr.similarityPercent())).append("</td>");
                sb.append("<td class='status-cell ").append(cls).append("'>").append(cr.verdict()).append("</td></tr>");
                if (!cr.issues().isEmpty()) {
                    sb.append("<tr><td colspan='5' style='padding:4px 14px 8px'><ul style='margin:0;padding-left:18px;color:#888'>");
                    for (String issue : cr.issues()) sb.append("<li>").append(esc(issue)).append("</li>");
                    sb.append("</ul></td></tr>");
                }
            }
            sb.append("</tbody></table></div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    // ── Report component builders ─────────────────────────────────────────────

    private void scoreCard(StringBuilder sb, String label, double score) {
        String cls = scoreColorClass(score);
        sb.append("<div class='score-card ").append(cls).append("'>");
        sb.append("<div class='score-val'>").append(String.format("%.1f%%", score)).append("</div>");
        sb.append("<div class='score-lbl'>").append(label).append("</div>");
        sb.append("</div>");
    }

    private String scoreColorClass(double score) {
        if (score >= 95) return "score-excellent";
        if (score >= 80) return "score-good";
        if (score >= 60) return "score-fair";
        return "score-poor";
    }

    private String renderMapDiff(String title, MapDiff diff, Map<String, String> mapA, Map<String, String> mapB) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='block'>");
        sb.append("<div class='section-title'>").append(title).append(" <span class='result-inline ")
          .append(diff.matches() ? "pass" : "fail").append("'>")
          .append(diff.matches() ? "PASS" : "MISMATCH").append("</span></div>");
        sb.append("<table><thead><tr><th>Key</th><th>Site A Value</th><th>Site B Value</th><th>Status</th></tr></thead><tbody>");
        Set<String> allKeys = new LinkedHashSet<>(); allKeys.addAll(mapA.keySet()); allKeys.addAll(mapB.keySet());
        for (String key : allKeys) {
            String va = mapA.get(key), vb = mapB.get(key);
            boolean isDiff = !Objects.equals(va, vb);
            sb.append("<tr").append(isDiff ? " class='row-fail'" : "").append(">");
            sb.append("<td class='key-cell'>").append(esc(key)).append("</td>");
            sb.append("<td>").append(va != null ? esc(va) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td>").append(vb != null ? esc(vb) : "<em class='na'>not present</em>").append("</td>");
            sb.append("<td class='status-cell ").append(isDiff ? "fail" : "pass").append("'>")
              .append(isDiff ? (va == null ? "MISSING IN A" : vb == null ? "MISSING IN B" : "DIFF") : "MATCH")
              .append("</td></tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private void summaryRow(StringBuilder sb, String check, String va, String vb, double score, boolean pass) {
        sb.append("<tr><td class='key-cell'>").append(check).append("</td>");
        sb.append("<td>").append(va).append("</td>");
        sb.append("<td>").append(vb).append("</td>");
        sb.append("<td><span class='score-inline ").append(scoreColorClass(score)).append("'>")
          .append(String.format("%.1f%%", score)).append("</span></td>");
        sb.append("<td class='status-cell ").append(pass ? "pass" : "fail").append("'>")
          .append(pass ? "PASS" : "MISMATCH").append("</td></tr>");
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public static String buildFilename(String urlA, String urlB) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String hash = "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest((urlA + urlB).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", bytes[i]));
            hash = "_" + sb;
        } catch (Exception ignored) {}
        return extractDomain(urlA) + "_vs_" + extractDomain(urlB) + hash + "_" + ts;
    }

    private static String extractDomain(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null ? host.replaceAll("[^a-zA-Z0-9._-]", "_") : "unknown";
        } catch (Exception e) { return "unknown"; }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String css() {
        try (InputStream is = getClass().getResourceAsStream("/report.css")) {
            if (is != null) {
                String base = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return base + extraCss();
            }
        } catch (Exception ignored) {}
        return extraCss();
    }

    private String extraCss() {
        return """
.block.score-banner { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px 24px; margin-bottom: 20px; }
.score-banner .section-title { color: #0f172a !important; font-size: 11pt !important; font-weight: 700 !important; letter-spacing: 0.05em !important; margin-bottom: 12px !important; text-transform: uppercase !important; }
.score-cards { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 12px; margin-bottom: 16px; }
.score-card { flex: 1; min-width: 100px; padding: 12px 14px; border-radius: 8px; border: 1px solid transparent; text-align: center; }
.score-card.score-excellent { background: #f0fdf4; border-color: #bbf7d0; }
.score-card.score-good      { background: #f7fee7; border-color: #d9f99d; }
.score-card.score-fair      { background: #fffbeb; border-color: #fde68a; }
.score-card.score-poor      { background: #fef2f2; border-color: #fecaca; }
.score-val { font-size: 1.35rem; font-weight: 700; line-height: 1.2; }
.score-lbl { font-size: .75rem; color: #475569; text-transform: uppercase; letter-spacing: .05em; margin-top: 4px; font-weight: 600; }
.score-card.score-excellent .score-val { color: #16a34a; }
.score-card.score-good      .score-val { color: #4d7c0f; }
.score-card.score-fair      .score-val { color: #d97706; }
.score-card.score-poor      .score-val { color: #dc2626; }
.overall-score-row { margin-top: 8px; }
.overall-pill { display: inline-block; padding: 6px 16px; border-radius: 99px; font-weight: 600; font-size: .85rem; border: 1px solid transparent; }
.overall-pill.score-excellent { background: #dcfce7; color: #15803d; border-color: #bbf7d0; }
.overall-pill.score-good      { background: #f0fdf4; color: #16a34a; border-color: #bbf7d0; }
.overall-pill.score-fair      { background: #fef3c7; color: #d97706; border-color: #fde68a; }
.overall-pill.score-poor      { background: #fee2e2; color: #b91c1c; border-color: #fecaca; }
.score-inline { display: inline-block; padding: 2px 8px; border-radius: 99px; font-size: .78rem; font-weight: 600; border: 1px solid transparent; }
.score-inline.score-excellent { background: #dcfce7; color: #15803d; border-color: #bbf7d0; }
.score-inline.score-good      { background: #f0fdf4; color: #16a34a; border-color: #bbf7d0; }
.score-inline.score-fair      { background: #fef3c7; color: #d97706; border-color: #fde68a; }
.score-inline.score-poor      { background: #fee2e2; color: #b91c1c; border-color: #fecaca; }
""";
    }
}
