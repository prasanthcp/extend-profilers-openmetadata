package org.openmetadata.hackathon.extendprofiler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openmetadata.hackathon.extendprofiler.client.OMClient;
import org.openmetadata.hackathon.extendprofiler.data.*;
import org.openmetadata.hackathon.extendprofiler.export.*;
import org.openmetadata.hackathon.extendprofiler.metrics.*;
import org.openmetadata.hackathon.extendprofiler.metrics.MetricRegistry.ColType;
import java.sql.Connection;
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

        Connection conn = src.getConnection();
        String tblName = src.getTableName();

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
                log.info("Seasonality from {} profile runs: {}", history.size(), seasonality);
            } else {
                log.info("Not enough profile history for seasonality (need >= 4 runs)");
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

            List<Metric> applicable = registry.forColumn(colType);
            List<MetricResult> results = new ArrayList<>();
            for (Metric m : applicable) {
                Double val = null;
                // prefer SQL-native computation when connection is available
                if (m instanceof SqlAwareMetric && conn != null) {
                    val = ((SqlAwareMetric) m).computeSql(conn, tblName, ci.getName(), colType);
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
            if (results.isEmpty()) continue;

            if (!first) colJson.append(",");
            first = false;
            colJson.append(columnProfileJson(ci.getName(), ts, values.size(), results));
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

    private String columnProfileJson(String colName, long ts,
                                      int valCount, List<MetricResult> metrics) {
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
