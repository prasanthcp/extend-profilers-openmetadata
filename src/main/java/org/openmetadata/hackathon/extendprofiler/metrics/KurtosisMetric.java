package org.openmetadata.hackathon.extendprofiler.metrics;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.stat.descriptive.moment.Kurtosis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KurtosisMetric implements Metric {

    private static final Logger log = LoggerFactory.getLogger(KurtosisMetric.class);

    @Override
    public String getName() {
        return "kurtosis";
    }

    @Override
    public String getDescription() {
        return "Outlier indicator. Normal range: -2 to 2. "
             + "Above 5 = heavy outliers (investigate extreme values). "
             + "Below -2 = unusually uniform (possible data truncation or capping). "
             + "0 = normal-like tail behavior.";
    }

    @Override
    public Double compute(List<String> columnData) {
        double[] values = toDoubles(columnData);
        if (values.length < 4) {
            log.debug("Kurtosis requires >= 4 values, got {}", values.length);
            return 0.0;
        }
        return new Kurtosis().evaluate(values);
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
