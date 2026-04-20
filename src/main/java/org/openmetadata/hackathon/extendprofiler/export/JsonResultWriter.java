package org.openmetadata.hackathon.extendprofiler.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class JsonResultWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonResultWriter.class);
    private final String outputPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonResultWriter(String outputPath) {
        this.outputPath = outputPath;
    }

    public void write(ProfileResult result) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("table", result.getTableFqn());
        root.put("timestamp", result.getTimestamp());
        root.put("rowCount", result.getRowCount());
        root.put("columnCount", result.getColumnCount());

        // table-level
        if (!result.getTableMetrics().isEmpty()) {
            ObjectNode tbl = mapper.createObjectNode();
            for (Map.Entry<String, Double> e : result.getTableMetrics().entrySet()) {
                tbl.put(e.getKey(), e.getValue());
            }
            root.set("tableMetrics", tbl);
        }

        // column-level
        ArrayNode cols = mapper.createArrayNode();
        for (Map.Entry<String, Map<String, Double>> entry : result.getColumnMetrics().entrySet()) {
            ObjectNode col = mapper.createObjectNode();
            col.put("column", entry.getKey());
            ObjectNode metrics = mapper.createObjectNode();
            for (Map.Entry<String, Double> m : entry.getValue().entrySet()) {
                metrics.put(m.getKey(), m.getValue());
            }
            col.set("metrics", metrics);
            cols.add(col);
        }
        root.set("columns", cols);

        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputPath), root);
        log.debug("Wrote JSON profile to {}", outputPath);
    }
}
