package org.openmetadata.hackathon.extendprofiler.metrics;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SeasonalityMetric implements Metric {

    private static final Logger log = LoggerFactory.getLogger(SeasonalityMetric.class);
    private static final double CORRELATION_THRESHOLD = 0.5;
    private static final int MIN_DATA_POINTS = 4;

    @Override
    public String getName() {
        return "seasonality";
    }

    @Override
    public String getDescription() {
        return "Repeating pattern detector using historical profile runs from OpenMetadata. "
             + "Returns the dominant cycle length (in profile runs), or 0 if none found. "
             + "E.g., 7 on daily profiling = weekly pattern. "
             + "Requires at least " + MIN_DATA_POINTS + " prior profile runs.";
    }

    @Override
    public Double compute(List<String> columnData) {
        // not meaningful on a single snapshot — seasonality is computed from OM profile history
        return null;
    }

    public Double computeFromHistory(double[] timeSeries) {
        if (timeSeries == null || timeSeries.length < MIN_DATA_POINTS) {
            log.debug("Seasonality requires >= {} data points, got {}",
                    MIN_DATA_POINTS, timeSeries == null ? 0 : timeSeries.length);
            return null;
        }
        return detectSeasonality(timeSeries);
    }

    private double detectSeasonality(double[] values) {
        int n = values.length;
        int maxLag = n / 2;
        double mean = mean(values);

        double variance = 0.0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        if (variance == 0.0) return 0.0;

        double bestCorrelation = CORRELATION_THRESHOLD;
        int bestLag = 0;

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
}
