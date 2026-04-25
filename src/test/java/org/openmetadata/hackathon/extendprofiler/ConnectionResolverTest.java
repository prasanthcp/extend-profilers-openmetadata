package org.openmetadata.hackathon.extendprofiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openmetadata.hackathon.extendprofiler.client.ConnectionResolver;
import org.openmetadata.hackathon.extendprofiler.client.ConnectionResolver.ResolvedConnection;
import org.openmetadata.hackathon.extendprofiler.data.SqlDialect;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionResolverTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode buildServiceJson(String serviceType, String hostPort,
                                         String username, String password, String database) {
        ObjectNode root = mapper.createObjectNode();
        root.put("serviceType", serviceType);
        ObjectNode connection = root.putObject("connection");
        ObjectNode config = connection.putObject("config");
        if (hostPort != null) config.put("hostPort", hostPort);
        if (username != null) config.put("username", username);
        if (password != null) config.put("password", password);
        if (database != null) config.put("database", database);
        return root;
    }

    // ---- resolve: supported service types ----

    @Test
    void resolve_postgres() {
        ObjectNode json = buildServiceJson("postgres", "localhost:5432", "user", "pass", "mydb");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.mydb.public.users");

        assertEquals("jdbc:postgresql://localhost:5432/mydb", conn.jdbcUrl);
        assertEquals("users", conn.tableName);
        assertEquals(SqlDialect.POSTGRESQL, conn.dialect);
    }

    @Test
    void resolve_mysql() {
        ObjectNode json = buildServiceJson("mysql", "db.example.com:3306", "root", "secret", "app");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.app.schema.orders");

        assertEquals("jdbc:mysql://db.example.com:3306/app", conn.jdbcUrl);
        assertEquals("orders", conn.tableName);
        assertEquals(SqlDialect.MYSQL, conn.dialect);
    }

    @Test
    void resolve_mariadb() {
        ObjectNode json = buildServiceJson("mariadb", "host:3307", "u", "p", "db");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.db.schema.t");

        assertTrue(conn.jdbcUrl.startsWith("jdbc:mariadb://"));
        assertEquals("t", conn.tableName);
    }

    @Test
    void resolve_mssql() {
        ObjectNode json = buildServiceJson("mssql", "host:1433", "sa", "p", "master");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.master.dbo.items");

        assertTrue(conn.jdbcUrl.startsWith("jdbc:sqlserver://"));
        assertEquals("items", conn.tableName);
    }

    @Test
    void resolve_redshift() {
        ObjectNode json = buildServiceJson("redshift", "cluster.region.redshift.amazonaws.com:5439", "u", "p", "warehouse");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.warehouse.public.events");

        assertTrue(conn.jdbcUrl.startsWith("jdbc:redshift://"));
    }

    @Test
    void resolve_snowflake() {
        ObjectNode json = buildServiceJson("snowflake", "account.snowflakecomputing.com", "u", "p", "analytics");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.analytics.public.facts");

        assertTrue(conn.jdbcUrl.startsWith("jdbc:snowflake://"));
    }

    // ---- resolve: without database ----

    @Test
    void resolve_noDatabaseOmitsSlash() {
        ObjectNode json = buildServiceJson("postgres", "localhost:5432", "u", "p", null);
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.db.public.t");

        assertEquals("jdbc:postgresql://localhost:5432", conn.jdbcUrl);
    }

    // ---- resolve: null credentials still resolves URL ----

    @Test
    void resolve_nullCredentials_stillResolves() {
        ObjectNode json = buildServiceJson("postgres", "localhost:5432", null, null, "db");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.db.public.t");

        assertNotNull(conn);
        assertEquals("jdbc:postgresql://localhost:5432/db", conn.jdbcUrl);
    }

    // ---- resolve: failure cases ----

    @Test
    void resolve_noHostPort_returnsNull() {
        ObjectNode json = buildServiceJson("postgres", null, "u", "p", "db");
        assertNull(ConnectionResolver.resolve(json, "svc.db.public.t"));
    }

    @Test
    void resolve_unsupportedServiceType_returnsNull() {
        ObjectNode json = buildServiceJson("oracle", "host:1521", "u", "p", "db");
        assertNull(ConnectionResolver.resolve(json, "svc.db.public.t"));
    }

    @Test
    void resolve_emptyServiceType_returnsNull() {
        ObjectNode json = buildServiceJson("", "host:5432", "u", "p", "db");
        assertNull(ConnectionResolver.resolve(json, "svc.db.public.t"));
    }

    @Test
    void resolve_caseInsensitiveServiceType() {
        ObjectNode json = buildServiceJson("Postgres", "host:5432", "u", "p", "db");
        ResolvedConnection conn = ConnectionResolver.resolve(json, "svc.db.public.t");
        assertNotNull(conn);
        assertTrue(conn.jdbcUrl.startsWith("jdbc:postgresql://"));
    }

    // ---- extractServiceName ----

    @Test
    void extractServiceName_fullFqn() {
        assertEquals("local_postgres", ConnectionResolver.extractServiceName("local_postgres.mydb.public.users"));
    }

    @Test
    void extractServiceName_singleSegment() {
        assertEquals("service", ConnectionResolver.extractServiceName("service"));
    }

    @Test
    void extractServiceName_empty() {
        assertEquals("", ConnectionResolver.extractServiceName(""));
    }

    // ---- extractTableName ----

    @Test
    void extractTableName_fullFqn() {
        assertEquals("users", ConnectionResolver.extractTableName("svc.db.schema.users"));
    }

    @Test
    void extractTableName_fiveSegments_takesFourth() {
        assertEquals("table", ConnectionResolver.extractTableName("a.b.c.table.extra"));
    }

    @Test
    void extractTableName_tooFewSegments_returnsNull() {
        assertNull(ConnectionResolver.extractTableName("svc.db.schema"));
    }

    @Test
    void extractTableName_twoSegments_returnsNull() {
        assertNull(ConnectionResolver.extractTableName("svc.db"));
    }

    @Test
    void extractTableName_singleSegment_returnsNull() {
        assertNull(ConnectionResolver.extractTableName("service"));
    }
}
