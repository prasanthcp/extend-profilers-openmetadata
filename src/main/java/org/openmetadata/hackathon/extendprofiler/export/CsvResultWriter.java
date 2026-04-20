package org.openmetadata.hackathon.extendprofiler.export;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

public class CsvResultWriter {

    private static final Logger log = LoggerFactory.getLogger(CsvResultWriter.class);
    private final String outputPath;

    public CsvResultWriter(String outputPath) {
        this.outputPath = outputPath;
    }

    /**
     * Writes metric results to a CSV file.
     *
     * @param results Map of columnName -> Map of metricName -> value
     * @param metricNames ordered list of metric names (used as CSV headers)
     */
    public void write(Map<String, Map<String, Double>> results, List<String> metricNames) throws IOException {

        String[] header = new String[metricNames.size() + 1];
        header[0] = "column";
        for (int i = 0; i < metricNames.size(); i++) {
            header[i + 1] = metricNames.get(i);
        }

        try (Writer writer = new FileWriter(outputPath);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(header).build())) {

            for (Map.Entry<String, Map<String, Double>> entry : results.entrySet()) {
                Object[] row = new Object[metricNames.size() + 1];
                row[0] = entry.getKey();
                Map<String, Double> metricValues = entry.getValue();
                for (int i = 0; i < metricNames.size(); i++) {
                    Double val = metricValues.get(metricNames.get(i));
                    row[i + 1] = val != null ? String.format("%.6f", val) : "N/A";
                }
                printer.printRecord(row);
            }
        }
        log.debug("Wrote CSV profile to {}", outputPath);
    }
}
