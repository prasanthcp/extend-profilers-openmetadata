package org.openmetadata.hackathon.extendprofiler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openmetadata.hackathon.extendprofiler.client.OMClient;
import org.openmetadata.hackathon.extendprofiler.data.*;
import org.openmetadata.hackathon.extendprofiler.export.*;
import org.openmetadata.hackathon.extendprofiler.metrics.*;
import org.openmetadata.hackathon.extendprofiler.metrics.MetricRegistry.ColType;
import java.util.*;

public class Profiler {

    private static final Logger log = LoggerFactory.getLogger(Profiler.class);

    private final OMClient client;
    private final MetricRegistry registry;

    public Profiler(OMClient client, MetricRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    // run using OM sample data (original flow)
    public ProfileResult run(String tableFqn) throws Exception {
        JsonNode tbl = client.fetchTable(tableFqn);
        SampleDataSource src = new SampleDataSource(tbl);
        if (!src.hasData()) {
            log.warn("No sample data for {} — skipping", tableFqn);
            return null;
        }
        return runWith(tbl, src);
    }

    // runs with any supported DataSource like JDBC, OM sample data
    public ProfileResult runWith(JsonNode tableJson, DataSource src) throws Exception {
        String tableId = tableJson.get("id").asText();
        String tableFqn = tableJson.get("fullyQualifiedName").asText();

        int colCount = src.getColumns().size();
        int rowCount = src.getTotalRowCount();

        long ts = System.currentTimeMillis();
        ProfileResult result = new ProfileResult(tableFqn, ts, rowCount, colCount);

        QueryCapable querySource = (src instanceof QueryCapable) ? (QueryCapable) src : null;

        Set<String> existingMetrics = new HashSet<>();
        JsonNode customMetrics = tableJson.get("customMetrics");
        if (customMetrics != null && customMetrics.isArray()) {
            for (JsonNode cm : customMetrics) {
                existingMetrics.add(cm.get("name").asText() + (cm.has("columnName") ? "_" + cm.get("columnName").asText() : ""));
            }
        }


        // --- table-level metrics ---
        List<MetricResult> tableLvl = new ArrayList<>();
        for (Metric m : registry.forTable()) {
            List<String> allVals = src.getAllValues();
            Double val = m.compute(allVals);
            if (val != null) {
                if(!existingMetrics.contains(m.getName())) {
                    client.addCustomMetric(tableId, m.getName(), m.getDescription(), null); 
                }
                tableLvl.add(new MetricResult(m.getName(), val));
                result.addTableMetric(m.getName(), val);
            }
        }

        // --- seasonality from OM profile history (table-level) ---
        try {
            long endTs = System.currentTimeMillis() / 1000;
            long startTs = endTs - (90L * 24 * 60 * 60); // last 90 days
            JsonNode history = client.fetchProfileHistory(tableFqn, startTs, endTs);
            if (history != null && history.isArray() && history.size() >= 4) {
                double[] rowCounts = new double[history.size()];
                for (int i = 0; i < history.size(); i++) {
                    rowCounts[i] = history.get(i).path("rowCount").asDouble(0);
                }
                SeasonalityMetric sm = new SeasonalityMetric();
                Double seasonality = sm.computeFromHistory(rowCounts);
                if (seasonality != null) {
                    if (!existingMetrics.contains(sm.getName())) {
                        client.addCustomMetric(tableId, sm.getName(), sm.getDescription(), null);
                    }
                    tableLvl.add(new MetricResult(sm.getName(), seasonality));
                    result.addTableMetric(sm.getName(), seasonality);
                }
                log.debug("Seasonality from {} profile runs: {}", history.size(), seasonality);
            } else {
                log.debug("Not enough profile history for seasonality (need >= 4 runs)");
            }
        } catch (Exception e) {
            log.warn("Could not compute seasonality from OM history: {}", e.getMessage());
        }

        // --- column-level metrics ---
        StringBuilder colJson = new StringBuilder("[");
        boolean first = true;

        for (ColumnInfo ci : src.getColumns()) {
            ColType colType = MetricRegistry.classifyOmType(ci.getDataType());
            List<String> values = src.getColumnValues(ci.getName());
            if (values.isEmpty()) continue;

            // --- basic column stats ---
            Map<String, Object> basicStats = computeBasicStats(querySource, ci.getName(), colType, values);
            result.addColumnBasicStats(ci.getName(), basicStats);

            // --- advanced metrics ---
            List<Metric> applicable = registry.forColumn(colType);
            List<MetricResult> results = new ArrayList<>();
            for (Metric m : applicable) {
                Double val = null;
                if (m instanceof SqlAwareMetric && querySource != null) {
                    val = ((SqlAwareMetric) m).computeNative(querySource, ci.getName(), colType);
                }
                if (val == null) {
                    val = m.compute(values);
                }
                if (val != null) {
                    String qualifiedName = m.getName() + "_" + ci.getName();

                    if(!existingMetrics.contains(qualifiedName))
                        client.addCustomMetric(tableId, qualifiedName, m.getDescription(), ci.getName());

                    results.add(new MetricResult(qualifiedName, val));
                    result.addColumnMetric(ci.getName(), m.getName(), val);
                }
            }
            if (results.isEmpty() && basicStats.isEmpty()) continue;

            if (!first) colJson.append(",");
            first = false;
            colJson.append(columnProfileJson(ci.getName(), ts, values.size(), basicStats, results));
        }
        colJson.append("]");

        // table-level customMetrics fragment
        String tableCustom = "";
        if (!tableLvl.isEmpty()) {
            StringBuilder tcm = new StringBuilder(", \"customMetrics\": [");
            for (int i = 0; i < tableLvl.size(); i++) {
                if (i > 0) tcm.append(",");
                MetricResult mr = tableLvl.get(i);
                tcm.append("{\"name\":\"").append(mr.name).append("\",\"value\":").append(mr.value).append("}");
            }
            tcm.append("]");
            tableCustom = tcm.toString();
        }

        // sample metadata so OM UI shows what the profile is based on
        double profileSample = src.getProfileSample();
        String profileSampleType = src.getProfileSampleType();

        String payload = "{ \"tableProfile\": { \"timestamp\": " + ts
            + ", \"rowCount\": " + rowCount
            + ", \"columnCount\": " + colCount
            + ", \"profileSample\": " + profileSample
            + ", \"profileSampleType\": \"" + profileSampleType + "\""
            + tableCustom
            + " }, \"columnProfile\": " + colJson + " }";

        client.putProfile(tableId, payload);
        log.info("Profiled {} ({} cols, {} total rows, sample={} {})",
            tableFqn, colCount, rowCount, profileSample, profileSampleType);
        return result;
    }

    // --- helpers ---

    private Map<String, Object> computeBasicStats(QueryCapable querySource,
                                                   String columnName, ColType colType,
                                                   List<String> values) {
        Map<String, Object> stats = new LinkedHashMap<>();

        if (querySource != null) {
            // SQL path -- accurate stats from full table
            String table = querySource.getQueryTarget();
            String sql;
            if (colType == ColType.NUMERIC) {
                sql = "SELECT COUNT(*) AS total, COUNT(" + columnName + ") AS non_null,"
                    + " COUNT(DISTINCT " + columnName + ") AS distinct_count,"
                    + " MIN(" + columnName + ") AS min_val,"
                    + " MAX(" + columnName + ") AS max_val,"
                    + " AVG(" + columnName + ") AS mean_val"
                    + " FROM " + table;
            } else {
                sql = "SELECT COUNT(*) AS total, COUNT(" + columnName + ") AS non_null,"
                    + " COUNT(DISTINCT " + columnName + ") AS distinct_count,"
                    + " MIN(" + columnName + ") AS min_val,"
                    + " MAX(" + columnName + ") AS max_val"
                    + " FROM " + table;
            }
            try {
                List<Map<String, String>> rows = querySource.executeQuery(sql);
                if (!rows.isEmpty()) {
                    Map<String, String> row = rows.get(0);
                    int total = parseInt(row.get("total"), 0);
                    int nonNull = parseInt(row.get("non_null"), 0);
                    int nullCount = total - nonNull;
                    int distinctCount = parseInt(row.get("distinct_count"), 0);

                    stats.put("nullCount", nullCount);
                    stats.put("nullProportion", total > 0 ? (nullCount * 1.0 / total) : 0.0);
                    stats.put("uniqueCount", distinctCount);
                    stats.put("uniqueProportion", nonNull > 0 ? (distinctCount * 1.0 / nonNull) : 0.0);
                    stats.put("min", row.get("min_val"));
                    stats.put("max", row.get("max_val"));
                    if (colType == ColType.NUMERIC && row.get("mean_val") != null) {
                        stats.put("mean", parseDouble(row.get("mean_val"), null));
                    }
                }
            } catch (Exception e) {
                log.debug("Basic stats SQL skipped for {} (unsupported type): {}", columnName, e.getMessage());
            }
        }

        // Fallback: compute from in-memory values if SQL didn't run or failed
        if (stats.isEmpty()) {
            int totalWithNulls = values.size();
            Set<String> unique = new HashSet<>(values);
            stats.put("nullCount", 0); // values list already excludes nulls
            stats.put("nullProportion", 0.0);
            stats.put("uniqueCount", unique.size());
            stats.put("uniqueProportion", totalWithNulls > 0 ? (unique.size() * 1.0 / totalWithNulls) : 0.0);

            if (colType == ColType.NUMERIC && !values.isEmpty()) {
                try {
                    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
                    int count = 0;
                    for (String v : values) {
                        double d = Double.parseDouble(v);
                        if (d < min) min = d;
                        if (d > max) max = d;
                        sum += d;
                        count++;
                    }
                    stats.put("min", min);
                    stats.put("max", max);
                    stats.put("mean", count > 0 ? sum / count : null);
                } catch (NumberFormatException ignored) {}
            }
        }

        return stats;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return (int) Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private static Double parseDouble(String s, Double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private String columnProfileJson(String colName, long ts,
                                      int valCount, Map<String, Object> basicStats,
                                      List<MetricResult> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(colName).append("\"");
        sb.append(",\"timestamp\":").append(ts);
        sb.append(",\"valuesCount\":").append(valCount);

        // basic stats fields OM recognizes
        if (basicStats.containsKey("nullCount"))
            sb.append(",\"nullCount\":").append(basicStats.get("nullCount"));
        if (basicStats.containsKey("nullProportion"))
            sb.append(",\"nullProportion\":").append(basicStats.get("nullProportion"));
        if (basicStats.containsKey("uniqueCount"))
            sb.append(",\"uniqueCount\":").append(basicStats.get("uniqueCount"));
        if (basicStats.containsKey("uniqueProportion"))
            sb.append(",\"uniqueProportion\":").append(basicStats.get("uniqueProportion"));
        if (basicStats.containsKey("min"))
            sb.append(",\"min\":").append(jsonValue(basicStats.get("min")));
        if (basicStats.containsKey("max"))
            sb.append(",\"max\":").append(jsonValue(basicStats.get("max")));
        if (basicStats.containsKey("mean") && basicStats.get("mean") != null)
            sb.append(",\"mean\":").append(basicStats.get("mean"));

        sb.append(",\"customMetrics\":[");
        for (int i = 0; i < metrics.size(); i++) {
            if (i > 0) sb.append(",");
            MetricResult m = metrics.get(i);
            sb.append("{\"name\":\"").append(m.name).append("\",\"value\":").append(m.value).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String jsonValue(Object val) {
        if (val == null) return "null";
        if (val instanceof Number) return val.toString();
        return "\"" + val.toString().replace("\"", "\\\"") + "\"";
    }

    static class MetricResult {
        final String name;
        final double value;
        MetricResult(String name, double value) {
            this.name = name;
            this.value = value;
        }
    }

}
