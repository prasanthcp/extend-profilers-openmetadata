package org.openmetadata.hackathon.extendprofiler;

import org.junit.jupiter.api.Test;
import org.openmetadata.hackathon.extendprofiler.data.SqlDialect;

import static org.junit.jupiter.api.Assertions.*;

class SqlDialectTest {

    // ---- fromJdbcUrl detection ----

    @Test
    void fromJdbcUrl_postgresql() {
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromJdbcUrl("jdbc:postgresql://localhost:5432/mydb"));
    }

    @Test
    void fromJdbcUrl_postgresql_caseInsensitive() {
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromJdbcUrl("jdbc:PostgreSQL://host:5432/db"));
    }

    @Test
    void fromJdbcUrl_mysql() {
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromJdbcUrl("jdbc:mysql://localhost:3306/mydb"));
    }

    @Test
    void fromJdbcUrl_mysql_caseInsensitive() {
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromJdbcUrl("jdbc:MySQL://host/db"));
    }

    @Test
    void fromJdbcUrl_unknown_returnsGeneric() {
        assertEquals(SqlDialect.GENERIC, SqlDialect.fromJdbcUrl("jdbc:oracle:thin:@host:1521:orcl"));
    }

    @Test
    void fromJdbcUrl_null_returnsGeneric() {
        assertEquals(SqlDialect.GENERIC, SqlDialect.fromJdbcUrl(null));
    }

    @Test
    void fromJdbcUrl_empty_returnsGeneric() {
        assertEquals(SqlDialect.GENERIC, SqlDialect.fromJdbcUrl(""));
    }

    // ---- randomSampleQuery ----

    @Test
    void postgresql_randomSampleQuery() {
        String sql = SqlDialect.POSTGRESQL.randomSampleQuery("users", 100, 0);
        assertTrue(sql.contains("ORDER BY RANDOM()"));
        assertTrue(sql.contains("LIMIT 100"));
        assertTrue(sql.contains("users"));
    }

    @Test
    void mysql_randomSampleQuery() {
        String sql = SqlDialect.MYSQL.randomSampleQuery("users", 50, 1000);
        assertTrue(sql.contains("ORDER BY RAND()"));
        assertTrue(sql.contains("LIMIT 50"));
    }

    @Test
    void generic_randomSampleQuery_usesPostgresSyntax() {
        String sql = SqlDialect.GENERIC.randomSampleQuery("t", 10, 100);
        assertTrue(sql.contains("ORDER BY RANDOM()"));
    }

    // ---- timestampAgeHoursSql ----

    @Test
    void postgresql_timestampAge_usesExtractEpoch() {
        String sql = SqlDialect.POSTGRESQL.timestampAgeHoursSql("updated_at");
        assertTrue(sql.contains("EXTRACT(EPOCH FROM"));
        assertTrue(sql.contains("updated_at"));
        assertTrue(sql.contains("3600.0"));
    }

    @Test
    void mysql_timestampAge_usesTimestampdiff() {
        String sql = SqlDialect.MYSQL.timestampAgeHoursSql("updated_at");
        assertTrue(sql.contains("TIMESTAMPDIFF"));
        assertTrue(sql.contains("updated_at"));
    }

    // ---- epochAgeHoursSql ----

    @Test
    void postgresql_epochAge_usesExtract() {
        String sql = SqlDialect.POSTGRESQL.epochAgeHoursSql("created_epoch");
        assertTrue(sql.contains("EXTRACT(EPOCH FROM NOW())"));
        assertTrue(sql.contains("3600000.0"));
    }

    @Test
    void mysql_epochAge_usesUnixTimestamp() {
        String sql = SqlDialect.MYSQL.epochAgeHoursSql("created_epoch");
        assertTrue(sql.contains("UNIX_TIMESTAMP"));
        assertTrue(sql.contains("3600000.0"));
    }

    // ---- stddevFunction ----

    @Test
    void postgresql_stddev() {
        assertEquals("STDDEV", SqlDialect.POSTGRESQL.stddevFunction());
    }

    @Test
    void mysql_stddev() {
        assertEquals("STDDEV_SAMP", SqlDialect.MYSQL.stddevFunction());
    }

    @Test
    void generic_stddev() {
        assertEquals("STDDEV", SqlDialect.GENERIC.stddevFunction());
    }

    // ---- all dialects produce non-empty SQL ----

    @Test
    void allDialects_produceSql() {
        for (SqlDialect d : SqlDialect.values()) {
            assertFalse(d.randomSampleQuery("t", 1, 100).isEmpty());
            assertFalse(d.timestampAgeHoursSql("c").isEmpty());
            assertFalse(d.epochAgeHoursSql("c").isEmpty());
            assertFalse(d.stddevFunction().isEmpty());
        }
    }
}
