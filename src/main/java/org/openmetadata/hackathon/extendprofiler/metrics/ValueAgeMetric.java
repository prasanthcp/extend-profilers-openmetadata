package org.openmetadata.hackathon.extendprofiler.metrics;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.openmetadata.hackathon.extendprofiler.data.QueryCapable;
import org.openmetadata.hackathon.extendprofiler.data.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ValueAgeMetric implements SqlAwareMetric {

    private static final Logger log = LoggerFactory.getLogger(ValueAgeMetric.class);

    private static final DateTimeFormatter[] FORMATTERS = {
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    @Override
    public String getName() {
        return "valueAge";
    }

    @Override
    public String getDescription() {
        return "Data freshness — median age of values in hours. "
             + "Low (e.g., 1-24) = data updated recently. "
             + "High (e.g., 1000+) = stale data, pipeline may be broken. "
             + "Compare across runs to track freshness trends.";
    }

    @Override
    public Double computeNative(QueryCapable source, String columnName, MetricRegistry.ColType colType) {
        if (colType == MetricRegistry.ColType.TIMESTAMP) {
            return computeSqlTimestamp(source, columnName);
        } else if (colType == MetricRegistry.ColType.NUMERIC) {
            return computeSqlEpoch(source, columnName);
        }
        return null;
    }

    private Double computeSqlTimestamp(QueryCapable source, String columnName) {
        SqlDialect dialect = source.getDialect();
        String tableName = source.getQueryTarget();
        String ageExpr = dialect.timestampAgeHoursSql(columnName);

        String sql = "SELECT " + ageExpr + " AS age_hours"
            + " FROM " + tableName
            + " WHERE " + columnName + " IS NOT NULL"
            + " ORDER BY age_hours";

        try {
            List<Map<String, String>> rows = source.executeQuery(sql);
            List<Double> ages = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String val = row.get("age_hours");
                if (val != null) ages.add(Math.max(Double.parseDouble(val), 0.0));
            }
            if (ages.isEmpty()) return null;
            log.debug("ValueAge via SQL (timestamp): {} rows from {}.{}", ages.size(), tableName, columnName);
            return median(ages);
        } catch (Exception e) {
            log.debug("Timestamp SQL failed for {}.{}: {}", tableName, columnName, e.getMessage());
            return null;
        }
    }

    private Double computeSqlEpoch(QueryCapable source, String columnName) {
        SqlDialect dialect = source.getDialect();
        String tableName = source.getQueryTarget();
        String ageExpr = dialect.epochAgeHoursSql(columnName);

        String sql = "SELECT " + ageExpr + " AS age_hours"
            + " FROM " + tableName
            + " WHERE " + columnName + " IS NOT NULL AND " + columnName + " > 0"
            + " ORDER BY age_hours";

        try {
            List<Map<String, String>> rows = source.executeQuery(sql);
            List<Double> ages = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String val = row.get("age_hours");
                if (val != null) {
                    double h = Double.parseDouble(val);
                    if (h >= 0 && h < 1_000_000) ages.add(h);
                }
            }
            if (ages.isEmpty()) return null;
            log.debug("ValueAge via SQL (epoch): {} rows from {}.{}", ages.size(), tableName, columnName);
            return median(ages);
        } catch (Exception e) {
            log.debug("Epoch SQL failed for {}.{}: {}", tableName, columnName, e.getMessage());
            return null;
        }
    }

    @Override
    public Double compute(List<String> columnData) {
        LocalDateTime now = LocalDateTime.now();
        List<Double> agesInHours = new ArrayList<>();

        for (String value : columnData) {
            LocalDateTime parsed = parseTimestamp(value.trim());
            if (parsed != null) {
                double hours = Duration.between(parsed, now).toMinutes() / 60.0;
                agesInHours.add(Math.max(hours, 0.0));
            }
        }

        if (agesInHours.isEmpty()) {
            log.debug("No parseable timestamps found in {} values", columnData.size());
            return null;
        }
        log.debug("Computed value age from {}/{} parseable values", agesInHours.size(), columnData.size());
        return median(agesInHours);
    }

    private LocalDateTime parseTimestamp(String value) {
        for (DateTimeFormatter fmt : FORMATTERS) {
            try {
                return LocalDateTime.parse(value, fmt);
            } catch (DateTimeParseException ignored) {}
        }
        try {
            return java.time.LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException ignored) {}
        return null;
    }

    private double median(List<Double> values) {
        Collections.sort(values);
        int size = values.size();
        if (size % 2 == 0) {
            return (values.get(size / 2 - 1) + values.get(size / 2)) / 2.0;
        }
        return values.get(size / 2);
    }
}
