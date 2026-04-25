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

    // Docker-internal hostnames that should be rewritten to localhost
    // when the profiler runs on the host machine
    private static final Map<String, String> HOST_REWRITES = Map.of(
        "openmetadata_postgresql", "localhost",
        "openmetadata_mysql", "localhost",
        "openmetadata_mssql", "localhost"
    );

    public static class ResolvedConnection {
        public final String jdbcUrl;
        public final String tableName;
        public final SqlDialect dialect;

        public ResolvedConnection(String jdbcUrl, String tableName, SqlDialect dialect) {
            this.jdbcUrl = jdbcUrl;
            this.tableName = tableName;
            this.dialect = dialect;
        }
    }

    public static ResolvedConnection resolve(JsonNode serviceJson, String fqn) {
        String serviceType = serviceJson.path("serviceType").asText("").toLowerCase();
        JsonNode config = serviceJson.path("connection").path("config");

        String hostPort = config.path("hostPort").asText(null);
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

        hostPort = rewriteHost(hostPort);

        String jdbcUrl = prefix + hostPort;
        if (database != null) {
            jdbcUrl += "/" + database;
        }

        String tableName = extractTableName(fqn);
        SqlDialect dialect = SqlDialect.fromJdbcUrl(jdbcUrl);

        log.debug("Resolved JDBC for {}: url={}, dialect={}, table={}", serviceType, jdbcUrl, dialect, tableName);
        return new ResolvedConnection(jdbcUrl, tableName, dialect);
    }

    public static String extractServiceName(String fqn) {
        String[] parts = fqn.split("\\.");
        return parts.length > 0 ? parts[0] : null;
    }

    public static String extractTableName(String fqn) {
        String[] parts = fqn.split("\\.");
        return parts.length >= 4 ? parts[3] : null;
    }

    private static String rewriteHost(String hostPort) {
        int colonIdx = hostPort.indexOf(':');
        String host = colonIdx > 0 ? hostPort.substring(0, colonIdx) : hostPort;
        String replacement = HOST_REWRITES.get(host);
        if (replacement != null) {
            String rewritten = colonIdx > 0 ? replacement + hostPort.substring(colonIdx) : replacement;
            log.warn("Rewrote Docker-internal host '{}' → '{}'", hostPort, rewritten);
            return rewritten;
        }
        return hostPort;
    }
}
