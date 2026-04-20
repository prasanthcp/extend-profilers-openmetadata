package org.openmetadata.hackathon.extendprofiler.export;

import java.util.*;

public class ProfileResult {

    private final String tableFqn;
    private final long timestamp;
    private final int rowCount;
    private final int columnCount;
    // table-level: metricName -> value
    private final Map<String, Double> tableMetrics = new LinkedHashMap<>();
    // column-level: columnName -> (metricName -> value)
    private final Map<String, Map<String, Double>> columnMetrics = new LinkedHashMap<>();

    public ProfileResult(String tableFqn, long timestamp, int rowCount, int columnCount) {
        this.tableFqn = tableFqn;
        this.timestamp = timestamp;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
    }

    public void addTableMetric(String name, double value) {
        tableMetrics.put(name, value);
    }

    public void addColumnMetric(String column, String metricName, double value) {
        columnMetrics.computeIfAbsent(column, k -> new LinkedHashMap<>()).put(metricName, value);
    }

    public String getTableFqn()   { return tableFqn; }
    public long getTimestamp()     { return timestamp; }
    public int getRowCount()       { return rowCount; }
    public int getColumnCount()    { return columnCount; }
    public Map<String, Double> getTableMetrics()                    { return tableMetrics; }
    public Map<String, Map<String, Double>> getColumnMetrics()      { return columnMetrics; }

    // all unique metric names across columns (for CSV headers)
    public List<String> allMetricNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(tableMetrics.keySet());
        for (Map<String, Double> cm : columnMetrics.values()) {
            names.addAll(cm.keySet());
        }
        return new ArrayList<>(names);
    }
}
