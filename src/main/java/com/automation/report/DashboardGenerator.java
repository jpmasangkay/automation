package com.automation.report;

import com.automation.model.ComparisonResult;
import com.automation.model.SimilarityScores;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates a single {@code reports/dashboard.html} after all comparison pairs have completed.
 * The dashboard provides a sortable, filterable, at-a-glance summary with links to each
 * individual HTML report. Individual PDFs are unaffected.
 */
public class DashboardGenerator {

    public record DashboardEntry(int pairNumber, ComparisonResult result, String reportBasename) {}

    /**
     * Generates the dashboard HTML file.
     *
     * @param entries    ordered list of all comparison entries from this run
     * @param reportsDir the directory where individual reports (and the dashboard) are saved
     */
    public static void generate(List<DashboardEntry> entries, Path reportsDir) {
        if (entries == null || entries.isEmpty()) return;
        try {
            if (!Files.exists(reportsDir)) Files.createDirectories(reportsDir);
            String html = buildDashboard(entries);
            Path dashPath = reportsDir.resolve("dashboard.html");
            Files.writeString(dashPath, html, StandardCharsets.UTF_8);
            System.out.println("[INFO] Dashboard generated: " + dashPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to generate dashboard: " + e.getMessage());
        }
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private static String buildDashboard(List<DashboardEntry> entries) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long passed = entries.stream().filter(e -> e.result().isAllMatch()).count();
        long failed = entries.size() - passed;
        double avgScore = entries.stream()
                .mapToDouble(e -> e.result().similarityScores().overallScore())
                .average().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Comparison Run Dashboard</title>
<style>
""");
        sb.append(css());
        sb.append("""
</style>
</head>
<body>
""");

        // ── Header ────────────────────────────────────────────────────────────
        sb.append("<header class=\"dash-header\">\n");
        sb.append("  <div class=\"header-inner\">\n");
        sb.append("    <div class=\"brand\"><span class=\"brand-icon\">⚡</span> Site Content Comparator</div>\n");
        sb.append("    <div class=\"run-meta\">Run completed: <strong>").append(esc(ts)).append("</strong></div>\n");
        sb.append("  </div>\n</header>\n");

        // ── KPI cards ─────────────────────────────────────────────────────────
        sb.append("<main class=\"dash-main\">\n");
        sb.append("<section class=\"kpi-row\">\n");
        kpiCard(sb, "Total Pairs",  String.valueOf(entries.size()), "kpi-neutral");
        kpiCard(sb, "Passed",       String.valueOf(passed),         "kpi-pass");
        kpiCard(sb, "Failed",       String.valueOf(failed),         failed > 0 ? "kpi-fail" : "kpi-neutral");
        kpiCard(sb, "Avg Similarity", String.format("%.1f%%", avgScore), scoreClass(avgScore));
        sb.append("</section>\n");

        // ── Filter bar ────────────────────────────────────────────────────────
        sb.append("""
<section class="filter-bar">
  <span class="filter-label">Filter:</span>
  <button class="filter-btn active" data-filter="all"  onclick="applyFilter('all')">All</button>
  <button class="filter-btn"        data-filter="pass" onclick="applyFilter('pass')">✅ Pass</button>
  <button class="filter-btn"        data-filter="fail" onclick="applyFilter('fail')">❌ Fail</button>
  <input  class="search-box" type="search" placeholder="Search URL…" oninput="applySearch(this.value)" />
</section>
""");

        // ── Table ─────────────────────────────────────────────────────────────
        sb.append("""
<section class="table-wrap">
<table id="dash-table">
<thead>
<tr>
  <th class="col-num sortable" onclick="sortBy('num')">#</th>
  <th class="sortable" onclick="sortBy('siteA')">Site A <span class="sort-icon">⇅</span></th>
  <th class="sortable" onclick="sortBy('siteB')">Site B <span class="sort-icon">⇅</span></th>
  <th class="col-score sortable" onclick="sortBy('text')">Text <span class="sort-icon">⇅</span></th>
  <th class="col-score sortable" onclick="sortBy('img')">Images <span class="sort-icon">⇅</span></th>
  <th class="col-score sortable" onclick="sortBy('links')">Links <span class="sort-icon">⇅</span></th>
  <th class="col-score sortable" onclick="sortBy('meta')">Meta <span class="sort-icon">⇅</span></th>
  <th class="col-score sortable" onclick="sortBy('dl')">DataLayer <span class="sort-icon">⇅</span></th>
  <th class="col-overall sortable" onclick="sortBy('overall')">Overall <span class="sort-icon">⇅</span></th>
  <th>Verdict</th>
  <th>Report</th>
</tr>
</thead>
<tbody id="table-body">
""");

        for (DashboardEntry entry : entries) {
            ComparisonResult r = entry.result();
            SimilarityScores s = r.similarityScores();
            boolean pass = r.isAllMatch();
            String rowClass = pass ? "row-pass" : "row-fail";
            String verdict  = pass ? "<span class=\"badge badge-pass\">PASS</span>"
                                   : "<span class=\"badge badge-fail\">FAIL</span>";
            String reportLink = "<a class=\"btn-open-pdf\" href=\"" + esc(entry.reportBasename()) +
                    ".pdf\"  target=\"_blank\">📄 Open PDF</a>";

            sb.append("<tr class=\"").append(rowClass).append("\"")
              .append(" data-verdict=\"").append(pass ? "pass" : "fail").append("\"")
              .append(" data-sitea=\"").append(esc(r.siteA().getUrl().toLowerCase())).append("\"")
              .append(" data-siteb=\"").append(esc(r.siteB().getUrl().toLowerCase())).append("\"")
              .append(">\n");

            sb.append("  <td class=\"col-num\">").append(entry.pairNumber()).append("</td>\n");
            sb.append("  <td class=\"url-cell\" title=\"").append(esc(r.siteA().getUrl())).append("\">")
              .append(esc(truncateUrl(r.siteA().getUrl()))).append("</td>\n");
            sb.append("  <td class=\"url-cell\" title=\"").append(esc(r.siteB().getUrl())).append("\">")
              .append(esc(truncateUrl(r.siteB().getUrl()))).append("</td>\n");

            scoreCell(sb, s.textScore());
            scoreCell(sb, s.imageScore());
            scoreCell(sb, s.linkScore());
            scoreCell(sb, s.metadataScore());
            scoreCell(sb, s.dataLayerScore());

            // Overall with progress bar
            sb.append("  <td class=\"col-overall\">\n");
            sb.append("    <div class=\"overall-wrap\">\n");
            sb.append("      <span class=\"score-label ").append(s.overallColorClass()).append("\">")
              .append(String.format("%.1f%%", s.overallScore())).append("</span>\n");
            sb.append("      <div class=\"progress-bar\"><div class=\"progress-fill ").append(s.overallColorClass())
              .append("\" style=\"width:").append(String.format("%.1f", s.overallScore())).append("%\"></div></div>\n");
            sb.append("    </div>\n  </td>\n");

            sb.append("  <td>").append(verdict).append("</td>\n");
            sb.append("  <td>").append(reportLink).append("</td>\n");
            sb.append("</tr>\n");
        }

        sb.append("</tbody>\n</table>\n</section>\n</main>\n");

        // ── JS ────────────────────────────────────────────────────────────────
        sb.append("<script>\n").append(js()).append("\n</script>\n");
        sb.append("</body>\n</html>");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void kpiCard(StringBuilder sb, String label, String value, String cls) {
        sb.append("<div class=\"kpi-card ").append(cls).append("\">\n");
        sb.append("  <div class=\"kpi-value\">").append(value).append("</div>\n");
        sb.append("  <div class=\"kpi-label\">").append(label).append("</div>\n");
        sb.append("</div>\n");
    }

    private static void scoreCell(StringBuilder sb, double score) {
        sb.append("  <td class=\"col-score\"><span class=\"score-badge ").append(scoreClass(score))
          .append("\">").append(String.format("%.0f%%", score)).append("</span></td>\n");
    }

    private static String scoreClass(double score) {
        if (score >= 95) return "score-excellent";
        if (score >= 80) return "score-good";
        if (score >= 60) return "score-fair";
        return "score-poor";
    }

    private static String truncateUrl(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost() != null ? uri.getHost() : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            String combined = host + path;
            return combined.length() > 55 ? combined.substring(0, 52) + "…" : combined;
        } catch (Exception e) {
            return url.length() > 55 ? url.substring(0, 52) + "…" : url;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── CSS ───────────────────────────────────────────────────────────────────

    private static String css() {
        return """
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap');
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
:root {
  --bg:        #080d18;
  --surface:   #0f1629;
  --surface2:  #151d35;
  --border:    rgba(99,130,255,.15);
  --text:      #e2e8f0;
  --muted:     #8892a4;
  --accent:    #6382ff;
  --pass:      #22c55e;
  --fail:      #ef4444;
  --warn:      #f59e0b;
  --excellent: #22c55e;
  --good:      #84cc16;
  --fair:      #f59e0b;
  --poor:      #ef4444;
  --radius:    12px;
}
body { font-family: 'Inter', system-ui, sans-serif; background: var(--bg); color: var(--text); min-height: 100vh; }

/* Header */
.dash-header { background: linear-gradient(135deg, #0f1629 0%, #1a2444 100%);
  border-bottom: 1px solid var(--border); padding: 0 32px; height: 64px; display: flex; align-items: center; }
.header-inner { width: 100%; display: flex; justify-content: space-between; align-items: center; }
.brand { font-size: 1.1rem; font-weight: 700; letter-spacing: -.02em; color: var(--text);
  display: flex; align-items: center; gap: 8px; }
.brand-icon { font-size: 1.3rem; }
.run-meta { font-size: .8rem; color: var(--muted); }
.run-meta strong { color: var(--text); }

/* Main */
.dash-main { max-width: 1600px; margin: 0 auto; padding: 28px 32px 48px; }

/* KPI cards */
.kpi-row { display: grid; grid-template-columns: repeat(4,1fr); gap: 16px; margin-bottom: 28px; }
.kpi-card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
  padding: 20px 24px; transition: transform .2s, box-shadow .2s; }
.kpi-card:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0,0,0,.4); }
.kpi-value { font-size: 2rem; font-weight: 700; line-height: 1; margin-bottom: 6px; }
.kpi-label { font-size: .8rem; color: var(--muted); text-transform: uppercase; letter-spacing: .07em; }
.kpi-pass .kpi-value { color: var(--pass); }
.kpi-fail .kpi-value { color: var(--fail); }
.kpi-neutral .kpi-value { color: var(--accent); }
.score-excellent .kpi-value { color: var(--excellent); }
.score-good      .kpi-value { color: var(--good); }
.score-fair      .kpi-value { color: var(--fair); }
.score-poor      .kpi-value { color: var(--poor); }

/* Filter bar */
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.filter-label { font-size: .8rem; color: var(--muted); text-transform: uppercase; letter-spacing: .07em; }
.filter-btn { background: var(--surface); border: 1px solid var(--border); color: var(--muted);
  padding: 6px 16px; border-radius: 20px; cursor: pointer; font-size: .82rem; font-family: inherit;
  transition: all .2s; }
.filter-btn.active, .filter-btn:hover { background: var(--accent); border-color: var(--accent);
  color: #fff; }
.search-box { margin-left: auto; background: var(--surface); border: 1px solid var(--border);
  color: var(--text); padding: 6px 14px; border-radius: 8px; font-size: .82rem;
  font-family: inherit; width: 240px; outline: none; transition: border-color .2s; }
.search-box:focus { border-color: var(--accent); }

/* Table wrapper */
.table-wrap { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
  overflow: hidden; }
table { width: 100%; border-collapse: collapse; font-size: .82rem; }
thead { background: var(--surface2); }
th { padding: 12px 14px; text-align: left; font-size: .72rem; font-weight: 600;
  text-transform: uppercase; letter-spacing: .07em; color: var(--muted);
  border-bottom: 1px solid var(--border); white-space: nowrap; }
th.sortable { cursor: pointer; user-select: none; }
th.sortable:hover { color: var(--accent); }
.sort-icon { opacity: .5; font-size: .7rem; }
td { padding: 11px 14px; border-bottom: 1px solid var(--border); vertical-align: middle; }
tbody tr { transition: background .15s; }
tbody tr:hover { background: rgba(99,130,255,.05); }
tbody tr:last-child td { border-bottom: none; }
.row-fail { background: rgba(239,68,68,.03); }
.col-num { width: 40px; color: var(--muted); text-align: center; }
.col-score { width: 80px; text-align: center; }
.col-overall { width: 140px; }
.url-cell { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--muted); font-size: .78rem; font-family: monospace; }

/* Score badges */
.score-badge { display: inline-block; padding: 2px 8px; border-radius: 99px;
  font-size: .74rem; font-weight: 600; }
.score-excellent { background: rgba(34,197,94,.15);  color: #4ade80; }
.score-good      { background: rgba(132,204,22,.15); color: #a3e635; }
.score-fair      { background: rgba(245,158,11,.15); color: #fbbf24; }
.score-poor      { background: rgba(239,68,68,.15);  color: #f87171; }

/* Overall cell */
.overall-wrap { display: flex; flex-direction: column; gap: 4px; }
.score-label { font-size: .8rem; font-weight: 600; }
.progress-bar { height: 4px; background: rgba(255,255,255,.08); border-radius: 2px; overflow: hidden; }
.progress-fill { height: 100%; border-radius: 2px; transition: width .6s ease; }
.progress-fill.score-excellent { background: var(--excellent); }
.progress-fill.score-good      { background: var(--good); }
.progress-fill.score-fair      { background: var(--fair); }
.progress-fill.score-poor      { background: var(--poor); }

/* Verdict badges */
.badge { display: inline-block; padding: 3px 10px; border-radius: 99px;
  font-size: .74rem; font-weight: 700; letter-spacing: .04em; }
.badge-pass { background: rgba(34,197,94,.15); color: #4ade80; }
.badge-fail { background: rgba(239,68,68,.15); color: #f87171; }

/* Report links */
.btn-open-pdf { display: inline-block; padding: 6px 14px; border-radius: 6px;
  font-size: .78rem; font-weight: 600; text-decoration: none;
  background: var(--accent); color: #fff;
  border: 1px solid var(--accent); transition: all .2s;
  box-shadow: 0 2px 4px rgba(99,130,255,.2); }
.btn-open-pdf:hover { background: #4f6be5; border-color: #4f6be5; transform: translateY(-1px); box-shadow: 0 4px 8px rgba(99,130,255,.3); }

/* Hidden */
.hidden { display: none !important; }
""";
    }

    // ── JavaScript ────────────────────────────────────────────────────────────

    private static String js() {
        return """
let currentFilter = 'all';
let currentSearch = '';
let sortCol = null, sortDir = 1;

function applyFilter(f) {
  currentFilter = f;
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.toggle('active', b.dataset.filter === f));
  renderTable();
}
function applySearch(q) {
  currentSearch = q.toLowerCase().trim();
  renderTable();
}
function renderTable() {
  const rows = Array.from(document.querySelectorAll('#table-body tr'));
  rows.forEach(row => {
    const verdict = row.dataset.verdict;
    const matchFilter = currentFilter === 'all' || verdict === currentFilter;
    const matchSearch = !currentSearch ||
      row.dataset.sitea.includes(currentSearch) || row.dataset.siteb.includes(currentSearch);
    row.classList.toggle('hidden', !matchFilter || !matchSearch);
  });
}
function sortBy(col) {
  if (sortCol === col) { sortDir *= -1; } else { sortCol = col; sortDir = 1; }
  const tbody = document.getElementById('table-body');
  const rows  = Array.from(tbody.querySelectorAll('tr'));
  const idx   = { num:0, siteA:1, siteB:2, text:3, img:4, links:5, meta:6, dl:7, overall:8, verdict:9 };
  rows.sort((a, b) => {
    const ci = idx[col] ?? 0;
    const ta = a.cells[ci]?.textContent.trim() ?? '';
    const tb = b.cells[ci]?.textContent.trim() ?? '';
    const na = parseFloat(ta); const nb = parseFloat(tb);
    const cmp = (!isNaN(na) && !isNaN(nb)) ? na - nb : ta.localeCompare(tb);
    return cmp * sortDir;
  });
  rows.forEach(r => tbody.appendChild(r));
}
""";
    }
}
