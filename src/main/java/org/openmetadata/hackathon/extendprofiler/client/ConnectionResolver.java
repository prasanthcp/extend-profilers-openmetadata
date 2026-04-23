package org.openmetadata.hackathon.extendprofiler.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.openmetadata.hackathon.extendprofiler.data.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ConnectionResolver {

    private static final Logger log = LoggerFactory.getLogger(ConnectionResolver.class);

    private static final Map<String, String> JDBC_PREFIXES = Map.of(
        "postgres", "jdbc:postgresql://",
        "mysql", "jdbc:mysql://",
        "mariadb", "jdbc:mariadb://",
        "mssql", "jdbc:sqlserver://",
        "redshift", "jdbc:redshift://",
        "snowflake", "jdbc:snowflake://"
    );

    public static class ResolvedConnection {
        public final String jdbcUrl;
        public final String user;
        public final String password;
        public final String tableName;
        public final SqlDialect dialect;

        public ResolvedConnection(String jdbcUrl, String user, String password,
                                  String tableName, SqlDialect dialect) {
            this.jdbcUrl = jdbcUrl;
            this.user = user;
            this.password = password;
            this.tableName = tableName;
            this.dialect = dialect;
        }
    }

    public static ResolvedConnection resolve(JsonNode serviceJson, String fqn) {
        String serviceType = serviceJson.path("serviceType").asText("").toLowerCase();
        JsonNode config = serviceJson.path("connection").path("config");

        String hostPort = config.path("hostPort").asText(null);
        String username = config.path("username").asText(null);
        String password = config.path("password").asText(null);
        String database = config.path("database").asText(null);

        if (hostPort == null) {
            log.warn("No hostPort found in service config for {}", serviceType);
            return null;
        }

        String prefix = JDBC_PREFIXES.get(serviceType);
        if (prefix == null) {
            log.warn("Unsupported service type: {}. Supported: {}", serviceType, JDBC_PREFIXES.keySet());
            return null;
        }

        String jdbcUrl = prefix + hostPort;
        if (database != null) {
            jdbcUrl += "/" + database;
        }

        String tableName = extractTableName(fqn);
        SqlDialect dialect = SqlDialect.fromJdbcUrl(jdbcUrl);

        log.debug("Resolved JDBC for {}: url={}, dialect={}, table={}", serviceType, jdbcUrl, dialect, tableName);
        return new ResolvedConnection(jdbcUrl, username, password, tableName, dialect);
    }

    public static String extractServiceName(String fqn) {
        String[] parts = fqn.split("\\.");
        return parts.length > 0 ? parts[0] : null;
    }

    public static String extractTableName(String fqn) {
        String[] parts = fqn.split("\\.");
        return parts.length >= 4 ? parts[3] : null;
    }
}
