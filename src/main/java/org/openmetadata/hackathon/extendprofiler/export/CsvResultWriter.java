package org.openmetadata.hackathon.extendprofiler.export;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

public class CsvResultWriter {

    private static final Logger log = LoggerFactory.getLogger(CsvResultWriter.class);
    private final String outputPath;

    public CsvResultWriter(String outputPath) {
        this.outputPath = outputPath;
    }

    public void write(List<ProfileResult> results) throws IOException {
        Set<String> metricSet = new LinkedHashSet<>();
        for (ProfileResult r : results) {
            for (Map<String, Double> cm : r.getColumnMetrics().values()) {
                metricSet.addAll(cm.keySet());
            }
        }
        List<String> metricNames = new ArrayList<>(metricSet);

        String[] header = new String[metricNames.size() + 2];
        header[0] = "table";
        header[1] = "column";
        for (int i = 0; i < metricNames.size(); i++) {
            header[i + 2] = metricNames.get(i);
        }

        try (Writer writer = new FileWriter(outputPath);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(header).build())) {

            for (ProfileResult r : results) {
                for (Map.Entry<String, Map<String, Double>> col : r.getColumnMetrics().entrySet()) {
                    Object[] row = new Object[metricNames.size() + 2];
                    row[0] = r.getTableFqn();
                    row[1] = col.getKey();
                    Map<String, Double> values = col.getValue();
                    for (int i = 0; i < metricNames.size(); i++) {
                        Double val = values.get(metricNames.get(i));
                        row[i + 2] = val != null ? String.format("%.2f", val) : "N/A";
                    }
                    printer.printRecord(row);
                }
            }
        }
        log.debug("Wrote CSV results to {}", outputPath);
    }
}
