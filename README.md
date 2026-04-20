<<<<<<< HEAD
# Project: Extend the profiler with advanced statistical metrics (entropy, kurtosis, skewness, seasonality, value-age) and make them exportable for downstream consumers #26662

## Build (Gradle & Java)

- **JDK:** 21+ recommended. This repo is tested with **Temurin 25** as the JVM running Gradle; the **Gradle Wrapper uses 9.1.0** (needed so Gradle itself runs on Java 25). Compiled bytecode targets **Java 21** (`release = 21`).
- **Commands:** from this directory run `./gradlew test`, `./gradlew run`, or `./gradlew build`.
- **No global Gradle install required** — use `./gradlew` only.
- **If `test` seems broken or prints nothing useful:** run `./gradlew doctor` then `./gradlew clean test --rerun-tasks --info` and check the end of the log for `BUILD SUCCESSFUL` / failures.
- **Corporate / shim `java` (e.g. Salesforce banner):** point Gradle at a real JDK: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` (or `-v 25`) then run `./gradlew test` again.
- **`Permission denied` on `./gradlew`:** `chmod +x gradlew`

# Objectives: 
- Implementations of new metrics as Metric classes (entropy, kurtosis, value-age, time-series seasonality detectors)
- exporters to emit metrics to JSON/CSV and OpenMetadata profile payloads

# Starter tasks
- implement entropy and relative-entropy (per-column) and add tests against known distributions
- add kurtosis and non-parametric skew metrics with numerical stability checks
- value-age metric: compute median age of values (timestamp-bearing columns)
- seasonal/detection metric for time-series columns (autocorrelation or seasonal decomposition)
- integrate metrics into MetricRegistry and ensure Profiler picks them up via config

Hackathon Idea https://github.com/open-metadata/OpenMetadata/issues/26662

# Architecture:

Ingest: connects to a database (e.g., PostgreSQL/Snowflake) using JDBC.
Compute: Java library like Apache Commons Math to calculate the "Advanced Metrics" (Entropy, Kurtosis, etc.)
Export: Provide a REST endpoint or UI button to download these results as CSV/JSON.
Sync: Use the OpenMetadata Java client to push these values back to the platform.

# Scope for MVP:
- Support JDBC Connection
- Accept File-based universal path to make the system connector-agnostic
- consume OM's stored sample data via APIs when available for some supported advanced stats calculation
- push the custom metrics to OM through tableprofile APIs
- pick a schema + auth for final hackathon demo


Where is AI used ?
- Download and set up the required gradle, java's supported versions, dependencies like Apache commons
- Generating Test files
=======
# extend-profilers-openmetadata

Extends OpenMetadata's profiler with advanced statistical metrics -- entropy, kurtosis, skewness, seasonality, and value-age -- and pushes them as custom metrics into the platform.

Built for [OpenMetadata Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662).

## Why this exists

OpenMetadata's built-in profiler covers the basics: row counts, null ratios, unique counts, etc. But when you're doing real data quality work, you often need deeper statistical insight. Things like:

- Is this column's distribution normal or heavily skewed?
- How much disorder/randomness is in the data?
- Are there repeating patterns over time?
- How stale is the data in a timestamp column?

This project computes those metrics externally (via JDBC or OM's sample data), pushes them back into OpenMetadata as custom metrics, and also exports them locally as JSON and CSV for downstream consumers.

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
| OM Sample  |---->|   + SqlAwareMetric|---->| JSON/CSV Export  |
| DataSource |     +-------------------+     +------------------+
+------------+
```

**Ingest** -- connects to your database via JDBC (random sample + COUNT(*) for real row count), or uses OM's stored sample data through the API. JDBC connections are managed with try-with-resources and closed after each table.

**Compute** -- runs each applicable metric against the column data. Metric applicability is driven by column type (numeric, string, timestamp) through the `MetricRegistry`. Metrics that implement `SqlAwareMetric` (Value Age, Seasonality) prefer direct SQL computation when a JDBC connection is available, falling back to in-memory computation otherwise.

**Sync** -- registers custom metric definitions in OM (required for the UI to display them), then pushes computed values via the table profile API. The payload includes `profileSample` and `profileSampleType` so the OM UI reflects sampling metadata.

