package org.openmetadata.hackathon.extendprofiler.metrics;

import java.sql.Statement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.naming.spi.DirStateFactory.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SeasonalityMetric implements SqlAwareMetric {

    private static final Logger log = LoggerFactory.getLogger(SeasonalityMetric.class);

    private static final double CORRELATION_THRESHOLD = 0.5;

    @Override
    public String getName() {
        return "seasonality";
    }

    @Override
    public String getDescription() {
        return "Repeating pattern detector. Returns the dominant cycle length (in rows), or 0 if none found. "
             + "E.g., 7 on daily data = weekly pattern, 12 on monthly data = yearly pattern. "
             + "Requires time-ordered data. Useful for forecasting and anomaly detection.";
    }

    @Override
    public Double compute(List<String> columnData) {
        double[] values = toDoubles(columnData);
        if (values.length < 4) {
            log.debug("Seasonality requires >= 4 values, got {}", values.length);
            return 0.0;
        }
        return detectSeasonality(values);
    }

    private double detectSeasonality(double[] values) {
        int n = values.length;
        int maxLag = n / 2;
        double mean = mean(values);

        double bestCorrelation = CORRELATION_THRESHOLD;
        int bestLag = 0;

        double variance = 0.0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        if (variance == 0.0) return 0.0;

        for (int lag = 1; lag <= maxLag; lag++) {
            double correlation = 0.0;
            for (int i = 0; i < n - lag; i++) {
                correlation += (values[i] - mean) * (values[i + lag] - mean);
            }
            correlation /= variance;

            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestLag = lag;
            }
        }

        return (double) bestLag;
    }

    private double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private double[] toDoubles(List<String> data) {
        List<Double> parsed = new ArrayList<>();
        for (String s : data) {
            try {
                parsed.add(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                log.debug("Skipping non-numeric value: {}", s);
            }
        }
        return parsed.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Override
    public Double computeSql(Connection conn, String tableName, String columnName, String orderByColumn) {

        String tsColumn = orderByColumn;
        if (tsColumn == null) {
            log.debug("No timestamp column found for SQL seasonality on {}.{}", tableName, columnName);
            return null;
        }
        String sql = String.format(
            "SELECT %s FROM %s ORDER BY %s",
            columnName, tableName, tsColumn
        );
        try(Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(sql);
            List<Double> values = new ArrayList<>();
            
            while (rs.next()) 
                values.add(rs.getDouble(1));

            if(values.size() < 4) {
                log.debug("Seasonality requires >= 4 values, got {} from SQL", values.size());
                return 0.0;
            }

            return detectSeasonality(values.stream().mapToDouble(Double::doubleValue).toArray());

        } catch (Exception e) {
            log.warn("SQL error computing seasonality for {}.{}: {}", tableName, columnName, e.getMessage());
            return null;
        }

    }

}
