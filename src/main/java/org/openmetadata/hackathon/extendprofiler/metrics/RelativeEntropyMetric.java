package org.openmetadata.hackathon.extendprofiler.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RelativeEntropyMetric implements Metric {

    @Override
    public String getName() {
        return "relativeEntropy";
    }

    @Override
    public String getDescription() {
        return "Distribution balance score (KL divergence vs uniform). "
             + "0 = all values appear equally often, higher = some values dominate. "
             + "Useful for detecting bias — e.g., a 'country' column where 95% is one value. "
             + "Values above 2.0 suggest significant concentration.";
    }

    @Override
    public Double compute(List<String> columnData) {
        if (columnData.isEmpty()) return 0.0;

        Map<String, Integer> freq = new HashMap<>();
        for (String v : columnData) {
            freq.merge(v, 1, Integer::sum);
        }

        int n = columnData.size();
        int k = freq.size();
        if (k <= 1) return 0.0;

        double uniform = 1.0 / k;
        double kl = 0.0;
        for (int count : freq.values()) {
            double p = (double) count / n;
            kl += p * log2(p / uniform);
        }
        return Math.max(kl, 0.0);
    }

    private double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
