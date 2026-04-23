package org.openmetadata.hackathon.extendprofiler.metrics;

import java.sql.Connection;

public interface SqlAwareMetric extends Metric {

    Double computeSql(Connection conn, String tableName, String columnName, MetricRegistry.ColType colType);
}
