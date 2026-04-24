package org.openmetadata.hackathon.extendprofiler.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class JsonResultWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonResultWriter.class);
    private final String outputPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonResultWriter(String outputPath) {
        this.outputPath = outputPath;
    }

    public void write(List<ProfileResult> results) throws IOException {
        ArrayNode root = mapper.createArrayNode();
        for (ProfileResult result : results) {
            root.add(toJson(result));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputPath), root);
        log.debug("Wrote JSON results to {}", outputPath);
    }

    private ObjectNode toJson(ProfileResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("table", result.getTableFqn());
        node.put("timestamp", result.getTimestamp());
        node.put("rowCount", result.getRowCount());
        node.put("columnCount", result.getColumnCount());

        if (!result.getTableMetrics().isEmpty()) {
            ObjectNode tbl = mapper.createObjectNode();
            for (Map.Entry<String, Double> e : result.getTableMetrics().entrySet()) {
                tbl.put(e.getKey(), round2(e.getValue()));
            }
            node.set("tableMetrics", tbl);
        }

        ArrayNode cols = mapper.createArrayNode();
        for (Map.Entry<String, Map<String, Double>> entry : result.getColumnMetrics().entrySet()) {
            ObjectNode col = mapper.createObjectNode();
            col.put("column", entry.getKey());
            ObjectNode metrics = mapper.createObjectNode();
            for (Map.Entry<String, Double> m : entry.getValue().entrySet()) {
                metrics.put(m.getKey(), round2(m.getValue()));
            }
            col.set("metrics", metrics);
            cols.add(col);
        }
        node.set("columns", cols);

        return node;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
