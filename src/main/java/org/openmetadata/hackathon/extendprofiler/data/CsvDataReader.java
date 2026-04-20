package org.openmetadata.hackathon.extendprofiler.data;
import org.apache.commons.csv.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public class CsvDataReader {

    private static final Logger log = LoggerFactory.getLogger(CsvDataReader.class);
    private String filePath;

    public CsvDataReader(String filePath) {
        this.filePath = filePath;
    }
    public String getFilePath() {
        return filePath;
    }

    public List<? extends Object> readData(int column) {
        
        List<Object> dataList = new ArrayList<>();
        try(Reader reader = new FileReader(getFilePath()) ) {

            CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT);
            for (CSVRecord record : parser) {

                if (record.getRecordNumber() == 1)
                    continue;  // skip header
    
                if (record.size() > column) {
                    String value = record.get(column);
                    dataList.add(value);
                }
            }

        } catch (IOException e) {
            log.error("Failed to read CSV file {}: {}", filePath, e.getMessage());
        }

        return dataList;
    } 

    private static final int SAMPLE_SIZE = 100;

    public boolean numericColumn(int column) {

        try(Reader reader = new FileReader(getFilePath()) ) {

            CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT);
            int checked = 0;
            for (CSVRecord record : parser) {

                if (record.getRecordNumber() == 1)
                    continue;

                if (record.size() > column) {
                    String value = record.get(column);
                    try {
                        Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        return false;
                    }
                    if (++checked >= SAMPLE_SIZE)
                        break;
                }
            }

        } catch (IOException e) {
            log.error("Failed to check column type in {}: {}", filePath, e.getMessage());
        }
        return true;
    }
}
