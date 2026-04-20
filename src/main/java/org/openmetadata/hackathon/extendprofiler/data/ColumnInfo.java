package org.openmetadata.hackathon.extendprofiler.data;

public class ColumnInfo {
    private final String name;
    private final String dataType; // OM-style type string like "VARCHAR", "BIGINT", "TIMESTAMP"

    public ColumnInfo(String name, String dataType) {
        this.name = name;
        this.dataType = dataType;
    }

    public String getName()     { return name; }
    public String getDataType() { return dataType; }
}
