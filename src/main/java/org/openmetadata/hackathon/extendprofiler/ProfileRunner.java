package org.openmetadata.hackathon.extendprofiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openmetadata.hackathon.extendprofiler.client.OMClient;
import org.openmetadata.hackathon.extendprofiler.client.OMClientException;
import org.openmetadata.hackathon.extendprofiler.data.JdbcDataSource;
import org.openmetadata.hackathon.extendprofiler.export.*;
import org.openmetadata.hackathon.extendprofiler.metrics.MetricRegistry;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

        for (JsonNode entry : tables) {

            String fqn = entry.get("fqn").asText();

            try {

                ProfileResult result = null;
                    
                if(entry.has("jdbcUrl") ) {

                    String jdbcUrl = entry.get("jdbcUrl").asText();
                    String dbUser = entry.get("dbUser").asText();
                    String dbPass = entry.get("dbPassword").asText();
                    String tableName = entry.get("tableName").asText();
                    int limit = entry.has("sampleLimit") ? entry.get("sampleLimit").asInt() : 500;
                    String sampleType = entry.has("sampleType") ? entry.get("sampleType").asText() : "ROWS";

                    log.info("Profiling: {} (sample: {} {})", fqn, limit, sampleType);

                    try (JdbcDataSource jdbc = new JdbcDataSource(jdbcUrl, dbUser, dbPass, tableName, limit, sampleType)) {
                        JsonNode tbl = om.fetchTable(fqn);
                        result = profiler.runWith(tbl, jdbc);
                    }

                } else {
                    
                    log.info("Profiling: {} using sample data", fqn);
                    result = profiler.run(fqn);

                }
                
                if (result != null) {

                    // create a directory for this table's output
                    String safeName = fqn.replaceAll("[^a-zA-Z0-9._-]", "_");
                    String tableDir = outputDir + "/" + safeName;
                    new File(tableDir).mkdirs();

                    // export results
                    String tsFile = tableDir + "/" + safeName + "_" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    new JsonResultWriter(tsFile + ".json").write(result);
                    new CsvResultWriter(tsFile + ".csv").write(result.getColumnMetrics(), result.allMetricNames());

                    // add latest results for easy consumption
                    new JsonResultWriter(tableDir + "/latest.json").write(result);
                    new CsvResultWriter(tableDir + "/latest.csv").write(result.getColumnMetrics(), result.allMetricNames());

                    results.add(result);
                    log.info("Exported {} -> JSON + CSV", safeName);
                }

            } catch (OMClientException e) {
                log.error("API error profiling {}: {}", fqn, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to profile {}: {}", fqn, e.getMessage(), e);
            }
        }

        log.info("Done. Profiled {}/{} tables.", results.size(), tables.size());
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
        log.info("        \"fqn\": \"service.database.schema.table\",");
        log.info("        \"jdbcUrl\": \"jdbc:postgresql://host:5432/db\",");
        log.info("        \"dbUser\": \"user\",");
        log.info("        \"dbPassword\": \"pass\",");
        log.info("        \"tableName\": \"table\",");
        log.info("        \"sampleLimit\": 500,");
        log.info("        \"sampleType\": \"ROWS\"        // ROWS (default) or PERCENTAGE");
        log.info("      }");
        log.info("    ]");
        log.info("  }");
    }
}
