package org.openmetadata.hackathon.extendprofiler.data;

import java.sql.Connection;
import java.util.List;

public interface DataSource {

    List<ColumnInfo> getColumns();

    List<String> getColumnValues(String columnName);

    List<String> getAllValues();

    // Number of rows in the sample
    int getRowCount();

    // Actual row count of the underlying table
    default int getTotalRowCount() { return getRowCount(); }

    /** Sample size used — number of rows or percentage depending on sample type. */
    default double getProfileSample() { return getRowCount(); }

    /** "ROWS" or "PERCENTAGE". */
    default String getProfileSampleType() { return "ROWS"; }

    /** JDBC connection if available (for SQL-native metrics). Null for non-JDBC sources. */
    default Connection getConnection() { return null; }

    /** Table name for SQL queries. Null for non-JDBC sources. */
    default String getTableName() { return null; }
}