**Export** -- writes results to per-table directories with timestamped files and a `latest.json`/`latest.csv` for stable consumer access.

## How to run

### Prerequisites

- JDK 21+ (tested with Temurin)
- A running OpenMetadata instance (local Docker or remote)
- Network access to the database you want to profile

No global Gradle install needed -- the wrapper (`./gradlew`) handles everything.

### 1. Clone and build

```bash
git clone <repo-url>
cd extend-profilers-openmetadata

chmod +x gradlew        # if needed
./gradlew build
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

**Via Gradle:**
```bash
./gradlew run --args='my-config.json'
```

**Via fat JAR (standalone):**
```bash
./gradlew fatJar
java -jar build/libs/extend-profilers-openmetadata-0.1.0-SNAPSHOT-all.jar my-config.json
```

### 4. Check the results

- **OpenMetadata UI** -- go to the table's Profiler tab. Custom metrics show under each column and at the table level. Hover over the metric name to see the description with practical guidance and ranges.
- **Local exports** -- each table gets its own directory under `output/`:

```
output/
  local_postgres_openmetadata_db_public_table_entity/
    latest.json              <- always the most recent run (stable path for automation)
    latest.csv
    2026-04-19T213345.json   <- timestamped history
    2026-04-19T213345.csv
  local_postgres_openmetadata_db_public_entity_relationship/
    latest.json
    latest.csv
```

Example CSV output:
```
column,entropy,kurtosis,skewness,seasonality,valueAge
id,8.965784,N/A,N/A,N/A,N/A
updatedat,8.961784,-1.890109,0.341342,1.000000,456.700000
updatedby,1.133220,N/A,N/A,N/A,N/A
deleted,0.000000,N/A,N/A,N/A,N/A
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
```

## How it talks to OpenMetadata

The profiler uses these OM APIs in sequence:

1. `POST /api/v1/users/login` -- authenticate, get JWT token
2. `GET /api/v1/tables/name/{fqn}?fields=columns,sampleData,customMetrics` -- fetch table metadata + column types
3. `PUT /api/v1/tables/{id}/customMetric` -- register each metric definition (OM requires this before values show in the UI). Metric descriptions include practical ranges and guidance, visible as tooltips in the OM UI.
4. Compute metrics -- SQL-native where possible (Value Age, Seasonality), in-memory for the rest
5. `PUT /api/v1/tables/{id}/tableProfile` -- push table + column profiles with custom metric values, sampling metadata (`profileSample`, `profileSampleType`), and `rowCount` from COUNT(*)

Column-level metrics use `{metricName}_{columnName}` naming to avoid OM's unique-name-per-table constraint.

Each push appends a new timeseries entry in OM. Running the profiler on a schedule (e.g., cron) will build up metric history visible as line charts in the OM Profiler tab.

## Running tests

```bash
./gradlew test
```

The end-to-end test creates sample CSV data, runs all metrics, writes to CSV output, and verifies the results -- no external services needed.

If tests seem broken or print nothing:
```bash
./gradlew clean test --rerun-tasks --info
```

## Logging

Logs go to both console and `logs/profiler.log`. Configured via `logback.xml`.

Change log level at runtime:
```bash
java -Dlogback.configurationFile=/path/to/custom-logback.xml -jar app.jar config.json
```

## Troubleshooting

**`Permission denied` on `./gradlew`**
```bash
chmod +x gradlew
```

**OM custom metrics not showing in the UI**
The metric definition must be registered via `PUT /customMetric` before pushing values. The profiler does this automatically, but if you're debugging, check OM's API response for the table entity to confirm the metric definitions exist.

## Where AI was used

- Setting up Gradle, Java version compatibility, and dependency configuration
- Generating test files and test data
- Understanding OpenMetadata's architecture, API flows, and custom metric registration process

## Links

- [Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662)
- [OM Profiler Metrics Docs](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics)
- [OM Table Profiler API Reference](https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler)
- [OM Profiler Workflow](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/profiler-workflow)
>>>>>>> f8110cf (Initial Working Version)
