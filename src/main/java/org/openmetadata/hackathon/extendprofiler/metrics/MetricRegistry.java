package org.openmetadata.hackathon.extendprofiler.metrics;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MetricRegistry {

    public enum ColType { STRING, NUMERIC, TIMESTAMP }
    public enum Level { TABLE, COLUMN }

    private static class MetricEntry { // holds one metric + where it applies
        Metric metric;
        Set<ColType> colTypes;
        Set<Level> levels;
    }

    private final List<MetricEntry> entries = new ArrayList<>();

    public void register(Metric m, Set<ColType> colTypes, Set<Level> levels) {
        MetricEntry e = new MetricEntry();
        e.metric = m;
        e.colTypes = colTypes;
        e.levels = levels;
        entries.add(e);
    }

    // all column-level metrics that apply to the given type
    public List<Metric> forColumn(ColType type) {
        return entries.stream()
                .filter(e -> e.levels.contains(Level.COLUMN) && e.colTypes.contains(type))
                .map(e -> e.metric)
                .collect(Collectors.toList());
    }

    // table-level metrics (type-agnostic — run once per table)
    public List<Metric> forTable() {
        return entries.stream()
                .filter(e -> e.levels.contains(Level.TABLE))
                .map(e -> e.metric)
                .collect(Collectors.toList());
    }

    // every registered metric, flat list
    public List<Metric> all() {
        return entries.stream().map(e -> e.metric).collect(Collectors.toList());
    }

    /*
     * Builds a registry pre-loaded with our five advanced metrics
     * and the applicability rules from the README table.
     */
    public static MetricRegistry defaults() {
        MetricRegistry reg = new MetricRegistry();

        Set<ColType> allTypes = EnumSet.allOf(ColType.class);
        Set<ColType> numOnly  = EnumSet.of(ColType.NUMERIC);

        Set<Level> colLevel      = EnumSet.of(Level.COLUMN);
        Set<Level> bothLevels    = EnumSet.of(Level.TABLE, Level.COLUMN);

        // entropy — works on any column type, both levels
        reg.register(new EntropyMetric(),      allTypes, bothLevels);
        // relative entropy (KL divergence vs uniform) — same applicability as entropy
        reg.register(new RelativeEntropyMetric(), allTypes, colLevel);
        // kurtosis / skewness — numeric columns only
        reg.register(new KurtosisMetric(),     numOnly, colLevel);
        reg.register(new SkewnessMetric(),     numOnly, colLevel);
        // seasonality is computed separately from OM profile history (not registered here)
        // value-age — timestamp columns and numeric (for epoch-stored timestamps)
        reg.register(new ValueAgeMetric(),     EnumSet.of(ColType.TIMESTAMP, ColType.NUMERIC), colLevel);

        return reg;
    }

    // helper: map OM dataType string to our ColType
    public static ColType classifyOmType(String omDataType) {
        if (omDataType == null) return ColType.STRING;
        String upper = omDataType.toUpperCase();
        switch (upper) {
            case "INT": case "INTEGER": case "BIGINT": case "SMALLINT":
            case "FLOAT": case "DOUBLE": case "DECIMAL": case "NUMERIC":
            case "NUMBER": case "TINYINT":
                return ColType.NUMERIC;
            case "TIMESTAMP": case "TIMESTAMPZ": case "DATE": case "DATETIME":
            case "TIME": case "TIMESTAMP_NTZ": case "TIMESTAMP_TZ":
                return ColType.TIMESTAMP;
            default:
                return ColType.STRING;
        }
    }
}
