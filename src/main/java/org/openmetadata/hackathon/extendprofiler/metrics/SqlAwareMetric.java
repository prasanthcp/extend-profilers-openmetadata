package org.openmetadata.hackathon.extendprofiler.metrics;

import org.openmetadata.hackathon.extendprofiler.data.QueryCapable;

public interface SqlAwareMetric extends Metric {

    Double computeNative(QueryCapable queryCapable, String columnName, MetricRegistry.ColType colType);
}
