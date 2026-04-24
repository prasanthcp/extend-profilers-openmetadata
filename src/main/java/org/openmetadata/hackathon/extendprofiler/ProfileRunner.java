package org.openmetadata.hackathon.extendprofiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openmetadata.hackathon.extendprofiler.client.ConnectionResolver;
import org.openmetadata.hackathon.extendprofiler.client.OMClient;
import org.openmetadata.hackathon.extendprofiler.client.OMClientException;
import org.openmetadata.hackathon.extendprofiler.data.JdbcDataSource;
import org.openmetadata.hackathon.extendprofiler.export.*;
import org.openmetadata.hackathon.extendprofiler.metrics.MetricRegistry;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ProfileRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfileRunner.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        try {
            run(args[0]);
        } catch (OMClientException e) {
            log.error("API error (HTTP {}): {}", e.getStatusCode(), e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void run(String configPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode config = mapper.readTree(new File(configPath));

        String omUrl   = config.get("omUrl").asText();
        String omEmail = config.get("omEmail").asText();
        String omPass  = config.get("omPassword").asText();

        OMClient om = new OMClient(omUrl);
        om.login(omEmail, omPass);

        MetricRegistry reg = MetricRegistry.defaults();
        Profiler profiler = new Profiler(om, reg);

        String outputDir = config.has("outputDir") ? config.get("outputDir").asText() : "output";
        new File(outputDir).mkdirs();

        List<ProfileResult> results = new ArrayList<>();
        JsonNode tables = config.get("tables");

        // expand wildcard FQNs and collect all entries to process
        List<TableEntry> entries = new ArrayList<>();
        for (JsonNode entry : tables) {
            String fqn = entry.get("fqn").asText();

            if (fqn.endsWith(".*")) {
                // schema wildcard -- discover all tables
                String schemaFqn = fqn.substring(0, fqn.length() - 2);
                log.info("Discovering tables in schema: {}", schemaFqn);
                List<String> discovered = om.listTables(schemaFqn);
                log.info("Discovered {} tables in {}", discovered.size(), schemaFqn);
                for (String tableFqn : discovered) {
                    entries.add(new TableEntry(tableFqn, entry));
                }
            } else {
                entries.add(new TableEntry(fqn, entry));
            }
        }

        int processed = 0;
        int failed = 0;
        for (TableEntry te : entries) {
            String fqn = te.fqn;
            processed++;

            try {
                ProfileResult result = null;

                if (te.config.has("jdbcUrl")) {
                    String jdbcUrl = te.config.get("jdbcUrl").asText();
                    String dbUser = te.config.get("dbUser").asText();
                    String dbPass = te.config.get("dbPassword").asText();
                    String tableName = te.config.get("tableName").asText();
                    int limit = te.config.has("sampleLimit") ? te.config.get("sampleLimit").asInt() : 500;
                    String sampleType = te.config.has("sampleType") ? te.config.get("sampleType").asText() : "ROWS";

                    try (JdbcDataSource jdbc = new JdbcDataSource(jdbcUrl, dbUser, dbPass, tableName, limit, sampleType)) {
                        JsonNode tbl = om.fetchTable(fqn);
                        result = profiler.runWith(tbl, jdbc);
                    }

                } else {
                    result = tryAutoDiscover(om, profiler, fqn, te.config);
                }

                if (result == null) {
                    result = profiler.run(fqn);
                }

                if (result != null) {
                    result.setOmUrl(omUrl);
                    results.add(result);
                }

                if (processed % 25 == 0) {
                    log.info("Progress: {}/{} tables processed ({} profiled so far)",
                            processed, entries.size(), results.size());
                }

            } catch (OMClientException e) {
                failed++;
                log.warn("Skipped {} (API error: {})", fqn, e.getMessage());
            } catch (Exception e) {
                failed++;
                log.warn("Skipped {} ({})", fqn, e.getMessage());
            }
        }

        if (!results.isEmpty()) {
            new JsonResultWriter(outputDir + "/results.json").write(results);
            new CsvResultWriter(outputDir + "/results.csv").write(results);
            new HtmlResultWriter(outputDir + "/LatestReport.html").write(results);
        }

        log.info("Done. Profiled {}/{} tables ({} failed).", results.size(), entries.size(), failed);
    }

    private static ProfileResult tryAutoDiscover(OMClient om, Profiler profiler,
                                                  String fqn, JsonNode entryConfig) {
        try {
            String serviceName = ConnectionResolver.extractServiceName(fqn);
            if (serviceName == null) return null;

            JsonNode serviceJson = om.fetchDatabaseService(serviceName);
            if (serviceJson == null) {
                log.debug("Could not fetch database service '{}' -- will fall back to sample data", serviceName);
                return null;
            }

            ConnectionResolver.ResolvedConnection resolved = ConnectionResolver.resolve(serviceJson, fqn);
            if (resolved == null || resolved.jdbcUrl == null || resolved.tableName == null) {
                log.debug("Could not resolve JDBC for {} -- will fall back to sample data", fqn);
                return null;
            }

            int limit = entryConfig.has("sampleLimit") ? entryConfig.get("sampleLimit").asInt() : 500;
            String sampleType = entryConfig.has("sampleType") ? entryConfig.get("sampleType").asText() : "ROWS";

            log.debug("Profiling: {} (auto-discovered JDBC: {}, sample: {} {})",
                    fqn, resolved.jdbcUrl, limit, sampleType);

            try (JdbcDataSource jdbc = new JdbcDataSource(resolved.jdbcUrl, resolved.user,
                    resolved.password, resolved.tableName, limit, sampleType)) {
                JsonNode tbl = om.fetchTable(fqn);
                return profiler.runWith(tbl, jdbc);
            }
        } catch (Exception e) {
            log.debug("Auto-discovery failed for {}: {}", fqn, e.getMessage());
            return null;
        }
    }

    private static class TableEntry {
        final String fqn;
        final JsonNode config;
        TableEntry(String fqn, JsonNode config) {
            this.fqn = fqn;
            this.config = config;
        }
    }

    private static void printUsage() {
        log.info("Usage: ProfileRunner <config.json>");
        log.info("Config format:");
        log.info("  {");
        log.info("    \"omUrl\": \"http://localhost:8585\",");
        log.info("    \"omEmail\": \"admin@open-metadata.org\",");
        log.info("    \"omPassword\": \"YWRtaW4=\",");
        log.info("    \"outputDir\": \"output\",");
        log.info("    \"tables\": [");
        log.info("      {");
        log.info("        \"fqn\": \"service.database.schema.table\"");
        log.info("        // JDBC auto-discovered from OM. Or provide explicit:");
        log.info("        // \"jdbcUrl\": \"jdbc:postgresql://host:5432/db\",");
        log.info("        // \"dbUser\": \"user\", \"dbPassword\": \"pass\", \"tableName\": \"table\"");
        log.info("      },");
        log.info("      { \"fqn\": \"service.database.schema.*\" }  // wildcard: all tables in schema");
        log.info("    ]");
        log.info("  }");
    }
}
