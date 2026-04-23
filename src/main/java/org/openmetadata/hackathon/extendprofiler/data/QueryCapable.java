package org.openmetadata.hackathon.extendprofiler.data;

import java.util.List;
import java.util.Map;

public interface QueryCapable {
    List<Map<String, String>> executeQuery(String sql);
    String getQueryTarget(); // returns table name
    SqlDialect getDialect(); // returns SQL Dialect type
}
