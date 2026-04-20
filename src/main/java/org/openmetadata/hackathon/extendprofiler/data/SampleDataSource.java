package org.openmetadata.hackathon.extendprofiler.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

// Backed by OM's sampleData from the table API response
public class SampleDataSource implements DataSource {

    private final JsonNode columns;    // table.columns  (schema info — name + dataType)
    private final JsonNode sampleCols; // table.sampleData.columns (column name list)
    private final JsonNode sampleRows; // table.sampleData.rows

    public SampleDataSource(JsonNode tableJson) {
        this.columns = tableJson.get("columns");
        JsonNode sd = tableJson.get("sampleData");
        this.sampleCols = (sd != null) ? sd.get("columns") : null;
        this.sampleRows = (sd != null) ? sd.get("rows") : null;
    }

    public boolean hasData() {
        return sampleCols != null && sampleRows != null && sampleRows.size() > 0;
    }

    @Override
    public List<ColumnInfo> getColumns() {
        List<ColumnInfo> out = new ArrayList<>();
        for (JsonNode col : columns) {
            out.add(new ColumnInfo(col.get("name").asText(), col.get("dataType").asText()));
        }
        return out;
    }

    @Override
    public List<String> getColumnValues(String columnName) {
        int idx = findIdx(columnName);
        if (idx < 0) return List.of();
        List<String> vals = new ArrayList<>();
        for (JsonNode row : sampleRows) {
            if (idx < row.size() && !row.get(idx).isNull())
                vals.add(row.get(idx).asText());
        }
        return vals;
    }

    @Override
    public List<String> getAllValues() {
        List<String> all = new ArrayList<>();
        for (JsonNode row : sampleRows) {
            for (int c = 0; c < row.size(); c++) {
                if (!row.get(c).isNull()) all.add(row.get(c).asText());
            }
        }
        return all;
    }

    @Override
    public int getRowCount() {
        return (sampleRows != null) ? sampleRows.size() : 0;
    }

    private int findIdx(String colName) {
        if (sampleCols == null) return -1;
        for (int i = 0; i < sampleCols.size(); i++) {
            if (sampleCols.get(i).asText().equalsIgnoreCase(colName)) return i;
        }
        return -1;
    }
}
