package org.openmetadata.hackathon.extendprofiler.data;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdbcDataSource implements DataSource, QueryCapable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataSource.class);

    private final Connection conn;
    private final String tableName;
    private final int sampleLimit;
    private final String sampleType; // "ROWS" or "PERCENTAGE"

    private final SqlDialect dialect;

    private List<ColumnInfo> cachedCols;
    private List<List<String>> cachedRows;
    private int totalRowCount = -1;

    public JdbcDataSource(String jdbcUrl, String user, String password,
                          String tableName, int sampleLimit,
                          String sampleType) throws SQLException {
        log.debug("Connecting to {} (table={}, limit={}, sampleType={})", jdbcUrl, tableName, sampleLimit, sampleType);
        this.conn = DriverManager.getConnection(jdbcUrl, user, password);
        this.tableName = tableName;
        this.sampleLimit = sampleLimit;
        this.sampleType = sampleType != null ? sampleType : "ROWS";
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        log.debug("JDBC connection established");
    }

    public JdbcDataSource(String jdbcUrl, String user, String password,
                          String tableName, int sampleLimit) throws SQLException {
        this(jdbcUrl, user, password, tableName, sampleLimit, "ROWS");
    }

    public JdbcDataSource(Connection conn, String tableName, int sampleLimit) {
        this.conn = conn;
        this.tableName = tableName;
        this.sampleLimit = sampleLimit;
        this.sampleType = "ROWS";
        this.dialect = SqlDialect.GENERIC; // default to GENERIC if not provided
    }

    @Override
    public List<ColumnInfo> getColumns() {
        loadIfNeeded();
        return cachedCols;
    }

    @Override
    public List<String> getColumnValues(String columnName) {
        loadIfNeeded();
        int idx = -1;
        for (int i = 0; i < cachedCols.size(); i++) {
            if (cachedCols.get(i).getName().equalsIgnoreCase(columnName)) { idx = i; break; }
        }
        if (idx < 0) return List.of();

        List<String> vals = new ArrayList<>();
        for (List<String> row : cachedRows) {
            String v = row.get(idx);
            if (v != null) vals.add(v);
        }
        return vals;
    }

    @Override
    public List<String> getAllValues() {
        loadIfNeeded();
        List<String> all = new ArrayList<>();
        for (List<String> row : cachedRows) {
            for (String v : row) {
                if (v != null) all.add(v);
            }
        }
        return all;
    }

    @Override
    public int getRowCount() {
        loadIfNeeded();
        return cachedRows.size();
    }

    @Override
    public int getTotalRowCount() {
        loadIfNeeded();
        return totalRowCount;
    }

    @Override
    public double getProfileSample() {
        loadIfNeeded();
        if ("PERCENTAGE".equals(sampleType)) {
            return totalRowCount > 0
                ? (cachedRows.size() * 100.0 / totalRowCount)
                : 100.0;
        }
        return cachedRows.size();
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    @Override
    public String getProfileSampleType() { return sampleType; }

    @Override
    public Connection getConnection() { return conn; }

    @Override
    public String getTableName() { return tableName; }

    private void loadIfNeeded() {
        if (cachedCols != null) return;
        cachedCols = new ArrayList<>();
        cachedRows = new ArrayList<>();

        try (Statement stmt = conn.createStatement()) {
            // actual row count
            try (ResultSet cnt = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                if (cnt.next()) totalRowCount = cnt.getInt(1);
            }

            // compute effective limit
            int effectiveLimit;
            if ("PERCENTAGE".equals(sampleType)) {
                effectiveLimit = Math.max(1, (int) (totalRowCount * sampleLimit / 100.0));
            } else {
                effectiveLimit = sampleLimit;
            }

            // random sample
            String query  = getDialect().randomSampleQuery(tableName, effectiveLimit);
            try (ResultSet rs = stmt.executeQuery(query)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int c = 1; c <= colCount; c++) {
                    cachedCols.add(new ColumnInfo(
                        meta.getColumnName(c), mapSqlType(meta.getColumnType(c))));
                }

                while (rs.next()) {
                    List<String> row = new ArrayList<>(colCount);
                    for (int c = 1; c <= colCount; c++) {
                        Object val = rs.getObject(c);
                        row.add(val != null ? val.toString() : null);
                    }
                    cachedRows.add(row);
                }
            }
            log.debug("Sampled {} / {} rows from {} ({})",
                cachedRows.size(), totalRowCount, tableName, sampleType);
        } catch (SQLException e) {
            log.error("Failed to read table {}: {}", tableName, e.getMessage());
            throw new RuntimeException("Failed to read " + tableName, e);
        }
    }

    // map java.sql.Types to our OM-style type strings
    private String mapSqlType(int sqlType) {
        switch (sqlType) {
            case Types.BIGINT: case Types.INTEGER: case Types.SMALLINT:
            case Types.TINYINT: case Types.NUMERIC: case Types.DECIMAL:
            case Types.FLOAT: case Types.DOUBLE: case Types.REAL:
                return "NUMERIC";
            case Types.TIMESTAMP: case Types.TIMESTAMP_WITH_TIMEZONE:
            case Types.DATE: case Types.TIME:
                return "TIMESTAMP";
            default:
                return "VARCHAR";
        }
    }

    @Override
    public List<Map<String, String>> executeQuery(String sql) {
        
        List<Map<String, String>> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 1; c <= colCount; c++) {
                    String colName = meta.getColumnName(c);
                    Object val = rs.getObject(c);
                    row.put(colName, val != null ? val.toString() : null);
                }
                results.add(row);
            }
        } catch (SQLException e) {
            log.debug("Query failed: {}", e.getMessage());
            throw new RuntimeException("Query execution failed", e);
        }
        return results;
    }

    @Override
    public String getQueryTarget() {
        return tableName;
    }

    @Override
    public SqlDialect getDialect() {
        return dialect;
    }
}
