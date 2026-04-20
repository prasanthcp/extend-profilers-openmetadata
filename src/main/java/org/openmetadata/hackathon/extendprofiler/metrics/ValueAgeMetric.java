package org.openmetadata.hackathon.extendprofiler.metrics;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    public Double computeSql(Connection conn, String tableName, String columnName) {
        String sql = "SELECT EXTRACT(EPOCH FROM (NOW() - " + columnName + ")) / 3600.0 AS age_hours"
            + " FROM " + tableName
            + " WHERE " + columnName + " IS NOT NULL"
            + " ORDER BY age_hours";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            List<Double> ages = new ArrayList<>();
            while (rs.next()) {
                double h = rs.getDouble("age_hours");
                ages.add(Math.max(h, 0.0));
            }
            if (ages.isEmpty()) return 0.0;
            log.info("ValueAge via SQL: {} rows from {}.{}", ages.size(), tableName, columnName);
            return median(ages);
        } catch (Exception e) {
            log.warn("SQL valueAge failed for {}.{}, falling back to in-memory: {}",
                tableName, columnName, e.getMessage());
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
            return 0.0;
        }
        log.debug("Computed value age from {}/{} parseable timestamps", agesInHours.size(), columnData.size());
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
