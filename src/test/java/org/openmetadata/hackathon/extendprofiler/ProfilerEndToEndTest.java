package org.openmetadata.hackathon.extendprofiler;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.hackathon.extendprofiler.data.CsvDataReader;
import org.openmetadata.hackathon.extendprofiler.export.CsvResultWriter;
import org.openmetadata.hackathon.extendprofiler.export.ProfileResult;
import org.openmetadata.hackathon.extendprofiler.metrics.*;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProfilerEndToEndTest {

    @Test
    void runAllMetricsOnSampleDataAndWriteResults(@TempDir Path tempDir) throws Exception {

        // --- 1. Create sample CSV with mixed column types ---
        Path inputCsv = tempDir.resolve("input.csv");
        try (FileWriter fw = new FileWriter(inputCsv.toFile())) {
            fw.write("id,city,score,registration_date\n");
            fw.write("1,New York,85.5,2024-01-15\n");
            fw.write("2,London,92.3,2024-02-20\n");
            fw.write("3,New York,78.1,2024-03-10\n");
            fw.write("4,Tokyo,88.9,2024-04-05\n");
            fw.write("5,London,95.0,2024-05-12\n");
            fw.write("6,Madrid,70.2,2024-06-01\n");
            fw.write("7,New York,85.5,2024-07-18\n");
            fw.write("8,Tokyo,91.4,2024-08-22\n");
            fw.write("9,Dubai,67.8,2024-09-30\n");
            fw.write("10,London,82.6,2024-10-15\n");
        }

        // --- 2. Read CSV and get headers ---
        CsvDataReader reader = new CsvDataReader(inputCsv.toString());
        List<String> headers = getHeaders(inputCsv.toString());
        int columnCount = headers.size();

        // --- 3. Define metrics ---
        ValueAgeMetric valueAgeMetric = new ValueAgeMetric();
        SeasonalityMetric seasonalityMetric = new SeasonalityMetric();

        // --- 4. Run metrics on each column ---
        Map<String, Map<String, Double>> results = new LinkedHashMap<>();

        for (int col = 0; col < columnCount; col++) {
            String colName = headers.get(col);
            List<String> columnData = toStringList(reader.readData(col));
            Map<String, Double> metricResults = new LinkedHashMap<>();

            // Entropy works on all columns
            metricResults.put("Entropy", new EntropyMetric().compute(columnData));

            // Numeric metrics — only if column is numeric
            if (reader.numericColumn(col)) {
                metricResults.put("Kurtosis", new KurtosisMetric().compute(columnData));
                metricResults.put("Skewness", new SkewnessMetric().compute(columnData));
                metricResults.put("Seasonality", seasonalityMetric.compute(columnData));
            } else {
                metricResults.put("Kurtosis", null);
                metricResults.put("Skewness", null);
                metricResults.put("Seasonality", null);
            }

            // ValueAge — only for timestamp columns
            if (colName.toLowerCase().contains("date") || colName.toLowerCase().contains("time")) {
                metricResults.put("ValueAge", valueAgeMetric.compute(columnData));
            } else {
                metricResults.put("ValueAge", null);
            }

            results.put(colName, metricResults);
        }

        // --- 5. Write results ---
        Path outputCsv = tempDir.resolve("output.csv");
        // Build ProfileResult list for the new writer API
        List<ProfileResult> profileResults = new ArrayList<>();
        ProfileResult pr = new ProfileResult("test.db.public.sample", System.currentTimeMillis(), 10, columnCount);
        for (var entry : results.entrySet()) {
            for (var metric : entry.getValue().entrySet()) {
                if (metric.getValue() != null) {
                    pr.addColumnMetric(entry.getKey(), metric.getKey(), metric.getValue());
                }
            }
        }
        profileResults.add(pr);

        CsvResultWriter writer = new CsvResultWriter(outputCsv.toString());
        writer.write(profileResults);

        // --- 6. Verify output file ---
        assertTrue(outputCsv.toFile().exists(), "Output CSV should exist");
        assertTrue(outputCsv.toFile().length() > 0, "Output CSV should not be empty");

        // Read back and verify structure
        try (Reader r = new FileReader(outputCsv.toFile());
             CSVParser parser = new CSVParser(r, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            List<CSVRecord> records = parser.getRecords();
            assertEquals(columnCount, records.size(), "Should have one row per column");

            // Verify headers
            List<String> outputHeaders = parser.getHeaderNames();
            assertTrue(outputHeaders.contains("table"));
            assertTrue(outputHeaders.contains("column"));
            assertTrue(outputHeaders.contains("Entropy"));
            assertTrue(outputHeaders.contains("Kurtosis"));

            // Verify score column has numeric metrics
            for (CSVRecord record : records) {
                if (record.get("column").equals("score")) {
                    assertNotEquals("N/A", record.get("Entropy"));
                    assertNotEquals("N/A", record.get("Kurtosis"));
                    assertNotEquals("N/A", record.get("Skewness"));
                }
                // city column should NOT have numeric metrics
                if (record.get("column").equals("city")) {
                    assertNotEquals("N/A", record.get("Entropy"));
                    assertEquals("N/A", record.get("Kurtosis"));
                    assertEquals("N/A", record.get("Skewness"));
                }
                // registration_date should have ValueAge
                if (record.get("column").equals("registration_date")) {
                    assertNotEquals("N/A", record.get("ValueAge"));
                }
            }
        }

        // Print results for visibility
        System.out.println("\n=== Profiler Results ===");
        System.out.println("Output CSV contents:");
        System.out.println(java.nio.file.Files.readString(outputCsv));
        System.out.println("--- Per-column breakdown ---");
        for (var entry : results.entrySet()) {
            System.out.println("\nColumn: " + entry.getKey());
            for (var metric : entry.getValue().entrySet()) {
                System.out.printf("  %-15s: %s%n", metric.getKey(),
                    metric.getValue() != null ? String.format("%.6f", metric.getValue()) : "N/A");
            }
        }
    }

    private List<String> getHeaders(String filePath) throws Exception {
        try (Reader r = new FileReader(filePath);
             CSVParser parser = new CSVParser(r, CSVFormat.DEFAULT)) {
            CSVRecord first = parser.iterator().next();
            List<String> headers = new ArrayList<>();
            for (String val : first) {
                headers.add(val);
            }
            return headers;
        }
    }

    private List<String> toStringList(List<?> data) {
        List<String> result = new ArrayList<>();
        for (Object obj : data) {
            result.add(obj.toString());
        }
        return result;
    }
}
