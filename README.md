# Extend OpenMetadata Profiler with Advanced Statistical Metrics

Extends OpenMetadata's profiler with advanced statistical metrics -- entropy, kurtosis, skewness, seasonality, and value-age -- and pushes them as custom metrics into the platform.

Built for [OpenMetadata Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662).

## Why this exists

OpenMetadata's built-in profiler covers the basics: row counts, null ratios, unique counts, etc. But when you're doing real data quality work, you often need deeper statistical insight. Things like:

- Is this column's distribution normal or heavily skewed?
- How much disorder/randomness is in the data?
- Are there repeating patterns over time?
- How stale is the data in a timestamp column?

This project computes those metrics externally (via JDBC or OM's sample data), pushes them back into OpenMetadata as custom metrics, and also exports them locally as JSON, CSV, and HTML reports for downstream consumers.

## Data input modes

The profiler supports two ways to access table data, selected per table in the config:

| Mode | When to use | How it works |
|------|------------|--------------|
| **JDBC** | You have direct database access | Connects via JDBC, runs `COUNT(*)` for real row count, then fetches a random sample (`ORDER BY RANDOM() LIMIT N`). Sampling is configurable as a fixed row count or percentage. |
| **OM Sample Data** | No direct DB access, or quick profiling of already-ingested tables | Uses sample data stored in OpenMetadata via the table API. No JDBC config needed -- just provide the table FQN. |

If a table entry in the config has `jdbcUrl`, the JDBC path is used. Otherwise, it falls back to OM sample data automatically.

### Sampling and accuracy

- **JDBC mode** reports `profileSampleType` (ROWS or PERCENTAGE) and `profileSample` in the payload so the OM UI reflects that results are sample-based.
- **Value Age** uses SQL-native computation (`EXTRACT(EPOCH FROM (NOW() - col))`) against the full table when JDBC is available -- exact results, no sampling.
- **Seasonality** uses a separate `ORDER BY <timestamp_column>` query when JDBC is available, since autocorrelation requires time-ordered data. The timestamp column is detected from OM's column metadata.
- All other metrics run against the random sample.

## Metrics

| Metric | What it tells you | Column Types | Min Data Points | Computation |
|--------|-------------------|--------------|-----------------|-------------|
| **Entropy** | Data diversity score. 0 = every value identical, higher = more variety. A sudden drop may signal pipeline issues. | All | 1 | In-memory (sample) |
| **Relative Entropy** | Distribution balance (KL divergence vs uniform). 0 = perfectly uniform, above 2.0 = significant concentration. | All | 1 | In-memory (sample) |
| **Kurtosis** | Outlier indicator. Normal range: -2 to 2. Above 5 = heavy outliers. Below -2 = unusually uniform. | Numeric | 4 | In-memory (sample) |
| **Skewness** | Distribution symmetry. 0 = symmetric. Beyond +/-1 = moderate skew, beyond +/-2 = heavy skew. | Numeric | 3 | In-memory (sample) |
| **Seasonality** | Repeating pattern detector. Returns dominant cycle length (in rows), or 0 if none. E.g., 7 on daily data = weekly. | Numeric | 4 | SQL (time-ordered) with in-memory fallback |
| **Value Age** | Data freshness -- median age in hours. Low (1-24) = fresh. High (1000+) = stale, pipeline may be broken. | Timestamp | 1 | SQL (exact) with in-memory fallback |

## Architecture

```
                    +-----------------+
                    |  Config (JSON)  |
                    +--------+--------+
                             |
                             v
+------------+     +-------------------+     +------------------+
|   JDBC     |---->|                   |---->| OpenMetadata API |
| DataSource |     |   ProfileRunner   |     |  (push profiles) |
+------------+     |                   |     +------------------+
                   |  Profiler engine  |
+------------+     |  + MetricRegistry |     +------------------+
| OM Sample  |---->|   + SqlAwareMetric|---->| JSON/CSV/HTML    |
| DataSource |     +-------------------+     +------------------+
+------------+
```

**Ingest** -- connects to your database via JDBC (random sample + COUNT(*) for real row count), or uses OM's stored sample data through the API. JDBC connections are managed with try-with-resources and closed after each table.

**Compute** -- runs each applicable metric against the column data. Metric applicability is driven by column type (numeric, string, timestamp) through the `MetricRegistry`. Metrics that implement `SqlAwareMetric` (Value Age, Seasonality) prefer direct SQL computation when a JDBC connection is available, falling back to in-memory computation otherwise.

**Sync** -- registers custom metric definitions in OM (required for the UI to display them), then pushes computed values via the table profile API. Existing metric definitions are detected and skipped to avoid redundant API calls.

**Export** -- writes results to per-table directories with timestamped files and a `latest.json`/`latest.csv` for stable consumer access. Also generates a single `report.html` summarizing all profiled tables with color-coded metrics and human-readable interpretations.

## How to run

### Prerequisites

- JDK 21+ (tested with Temurin 25)
- A running OpenMetadata instance (local Docker or remote)
- Network access to the database you want to profile

No global Gradle install needed -- the wrapper (`./gradlew`) handles everything.

### 1. Clone and build

```bash
git clone <repo-url>
cd extend-profilers-openmetadata

chmod +x gradlew        # if needed
./gradlew fatJar
```

### 2. Create a config file

Copy `sample-config.json` and fill in your details:

**JDBC mode (direct database access):**
```json
{
  "omUrl": "http://localhost:8585",
  "omEmail": "admin@open-metadata.org",
  "omPassword": "YWRtaW4=",
  "outputDir": "output",
  "tables": [
    {
      "fqn": "local_postgres.openmetadata_db.public.table_entity",
      "jdbcUrl": "jdbc:postgresql://localhost:5432/openmetadata_db",
      "dbUser": "postgres",
      "dbPassword": "password",
      "tableName": "table_entity",
      "sampleLimit": 500,
      "sampleType": "ROWS"
    }
  ]
}
```

**OM sample data mode (no JDBC needed):**
```json
{
  "omUrl": "http://localhost:8585",
  "omEmail": "admin@open-metadata.org",
  "omPassword": "YWRtaW4=",
  "tables": [
    {
      "fqn": "sample_data.ecommerce_db.shopify.dim_address"
    }
  ]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `omUrl` | Yes | OpenMetadata server URL |
| `omEmail` | Yes | Login email |
| `omPassword` | Yes | Base64-encoded password |
| `outputDir` | No | Where exports land (default: `output/`) |
| `tables[].fqn` | Yes | Fully qualified table name in OM |
| `tables[].jdbcUrl` | No | JDBC connection string. If omitted, uses OM sample data instead. |
| `tables[].dbUser` | JDBC only | Database username |
| `tables[].dbPassword` | JDBC only | Database password |
| `tables[].tableName` | JDBC only | Table name for SQL queries |
| `tables[].sampleLimit` | No | Number of rows (default 500) or percentage to sample |
| `tables[].sampleType` | No | `ROWS` (default) or `PERCENTAGE` |

### 3. Run it

```bash
# If using a corporate Java shim (e.g. Salesforce), point to a real JDK:
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home

# Build the fat JAR
./gradlew fatJar

# Run
$JAVA_HOME/bin/java -jar build/libs/extend-profilers-openmetadata-0.1.0-SNAPSHOT-all.jar my-config.json
```

Or via Gradle directly:
```bash
./gradlew run --args='my-config.json'
```

### 4. Check the results

- **OpenMetadata UI** -- go to the table's Profiler tab. Custom metrics show under each column and at the table level. Hover over the metric name to see the description with practical guidance and ranges.
- **HTML report** -- open `output/LatestReport.html` in a browser for a color-coded summary with human-readable metric interpretations.
- **Local exports** -- each table gets its own directory under `output/`:

```
output/
  LatestReport.html            <- color-coded HTML summary of all tables
  local_postgres_.../
    latest.json                <- always the most recent run (stable path)
    latest.csv
    2026-04-19T213345.json     <- timestamped history
    2026-04-19T213345.csv
```

## Project structure

```
src/main/java/org/openmetadata/hackathon/extendprofiler/
  ProfileRunner.java        -- CLI entry point, reads config, orchestrates everything
  Profiler.java             -- core engine: runs metrics, builds OM payloads, pushes profiles
  client/
    OMClient.java           -- thin wrapper around the OM REST API (auth, fetch, push)
    OMClientException.java  -- custom exception with HTTP status codes
  data/
    DataSource.java         -- interface for any data source (sampling metadata, optional JDBC access)
    JdbcDataSource.java     -- JDBC source: random sampling, COUNT(*), AutoCloseable
    SampleDataSource.java   -- backed by OM's stored sample data (API response)
    CsvDataReader.java      -- reads CSV files (test utility only)
    ColumnInfo.java         -- column name + type pair
  metrics/
    Metric.java             -- interface: getName(), getDescription(), compute()
    SqlAwareMetric.java     -- extends Metric with computeSql() for SQL-native computation
    MetricRegistry.java     -- registers metrics with their applicable column types
    EntropyMetric.java      -- Shannon entropy
    RelativeEntropyMetric.java -- KL divergence vs uniform
    KurtosisMetric.java     -- distribution tail heaviness (Apache Commons Math)
    SkewnessMetric.java     -- distribution asymmetry (Apache Commons Math)
    SeasonalityMetric.java  -- autocorrelation-based period detection (SqlAwareMetric)
    ValueAgeMetric.java     -- median staleness of timestamp values (SqlAwareMetric)
  export/
    ProfileResult.java      -- holds table + column metric results
    JsonResultWriter.java   -- writes results to JSON
    CsvResultWriter.java    -- writes results to CSV
    HtmlResultWriter.java   -- generates color-coded HTML report with metric interpretations
```

## Running tests

```bash
./gradlew test
```

The test suite includes:
- **MetricTests** -- 34 unit tests covering all 6 metrics (edge cases, boundary values, type applicability), MetricRegistry type mappings, and HTML report generation with color-coded output
- **ProfilerEndToEndTest** -- end-to-end test creating sample CSV data, running all metrics, writing to CSV, and verifying results
- No external services needed for testing

If tests seem broken or print nothing:
```bash
./gradlew clean test --rerun-tasks --info
```

## Known limitations

- `ORDER BY RANDOM()` and `EXTRACT(EPOCH FROM ...)` are PostgreSQL-specific. Needs dialect abstraction for MySQL, Snowflake, etc.
- No built-in scheduler. For timeseries to be useful, run via cron or integrate with OM's profiler agent schedule.
- No guardrails for huge tables -- `COUNT(*)` and `ORDER BY RANDOM()` on billion-row tables could be slow.
- Seasonality on random-sampled data (in-memory fallback) is unreliable since ordering is lost.

## Where AI was used

- Setting up Gradle, Java version compatibility, and dependency configuration
- Generating test files and test data
- Understanding OpenMetadata's architecture, API flows, and custom metric registration process

## Links

- [Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662)
- [OM Profiler Metrics Docs](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics)
- [OM Table Profiler API Reference](https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler)
- [OM Profiler Workflow](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/profiler-workflow)
