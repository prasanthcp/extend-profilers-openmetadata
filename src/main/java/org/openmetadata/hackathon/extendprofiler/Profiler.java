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

    public ProfileResult runWith(JsonNode tableJson, DataSource src) throws Exception {
        String tableId = tableJson.get("id").asText();
        String tableFqn = tableJson.get("fullyQualifiedName").asText();
        long ts = System.currentTimeMillis();
        ProfileResult result = new ProfileResult(tableFqn, ts, src.getTotalRowCount(), src.getColumns().size());

        Set<String> existing = parseExistingMetrics(tableJson);
        List<MetricResult> tableLvl = computeTableMetrics(src, tableId, existing, result);
        computeSeasonality(tableFqn, tableId, existing, result, tableLvl);
        String colJson = computeColumnProfiles(src, tableId, existing, ts, result);
        String payload = buildPayload(ts, src, tableLvl, colJson);

        client.putProfile(tableId, payload);
        log.info("Profiled {} ({} cols, {} rows)", tableFqn, src.getColumns().size(), src.getTotalRowCount());
        return result;
    }

    private Set<String> parseExistingMetrics(JsonNode tableJson) {
        Set<String> existing = new HashSet<>();
        JsonNode customMetrics = tableJson.get("customMetrics");
        if (customMetrics != null && customMetrics.isArray()) {
            for (JsonNode cm : customMetrics) {
                existing.add(cm.get("name").asText()
                    + (cm.has("columnName") ? "_" + cm.get("columnName").asText() : ""));
            }
        }
        return existing;
    }

    private List<MetricResult> computeTableMetrics(DataSource src, String tableId,
                                                    Set<String> existing, ProfileResult result) throws Exception {
        List<MetricResult> tableLvl = new ArrayList<>();
        for (Metric m : registry.forTable()) {
            Double val = m.compute(src.getAllValues());
            if (val != null) {
                if (!existing.contains(m.getName())) {
                    client.addCustomMetric(tableId, m.getName(), m.getDescription(), null);
                }
                tableLvl.add(new MetricResult(m.getName(), val));
                result.addTableMetric(m.getName(), val);
            }
        }
        return tableLvl;
    }

    private void computeSeasonality(String tableFqn, String tableId, Set<String> existing,
                                     ProfileResult result, List<MetricResult> tableLvl) {
        SeasonalityMetric sm = new SeasonalityMetric();
        double seasonalityValue = 0.0;
        try {
            long endTs = System.currentTimeMillis() / 1000;
            long startTs = endTs - (90L * 24 * 60 * 60);
            JsonNode history = client.fetchProfileHistory(tableFqn, startTs, endTs);

            if (history != null && history.isArray() && history.size() >= 4) {
                double[] rowCounts = new double[history.size()];
                for (int i = 0; i < history.size(); i++) {
                    rowCounts[i] = history.get(i).path("rowCount").asDouble(0);
                }
                Double computed = sm.computeFromHistory(rowCounts);
                if (computed != null) seasonalityValue = computed;
                log.debug("Seasonality from {} profile runs: {}", history.size(), seasonalityValue);
            } else {
                log.debug("Not enough profile history for seasonality (need >= 4 runs)");
            }
        } catch (Exception e) {
            log.warn("Could not compute seasonality from OM history: {}", e.getMessage());
        }

        try {
            if (!existing.contains(sm.getName())) {
                client.addCustomMetric(tableId, sm.getName(), sm.getDescription(), null);
            }
        } catch (Exception e) {
            log.debug("Could not register seasonality metric: {}", e.getMessage());
        }
        tableLvl.add(new MetricResult(sm.getName(), seasonalityValue));
        result.addTableMetric(sm.getName(), seasonalityValue);
    }

    private String computeColumnProfiles(DataSource src, String tableId, Set<String> existing,
                                          long ts, ProfileResult result) throws Exception {
        QueryCapable querySource = (src instanceof QueryCapable) ? (QueryCapable) src : null;
        StringBuilder colJson = new StringBuilder("[");
        boolean first = true;

        for (ColumnInfo ci : src.getColumns()) {
            ColType colType = MetricRegistry.classifyOmType(ci.getDataType());
            List<String> values = src.getColumnValues(ci.getName());
            if (values.isEmpty()) continue;

            List<MetricResult> metrics = computeColumnMetrics(querySource, values, ci.getName(),
                    colType, tableId, existing, result);
            if (metrics.isEmpty()) continue;

            if (!first) colJson.append(",");
            first = false;
            colJson.append(columnProfileJson(ci.getName(), ts, values.size(), metrics));
        }
        colJson.append("]");
        return colJson.toString();
    }

    private List<MetricResult> computeColumnMetrics(QueryCapable querySource, List<String> values,
                                                     String colName, ColType colType, String tableId,
                                                     Set<String> existing, ProfileResult result) throws Exception {
        List<MetricResult> results = new ArrayList<>();
        for (Metric m : registry.forColumn(colType)) {
            Double val = null;
            if (m instanceof SqlAwareMetric && querySource != null) {
                val = ((SqlAwareMetric) m).computeNative(querySource, colName, colType);
            }
            if (val == null) {
                val = m.compute(values);
            }
            if (val != null) {
                String qualifiedName = m.getName() + "_" + colName;
                if (!existing.contains(qualifiedName)) {
                    client.addCustomMetric(tableId, qualifiedName, m.getDescription(), colName);
                }
                results.add(new MetricResult(qualifiedName, val));
                result.addColumnMetric(colName, m.getName(), val);
            }
        }
        return results;
    }

    private String buildPayload(long ts, DataSource src, List<MetricResult> tableLvl, String colJson) {
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

        return "{ \"tableProfile\": { \"timestamp\": " + ts
            + ", \"rowCount\": " + src.getTotalRowCount()
            + ", \"columnCount\": " + src.getColumns().size()
            + ", \"profileSample\": " + src.getProfileSample()
            + ", \"profileSampleType\": \"" + src.getProfileSampleType() + "\""
            + tableCustom
            + " }, \"columnProfile\": " + colJson + " }";
    }

    private String columnProfileJson(String colName, long ts, int valCount,
                                      List<MetricResult> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(colName).append("\"");
        sb.append(",\"timestamp\":").append(ts);
        sb.append(",\"valuesCount\":").append(valCount);
        sb.append(",\"customMetrics\":[");
        for (int i = 0; i < metrics.size(); i++) {
            if (i > 0) sb.append(",");
            MetricResult m = metrics.get(i);
            sb.append("{\"name\":\"").append(m.name).append("\",\"value\":").append(m.value).append("}");
        }
        sb.append("]}");
        return sb.toString();
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
