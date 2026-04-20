package org.openmetadata.hackathon.extendprofiler.metrics;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.stat.descriptive.moment.Skewness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkewnessMetric implements Metric {

    private static final Logger log = LoggerFactory.getLogger(SkewnessMetric.class);

    @Override
    public String getName() {
        return "skewness";
    }

    @Override
    public String getDescription() {
        return "Distribution symmetry. 0 = symmetric. "
             + "Positive (e.g., +2) = long tail of large values (common in revenue, transaction amounts). "
             + "Negative (e.g., -2) = long tail of small values. "
             + "Beyond +/-1 is moderately skewed; beyond +/-2 is heavily skewed.";
    }

    @Override
    public Double compute(List<String> columnData) {
        double[] values = toDoubles(columnData);
        if (values.length < 3) {
            log.debug("Skewness requires >= 3 values, got {}", values.length);
            return 0.0;
        }
        return new Skewness().evaluate(values);
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
}
