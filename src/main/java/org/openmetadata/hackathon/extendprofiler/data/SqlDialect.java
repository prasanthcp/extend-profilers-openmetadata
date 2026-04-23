package org.openmetadata.hackathon.extendprofiler.data;

public enum SqlDialect {

    POSTGRESQL { 
        // public implementations
    },

    MYSQL {
        @Override
        public String randomSampleQuery(String table, int limit) {
            return String.format("SELECT * FROM %s ORDER BY RAND() LIMIT %d", table, limit);
        }

        @Override
        public String timestampAgeHoursSql(String col) {
            return String.format("TIMESTAMPDIFF(HOUR, %s, CURRENT_TIMESTAMP)", col);
        }

        @Override
        public String epochAgeHoursSql(String col) {
            return String.format("(UNIX_TIMESTAMP(NOW())*1000 - %s) / 3600000.0", col);
        }

        @Override
        public String stddevFunction() {
            return "STDDEV_SAMP";
        }
    },

    GENERIC { 
        // public implementations
    };

    public String randomSampleQuery(String table, int limit) {
        return String.format("SELECT * FROM %s ORDER BY RANDOM() LIMIT %d", table, limit);
    }

    public String timestampAgeHoursSql(String col) {
        return String.format("EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - %s)) / 3600.0", col);
    }

    public String epochAgeHoursSql(String col) {
        return String.format("(EXTRACT(EPOCH FROM NOW())*1000 - %s) / 3600000.0", col);
    }

    public String stddevFunction() {
        return "STDDEV";
    }
    public static SqlDialect fromJdbcUrl(String jdbcUrl) {
        if(jdbcUrl != null && jdbcUrl.toLowerCase().contains("postgresql")) {
            return POSTGRESQL;
        } else if(jdbcUrl != null && jdbcUrl.toLowerCase().contains("mysql")) {
            return MYSQL;
        } else {
            return GENERIC;
        }
    }
}
