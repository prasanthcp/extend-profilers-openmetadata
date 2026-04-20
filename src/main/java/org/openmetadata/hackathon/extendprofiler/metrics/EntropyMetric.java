package org.openmetadata.hackathon.extendprofiler.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntropyMetric implements Metric {

    @Override
    public String getName() {
        return "entropy";
    }

    @Override
    public String getDescription() {
        return "Data diversity score. 0 = every value identical, higher = more variety. "
             + "Range: 0 to log2(distinct values). A sudden drop may indicate data pipeline issues "
             + "or upstream filtering. Compare across runs to spot distribution shifts.";
    }

    @Override
    public Double compute(List<String> columnData) {

        Map<String, Integer> frequencyMap = new HashMap<>();
        for (String value : columnData) {
            frequencyMap.put(value, frequencyMap.getOrDefault(value, 0) + 1);
        }

        double entropy = 0.0;
        int total = columnData.size();
        for (Integer count : frequencyMap.values()) {
            double probability = (double) count / total;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }
}
