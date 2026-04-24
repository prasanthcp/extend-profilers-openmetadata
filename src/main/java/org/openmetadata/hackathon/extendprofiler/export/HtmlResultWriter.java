package org.openmetadata.hackathon.extendprofiler.export;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HtmlResultWriter {

    private static final Logger log = LoggerFactory.getLogger(HtmlResultWriter.class);
    private final String outputPath;

    public HtmlResultWriter(String outputPath) {
        this.outputPath = outputPath;
    }

    public void write(List<ProfileResult> results) throws IOException {

        int totalColumns = 0;
        int totalMetrics = 0;
        for (ProfileResult r : results) {
            totalColumns += r.getColumnCount();
            totalMetrics += r.getTableMetrics().size();
            for (Map<String, Double> cm : r.getColumnMetrics().values()) {
                totalMetrics += cm.size();
            }
        }

        double overallScore = computeOverallScore(results);

        try (Writer w = new FileWriter(outputPath)) {

            w.write("<!DOCTYPE html>\n<html>\n<head>\n");
            w.write("<meta charset=\"UTF-8\">\n");
            w.write("<title>Profiler Report</title>\n");
            w.write("<style>\n");
            w.write(CSS);
            w.write("</style>\n</head>\n<body>\n");

            // --- header ---
            w.write("<div class=\"summary\">\n");
            w.write("  <h1>OpenMetadata Extended Profiler Report</h1>\n");
            w.write("  <p>" + results.size() + " tables &middot; "
                    + totalColumns + " columns &middot; "
                    + totalMetrics + " metrics computed</p>\n");
            w.write("</div>\n");

            // --- overall health score ---
            String scoreClass = overallScore >= 80 ? "score-good" : overallScore >= 50 ? "score-warn" : "score-bad";
            w.write("<div class=\"health-card\">\n");
            w.write("  <div class=\"score-circle " + scoreClass + "\">" + (int) overallScore + "</div>\n");
            w.write("  <div class=\"score-detail\">\n");
            w.write("    <h2>Overall Data Health Score</h2>\n");
            w.write("    <p>" + interpretScore(overallScore) + "</p>\n");
            w.write("    <div class=\"score-bar\"><div class=\"score-fill " + scoreClass + "\" style=\"width:" + (int) overallScore + "%\"></div></div>\n");
            w.write("  </div>\n");
            w.write("</div>\n");

            // --- per-table cards ---
            for (ProfileResult r : results) {
                writeTableCard(w, r);
            }

            // --- color legend ---
            w.write("<div class=\"card legend-card\">\n");
            w.write("  <h2>Color Legend</h2>\n");
            w.write("  <div class=\"legend-row\">\n");
            w.write("    <span class=\"legend-dot dot-good\"></span><b>Green — Normal</b>: Value is within the expected healthy range. No action needed.\n");
            w.write("  </div>\n");
            w.write("  <div class=\"legend-row\">\n");
            w.write("    <span class=\"legend-dot dot-warn\"></span><b>Amber — Warning</b>: Value is outside the typical range. Worth investigating.\n");
            w.write("  </div>\n");
            w.write("  <div class=\"legend-row\">\n");
            w.write("    <span class=\"legend-dot dot-bad\"></span><b>Red — Attention</b>: Value indicates a potential data quality issue. Investigate promptly.\n");
            w.write("  </div>\n");
            w.write("  <div class=\"legend-row\">\n");
            w.write("    <span class=\"legend-dot dot-na\"></span><b>Grey (—)</b>: Metric not applicable for this column type.\n");
            w.write("  </div>\n");
            w.write("</div>\n");

            // --- metric reference guide ---
            w.write("<div class=\"card ref-card\">\n");
            w.write("  <h2>Metric Reference Guide</h2>\n");
            w.write("  <p class=\"ref-subtitle\">What each metric measures and when to worry</p>\n");
            w.write("  <table class=\"ref-table\">\n");
            w.write("    <tr><th>Metric</th><th>What It Measures</th><th class=\"ref-range\">Normal Range</th><th class=\"ref-range\">Warning</th><th class=\"ref-range\">Attention</th></tr>\n");
            writeRefRow(w, "Entropy",
                    "Diversity of values in a column. Higher = more unique values, lower = more repetition.",
                    "1.0 – 10.0", "< 1.0 (very low diversity)", "—");
            writeRefRow(w, "Relative Entropy",
                    "How evenly values are distributed compared to a perfectly uniform spread. 0 = perfectly balanced.",
                    "0 – 0.5", "0.5 – 2.0 (some concentration)", "> 2.0 (few values dominate)");
            writeRefRow(w, "Kurtosis",
                    "Tail heaviness of numeric data — how likely extreme outliers are. 0 = normal bell curve.",
                    "-2.0 to 2.0", "2.0 – 5.0 (moderate outliers)", "> 5.0 (heavy outliers)");
            writeRefRow(w, "Skewness",
                    "Asymmetry of numeric data. 0 = symmetric. Positive = right tail, negative = left tail.",
                    "-1.0 to 1.0", "1.0 – 2.0 (moderate skew)", "> 2.0 (heavy skew)");
            writeRefRow(w, "Seasonality",
                    "Repeating cycle detected in historical profile runs (e.g., row count patterns). Computed from OM profile history.",
                    "> 0 (cycle found)", "0 (no pattern)", "—");
            writeRefRow(w, "Value Age",
                    "Data freshness — median age of values in hours. Low = recently updated, high = potentially stale.",
                    "0 – 168h (< 1 week)", "168 – 1000h (1–6 weeks)", "> 1000h (stale — pipeline issue?)");
            w.write("  </table>\n");
            w.write("</div>\n");

            w.write("<footer>Generated by extend-profilers-openmetadata</footer>\n");
            w.write("</body>\n</html>\n");

        } catch (IOException e) {
            log.error("Error writing HTML results", e);
            throw e;
        }

        log.info("Wrote HTML report to {}", outputPath);
    }

    private void writeTableCard(Writer w, ProfileResult r) throws IOException {
        String ts = Instant.ofEpochMilli(r.getTimestamp())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        double tableScore = computeTableScore(r);
        String tsClass = tableScore >= 80 ? "score-good" : tableScore >= 50 ? "score-warn" : "score-bad";

        String omUrl = r.getOmUrl();
        String fqn = r.getTableFqn();

        w.write("<details class=\"card\">\n");
        w.write("  <summary class=\"card-header\">\n");
        w.write("    <div>\n");
        w.write("      <h2>" + esc(fqn) + "</h2>\n");
        w.write("      <p class=\"meta\">" + r.getRowCount() + " rows &middot; "
                + r.getColumnCount() + " columns &middot; " + ts
                + " &middot; score: " + (int) tableScore);
        if (omUrl != null) {
            w.write(" &middot; <a href=\"" + esc(omUrl) + "/table/" + esc(fqn)
                    + "/profiler/table-profile\" target=\"_blank\" class=\"om-link\">View in OpenMetadata</a>");
        }
        w.write("</p>\n");
        w.write("    </div>\n");
        w.write("    <div class=\"table-score " + tsClass + "\">" + (int) tableScore + "</div>\n");
        w.write("  </summary>\n");

        // table-level metrics
        if (!r.getTableMetrics().isEmpty()) {
            w.write("  <h3>Table-level metrics</h3>\n");
            w.write("  <table><tr><th>Metric</th><th>Value</th><th>Status</th><th>Interpretation</th></tr>\n");
            for (Map.Entry<String, Double> e : r.getTableMetrics().entrySet()) {
                String cls = rate(e.getKey(), e.getValue());
                w.write("  <tr><td>" + esc(e.getKey()) + "</td>"
                        + "<td class=\"" + cls + "\">" + fmt(e.getValue()) + "</td>"
                        + "<td>" + statusBadge(cls) + "</td>"
                        + "<td class=\"interp\">" + interpret(e.getKey(), e.getValue()) + "</td></tr>\n");
            }
            w.write("  </table>\n");
        }

        // column-level advanced metrics
        if (!r.getColumnMetrics().isEmpty()) {
            List<String> metricNames = r.allMetricNames();

            w.write("  <h3>Advanced Metrics</h3>\n");
            w.write("  <table><tr><th>Column</th>");
            for (String m : metricNames) {
                w.write("<th>" + esc(m) + "</th>");
            }
            w.write("<th>Interpretation</th>");
            w.write("</tr>\n");

            for (Map.Entry<String, Map<String, Double>> col : r.getColumnMetrics().entrySet()) {
                w.write("  <tr><td class=\"col-name\">" + esc(col.getKey()) + "</td>");
                List<String> interpretations = new ArrayList<>();
                for (String m : metricNames) {
                    Double val = col.getValue().get(m);
                    if (val != null) {
                        String cls = rate(m, val);
                        w.write("<td class=\"" + cls + "\">" + fmt(val) + "</td>");
                        if (!"neutral".equals(cls)) {
                            interpretations.add(interpret(m, val));
                        }
                    } else {
                        w.write("<td class=\"na\">&mdash;</td>");
                    }
                }
                String interpText = interpretations.isEmpty()
                        ? "<span class=\"interp\">All metrics within normal range</span>"
                        : "<span class=\"interp\">" + String.join("; ", interpretations) + "</span>";
                w.write("<td>" + interpText + "</td>");
                w.write("</tr>\n");
            }
            w.write("  </table>\n");
        }

        w.write("</details>\n");
    }

    private void writeRefRow(Writer w, String metric, String description,
                             String normal, String warning, String attention) throws IOException {
        w.write("    <tr><td><b>" + metric + "</b></td>"
                + "<td>" + description + "</td>"
                + "<td class=\"ref-good\">" + normal + "</td>"
                + "<td class=\"ref-warn\">" + warning + "</td>"
                + "<td class=\"ref-bad\">" + attention + "</td></tr>\n");
    }

    // --- scoring ---

    private double computeOverallScore(List<ProfileResult> results) {
        if (results.isEmpty()) return 100;
        double sum = 0;
        for (ProfileResult r : results) {
            sum += computeTableScore(r);
        }
        return sum / results.size();
    }

    public static double computeTableScore(ProfileResult r) {
        int total = 0;
        int healthy = 0;

        // table-level metrics
        for (Map.Entry<String, Double> e : r.getTableMetrics().entrySet()) {
            total++;
            String rating = rate(e.getKey(), e.getValue());
            if ("good".equals(rating) || "neutral".equals(rating)) healthy++;
        }
        // advanced column-level metrics
        for (Map<String, Double> colMetrics : r.getColumnMetrics().values()) {
            for (Map.Entry<String, Double> e : colMetrics.entrySet()) {
                total++;
                String rating = rate(e.getKey(), e.getValue());
                if ("good".equals(rating) || "neutral".equals(rating)) healthy++;
            }
        }
        if (total == 0) return 100;
        return (healthy * 100.0) / total;
    }

    private static String interpretScore(double score) {
        if (score >= 90) return "Excellent — your data profiles look healthy across all tables.";
        if (score >= 80) return "Good — most metrics are within normal range. A few items may warrant a look.";
        if (score >= 50) return "Fair — several metrics are outside normal range. Review the flagged items below.";
        return "Needs attention — multiple data quality issues detected. Investigate the red-flagged metrics.";
    }

    private static String statusBadge(String cls) {
        switch (cls) {
            case "good": return "<span class=\"badge badge-good\">Normal</span>";
            case "warn": return "<span class=\"badge badge-warn\">Warning</span>";
            case "bad":  return "<span class=\"badge badge-bad\">Attention</span>";
            default:     return "<span class=\"badge badge-neutral\">Info</span>";
        }
    }

    // color rating based on metric thresholds
    public static String rate(String metric, double value) {
        switch (metric.toLowerCase()) {
            case "entropy":
                if (value < 1) return "warn";
                return "good";
            case "relativeentropy":
                if (value > 2) return "bad";
                if (value > 0.5) return "warn";
                return "good";
            case "kurtosis":
                if (Math.abs(value) > 5) return "bad";
                if (Math.abs(value) > 2) return "warn";
                return "good";
            case "skewness":
                if (Math.abs(value) > 2) return "bad";
                if (Math.abs(value) > 1) return "warn";
                return "good";
            case "valueage":
                if (value > 1000) return "bad";
                if (value > 168) return "warn";
                return "good";
            case "seasonality":
                return value > 0 ? "good" : "neutral";
            default:
                return "neutral";
        }
    }

    // human-readable interpretation
    public static String interpret(String metric, double value) {
        switch (metric.toLowerCase()) {
            case "entropy":
                if (value < 1) return "Very low diversity — nearly all values identical";
                if (value < 3) return "Moderate diversity";
                return "High diversity — values well spread";
            case "relativeentropy":
                if (value < 0.5) return "Distribution roughly balanced";
                if (value < 2) return "Some concentration in certain values";
                return "Significant concentration — few values dominate";
            case "kurtosis":
                if (value > 5) return "Heavy outliers — investigate extreme values";
                if (value > 2) return "Moderate tail heaviness — some outliers likely";
                if (value < -2) return "Unusually uniform — possible truncation";
                return "Normal tail behavior";
            case "skewness":
                if (value > 2) return "Heavily right-skewed — long tail of large values";
                if (value > 1) return "Moderately right-skewed";
                if (value < -2) return "Heavily left-skewed — long tail of small values";
                if (value < -1) return "Moderately left-skewed";
                return "Roughly symmetric";
            case "seasonality":
                if (value == 0) return "No repeating pattern detected";
                return "Dominant cycle every " + (int) value + " profile runs";
            case "valueage":
                if (value > 1000) return "Stale data (" + (int) value + "h) — pipeline may be broken";
                if (value > 168) return "Data is " + (int) value + "h old — over a week";
                if (value > 24) return "Data is " + (int) value + "h old";
                return "Fresh data (" + String.format("%.1f", value) + "h old)";
            default:
                return fmt(value);
        }
    }

    private static String fmt(double value) {
        if (value == (long) value) return Long.toString((long) value);
        return String.format("%.2f", value);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = ""
            + "* { box-sizing: border-box; }\n"
            + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;\n"
            + "       background: #f5f6fa; color: #2d3436; margin: 0; padding: 24px; max-width: 1200px; margin: 0 auto; }\n"
            + ".summary { text-align: center; padding: 24px 0 8px; }\n"
            + ".summary h1 { color: #6c5ce7; margin-bottom: 8px; }\n"
            + ".summary p { color: #636e72; }\n"

            // health score card
            + ".health-card { display: flex; align-items: center; gap: 28px; background: #fff;\n"
            + "  border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.08); padding: 28px 36px; margin-bottom: 24px; }\n"
            + ".score-circle { width: 90px; height: 90px; border-radius: 50%; display: flex;\n"
            + "  align-items: center; justify-content: center; font-size: 2em; font-weight: 700; color: #fff; flex-shrink: 0; }\n"
            + ".score-good { background: #00b894; }\n"
            + ".score-warn { background: #e17055; }\n"
            + ".score-bad  { background: #d63361; }\n"
            + ".score-detail { flex: 1; }\n"
            + ".score-detail h2 { margin: 0 0 4px; font-size: 1.2em; }\n"
            + ".score-detail p { margin: 0 0 10px; color: #636e72; font-size: 0.95em; }\n"
            + ".score-bar { background: #dfe6e9; border-radius: 6px; height: 10px; width: 100%; }\n"
            + ".score-fill { height: 100%; border-radius: 6px; transition: width 0.5s; }\n"

            // table score badge
            + ".table-score { width: 52px; height: 52px; border-radius: 50%; display: flex;\n"
            + "  align-items: center; justify-content: center; font-size: 1.2em; font-weight: 700; color: #fff; flex-shrink: 0; }\n"

            // cards (collapsible)
            + ".card { background: #fff; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.07);\n"
            + "        padding: 24px; margin-bottom: 20px; }\n"
            + "details.card > summary { cursor: pointer; list-style: none; }\n"
            + "details.card > summary::-webkit-details-marker { display: none; }\n"
            + "details.card > summary::before { content: '\\25B6'; display: inline-block; margin-right: 8px;\n"
            + "  font-size: 0.7em; transition: transform 0.2s; vertical-align: middle; }\n"
            + "details.card[open] > summary::before { transform: rotate(90deg); }\n"
            + ".card h2 { font-size: 1.1em; word-break: break-all; margin: 0; display: inline; }\n"
            + ".card-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }\n"
            + ".meta { font-size: 0.85em; color: #636e72; margin: 4px 0 12px; }\n"
            + "h3 { font-size: 0.9em; color: #636e72; margin: 14px 0 6px; }\n"

            // tables
            + "table { width: 100%; border-collapse: collapse; font-size: 0.88em; }\n"
            + "th { background: #dfe6e9; text-align: left; padding: 8px 10px; font-weight: 600; }\n"
            + "td { padding: 7px 10px; border-bottom: 1px solid #f0f0f0; }\n"
            + ".col-name { font-weight: 600; }\n"

            // status colors
            + ".good { background: #00b89420; color: #00b894; font-weight: 600; }\n"
            + ".warn { background: #fdcb6e30; color: #e17055; font-weight: 600; }\n"
            + ".bad  { background: #d6336120; color: #d63361; font-weight: 600; }\n"
            + ".neutral { background: #dfe6e960; color: #636e72; font-weight: 600; }\n"
            + ".na { color: #b2bec3; text-align: center; }\n"
            + ".interp { font-size: 0.85em; color: #636e72; }\n"

            // badges
            + ".badge { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 0.8em; font-weight: 600; }\n"
            + ".badge-good { background: #00b89420; color: #00b894; }\n"
            + ".badge-warn { background: #fdcb6e30; color: #e17055; }\n"
            + ".badge-bad  { background: #d6336120; color: #d63361; }\n"
            + ".badge-neutral { background: #dfe6e920; color: #636e72; }\n"

            // inline dots
            + ".dot-inline { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }\n"

            // legend
            + ".legend-card { }\n"
            + ".legend-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; font-size: 0.9em; }\n"
            + ".legend-dot { display: inline-block; width: 14px; height: 14px; border-radius: 50%; flex-shrink: 0; }\n"
            + ".dot-good { background: #00b894; }\n"
            + ".dot-warn { background: #e17055; }\n"
            + ".dot-bad  { background: #d63361; }\n"
            + ".dot-na   { background: #b2bec3; }\n"

            // reference guide
            + ".ref-card { }\n"
            + ".ref-subtitle { color: #636e72; font-size: 0.9em; margin: 4px 0 12px; }\n"
            + ".ref-table td, .ref-table th { font-size: 0.85em; padding: 8px 10px; vertical-align: top; }\n"
            + ".ref-range { min-width: 120px; }\n"
            + ".ref-good { background: #00b89410; }\n"
            + ".ref-warn { background: #fdcb6e15; }\n"
            + ".ref-bad  { background: #d6336110; }\n"

            + ".om-link { color: #6c5ce7; text-decoration: none; font-weight: 600; }\n"
            + ".om-link:hover { text-decoration: underline; }\n"
            + "footer { text-align: center; color: #b2bec3; font-size: 0.8em; padding: 20px 0; }\n";
}
