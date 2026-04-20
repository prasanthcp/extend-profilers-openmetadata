package org.openmetadata.hackathon.extendprofiler.metrics;

import java.util.List;

public interface Metric {

    String getName();
    String getDescription();
    Double compute(List<String> columnData);

}
