package org.openmetadata.hackathon.extendprofiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.hackathon.extendprofiler.export.HtmlResultWriter;
import org.openmetadata.hackathon.extendprofiler.export.ProfileResult;
import org.openmetadata.hackathon.extendprofiler.metrics.*;
import org.openmetadata.hackathon.extendprofiler.metrics.MetricRegistry.ColType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricTests {

    // ---- EntropyMetric ----

    @Test
    void entropy_allIdentical_returnsZero() {
        assertEquals(0.0, new EntropyMetric().compute(List.of("a", "a", "a")));
    }

    @Test
    void entropy_twoEqualValues_returnsOne() {
        double result = new EntropyMetric().compute(List.of("a", "b"));
        assertEquals(1.0, result, 0.001);
    }

    @Test
    void entropy_fourEqualValues_returnsTwo() {
        double result = new EntropyMetric().compute(List.of("a", "b", "c", "d"));
        assertEquals(2.0, result, 0.001);
    }

    @Test
    void entropy_singleValue_returnsZero() {
        assertEquals(0.0, new EntropyMetric().compute(List.of("x")));
    }

    // ---- RelativeEntropyMetric ----

    @Test
    void relativeEntropy_uniform_returnsZero() {
        double result = new RelativeEntropyMetric().compute(List.of("a", "b", "c", "d"));
        assertEquals(0.0, result, 0.001);
    }

    @Test
    void relativeEntropy_skewed_returnsPositive() {
        double result = new RelativeEntropyMetric().compute(
                List.of("a", "a", "a", "a", "a", "a", "a", "a", "a", "b"));
        assertTrue(result > 0, "Skewed distribution should have positive KL divergence");
    }

    @Test
    void relativeEntropy_empty_returnsZero() {
        assertEquals(0.0, new RelativeEntropyMetric().compute(Collections.emptyList()));
    }

    @Test
    void relativeEntropy_singleDistinctValue_returnsZero() {
        assertEquals(0.0, new RelativeEntropyMetric().compute(List.of("x", "x", "x")));
    }

    // ---- KurtosisMetric ----

    @Test
    void kurtosis_normalRange() {
        List<String> data = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        Double result = new KurtosisMetric().compute(data);
        assertNotNull(result);
        assertTrue(Math.abs(result) < 5, "Uniform-ish data should have kurtosis near 0");
    }

    @Test
    void kurtosis_tooFewValues_returnsZero() {
        assertEquals(0.0, new KurtosisMetric().compute(List.of("1", "2", "3")));
    }

    @Test
    void kurtosis_nonNumeric_returnsZero() {
        assertEquals(0.0, new KurtosisMetric().compute(List.of("cat", "dog", "fish", "bird")));
    }

    // ---- SkewnessMetric ----

    @Test
    void skewness_symmetric_nearZero() {
        List<String> data = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        Double result = new SkewnessMetric().compute(data);
        assertNotNull(result);
        assertEquals(0.0, result, 0.1);
    }

    @Test
    void skewness_rightSkewed_positive() {
        List<String> data = List.of("1", "1", "1", "1", "1", "2", "2", "3", "10", "100");
        Double result = new SkewnessMetric().compute(data);
        assertNotNull(result);
        assertTrue(result > 0, "Right-skewed data should have positive skewness");
    }

    @Test
    void skewness_tooFewValues_returnsZero() {
        assertEquals(0.0, new SkewnessMetric().compute(List.of("1", "2")));
    }

    // ---- SeasonalityMetric (from OM profile history) ----

    @Test
    void seasonality_fromHistory_detectsCycle() {
        double[] series = {100, 200, 100, 200, 100, 200, 100, 200};
        Double result = new SeasonalityMetric().computeFromHistory(series);
        assertNotNull(result);
        assertEquals(2.0, result, "Should detect period-2 cycle");
    }

    @Test
    void seasonality_fromHistory_tooFewPoints_returnsNull() {
        double[] series = {100, 200, 300};
        assertNull(new SeasonalityMetric().computeFromHistory(series));
    }

    @Test
    void seasonality_fromHistory_constant_returnsZero() {
        double[] series = {50, 50, 50, 50, 50, 50};
        assertEquals(0.0, new SeasonalityMetric().computeFromHistory(series));
    }

    @Test
    void seasonality_compute_returnsNull() {
        assertNull(new SeasonalityMetric().compute(List.of("1", "2", "3", "4")),
                "compute() should return null — seasonality uses OM history, not snapshot data");
    }

    // ---- ValueAgeMetric ----

    @Test
    void valueAge_recentDates_smallAge() {
        String today = java.time.LocalDate.now().toString();
        Double result = new ValueAgeMetric().compute(List.of(today, today, today));
        assertNotNull(result);
        assertTrue(result < 48, "Today's dates should be less than 48 hours old");
    }

    @Test
    void valueAge_oldDates_largeAge() {
        Double result = new ValueAgeMetric().compute(List.of("2020-01-01", "2020-06-15", "2020-12-31"));
        assertNotNull(result);
        assertTrue(result > 1000, "2020 dates should be >1000 hours old");
    }

    @Test
    void valueAge_unparseable_returnsNull() {
        assertNull(new ValueAgeMetric().compute(List.of("not-a-date", "also-not")));
    }

    @Test
    void valueAge_epochMillis_notParsedByCompute() {
        long recentEpoch = System.currentTimeMillis() - 3_600_000L;
        assertNull(new ValueAgeMetric().compute(List.of(String.valueOf(recentEpoch))),
                "compute() only handles date strings — epoch values are handled by computeSql with known colType");
    }

    // ---- MetricRegistry ----

    @Test
    void registry_numericColumn_getsKurtosisSkewnessValueAge() {
        MetricRegistry reg = MetricRegistry.defaults();
        List<Metric> metrics = reg.forColumn(ColType.NUMERIC);
        List<String> names = metrics.stream().map(Metric::getName).toList();
        assertTrue(names.contains("entropy"));
        assertTrue(names.contains("kurtosis"));
        assertTrue(names.contains("skewness"));
        assertTrue(names.contains("valueAge"), "valueAge applies to NUMERIC for epoch-stored timestamps");
        assertFalse(names.contains("seasonality"), "seasonality is computed from OM history, not per-column");
    }

    @Test
    void registry_timestampColumn_getsValueAge() {
        MetricRegistry reg = MetricRegistry.defaults();
        List<Metric> metrics = reg.forColumn(ColType.TIMESTAMP);
        List<String> names = metrics.stream().map(Metric::getName).toList();
        assertTrue(names.contains("entropy"));
        assertTrue(names.contains("valueAge"));
        assertFalse(names.contains("kurtosis"), "kurtosis should not apply to TIMESTAMP");
    }

    @Test
    void registry_stringColumn_getsEntropyOnly() {
        MetricRegistry reg = MetricRegistry.defaults();
        List<Metric> metrics = reg.forColumn(ColType.STRING);
        List<String> names = metrics.stream().map(Metric::getName).toList();
        assertTrue(names.contains("entropy"));
        assertTrue(names.contains("relativeEntropy"));
        assertFalse(names.contains("kurtosis"));
        assertFalse(names.contains("valueAge"));
    }

    @Test
    void registry_tableLevelMetrics_includesEntropy() {
        MetricRegistry reg = MetricRegistry.defaults();
        List<Metric> tableLvl = reg.forTable();
        List<String> names = tableLvl.stream().map(Metric::getName).toList();
        assertTrue(names.contains("entropy"));
        assertFalse(names.contains("kurtosis"), "kurtosis should not be table-level");
    }

    @Test
    void classifyOmType_coversAllExpectedTypes() {
        assertEquals(ColType.NUMERIC, MetricRegistry.classifyOmType("INT"));
        assertEquals(ColType.NUMERIC, MetricRegistry.classifyOmType("BIGINT"));
        assertEquals(ColType.NUMERIC, MetricRegistry.classifyOmType("FLOAT"));
        assertEquals(ColType.NUMERIC, MetricRegistry.classifyOmType("DECIMAL"));
        assertEquals(ColType.TIMESTAMP, MetricRegistry.classifyOmType("TIMESTAMP"));
        assertEquals(ColType.TIMESTAMP, MetricRegistry.classifyOmType("DATE"));
        assertEquals(ColType.TIMESTAMP, MetricRegistry.classifyOmType("DATETIME"));
        assertEquals(ColType.STRING, MetricRegistry.classifyOmType("VARCHAR"));
        assertEquals(ColType.STRING, MetricRegistry.classifyOmType("JSON"));
        assertEquals(ColType.STRING, MetricRegistry.classifyOmType("BOOLEAN"));
        assertEquals(ColType.STRING, MetricRegistry.classifyOmType(null));
    }

    // ---- HtmlResultWriter ----

    @Test
    void htmlWriter_producesValidOutput(@TempDir Path tempDir) throws Exception {
        ProfileResult r1 = new ProfileResult("db.schema.table1", System.currentTimeMillis(), 100, 3);
        r1.addTableMetric("entropy", 4.5);
        r1.addColumnMetric("id", "entropy", 3.2);
        r1.addColumnMetric("id", "kurtosis", 6.1);
        r1.addColumnMetric("city", "entropy", 2.1);
        r1.addColumnMetric("updated_at", "valueAge", 2000.0);

        ProfileResult r2 = new ProfileResult("db.schema.table2", System.currentTimeMillis(), 50, 2);
        r2.addTableMetric("entropy", 2.0);
        r2.addColumnMetric("name", "entropy", 1.5);

        Path output = tempDir.resolve("report.html");
        new HtmlResultWriter(output.toString()).write(List.of(r1, r2));

        assertTrue(output.toFile().exists());
        String html = Files.readString(output);

        assertTrue(html.contains("<!DOCTYPE html>"), "Should be valid HTML5");
        assertTrue(html.contains("db.schema.table1"), "Should contain table FQN");
        assertTrue(html.contains("db.schema.table2"), "Should contain second table FQN");
        assertTrue(html.contains("2 tables"), "Should show table count in summary");
        assertTrue(html.contains("class=\"bad\""), "Kurtosis 6.1 should be rated bad");
        assertTrue(html.contains("class=\"good\""), "Entropy should be rated good");

        System.out.println("HTML report generated at: " + output);
        System.out.println("Size: " + output.toFile().length() + " bytes");
    }

    @Test
    void htmlWriter_emptyResults(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("empty.html");
        new HtmlResultWriter(output.toString()).write(Collections.emptyList());

        String html = Files.readString(output);
        assertTrue(html.contains("0 tables"));
        assertTrue(html.contains("0 columns"));
    }

    // ---- HtmlResultWriter rating/interpretation ----

    @Test
    void rate_kurtosis_thresholds() {
        assertEquals("good", HtmlResultWriter.rate("kurtosis", 1.5));
        assertEquals("warn", HtmlResultWriter.rate("kurtosis", 3.0));
        assertEquals("bad", HtmlResultWriter.rate("kurtosis", 6.0));
        assertEquals("bad", HtmlResultWriter.rate("kurtosis", -6.0));
    }

    @Test
    void rate_skewness_thresholds() {
        assertEquals("good", HtmlResultWriter.rate("skewness", 0.5));
        assertEquals("warn", HtmlResultWriter.rate("skewness", 1.5));
        assertEquals("bad", HtmlResultWriter.rate("skewness", 3.0));
    }

    @Test
    void rate_valueAge_thresholds() {
        assertEquals("good", HtmlResultWriter.rate("valueAge", 24.0));
        assertEquals("warn", HtmlResultWriter.rate("valueAge", 500.0));
        assertEquals("bad", HtmlResultWriter.rate("valueAge", 2000.0));
    }

    @Test
    void interpret_returnsHumanReadable() {
        assertTrue(HtmlResultWriter.interpret("entropy", 0.5).contains("low diversity"));
        assertTrue(HtmlResultWriter.interpret("kurtosis", 6.0).contains("outliers"));
        assertTrue(HtmlResultWriter.interpret("valueAge", 2000).contains("Stale"));
        assertTrue(HtmlResultWriter.interpret("seasonality", 7).contains("cycle"));
        assertTrue(HtmlResultWriter.interpret("seasonality", 0).contains("No"));
    }
}
