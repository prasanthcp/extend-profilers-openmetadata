# Project: Extend the profiler with advanced statistical metrics (entropy, kurtosis, skewness, seasonality, value-age) and make them exportable for downstream consumers #26662

## Build (Gradle & Java)

- **JDK:** 21+ recommended. This repo is tested with **Temurin 25** as the JVM running Gradle; the **Gradle Wrapper uses 9.1.0** (needed so Gradle itself runs on Java 25). Compiled bytecode targets **Java 21** (`release = 21`).
- **Commands:** from this directory run `./gradlew test`, `./gradlew run`, or `./gradlew build`.
- **No global Gradle install required** -- use `./gradlew` only.
- **If `test` seems broken or prints nothing useful:** run `./gradlew clean test --rerun-tasks --info` and check the end of the log for `BUILD SUCCESSFUL` / failures.
- **Corporate / shim `java` (e.g. Salesforce banner):** point Gradle at a real JDK: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` (or `-v 25`) then run `./gradlew test` again.
- **`Permission denied` on `./gradlew`:** `chmod +x gradlew`

## Objectives
- Implementations of new metrics as Metric classes (entropy, kurtosis, value-age, time-series seasonality detectors)
- Exporters to emit metrics to JSON/CSV and OpenMetadata profile payloads
- Multi-dialect JDBC support (PostgreSQL, MySQL, etc.)
- Auto-discovery of JDBC connections from OM database service API
- Schema-level wildcard profiling
- Self-sufficient basic stats (no dependency on OM profiler having run)

Hackathon Idea https://github.com/open-metadata/OpenMetadata/issues/26662

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
| OM Sample  |---->|  + QueryCapable   |---->| JSON/CSV/HTML    |
| DataSource |     +-------------------+     +------------------+
+------------+           |
                         v
               +-------------------+
               | ConnectionResolver|
               | (auto-discovery)  |
               +-------------------+
```

**Ingest** -- connects to database via JDBC (random sample + COUNT(*) for real row count), or auto-discovers JDBC from OM's database service API, or uses OM's stored sample data through the API. JDBC connections are managed with try-with-resources and closed after each table.

**Compute** -- runs each applicable metric against the column data. Metric applicability is driven by column type (numeric, string, timestamp) through the `MetricRegistry`. Metrics that implement `SqlAwareMetric` (Value Age) prefer direct SQL computation when a JDBC connection is available, falling back to in-memory computation otherwise. Basic column stats (nullCount, uniqueCount, min, max, mean) are computed independently via SQL aggregates or in-memory.

**Sync** -- registers custom metric definitions in OM (required for the UI to display them), then pushes computed values via the table profile API. Existing metric definitions are detected and skipped to avoid redundant API calls.

**Export** -- writes results to per-table directories with timestamped files and a `latest.json`/`latest.csv` for stable consumer access. Generates a single `LatestReport.html` summarizing all profiled tables with collapsible cards, color-coded metrics, health scores, and human-readable interpretations.

## Project Structure

```
src/main/java/org/openmetadata/hackathon/extendprofiler/
  ProfileRunner.java        -- CLI entry point, config parsing, wildcard expansion, 3-tier fallback
  Profiler.java             -- core engine: runs metrics, computes basic stats, builds OM payloads
  client/
    OMClient.java           -- OM REST API wrapper (auth, fetch, push, listTables, fetchDatabaseService)
    OMClientException.java  -- custom exception with HTTP status codes
    ConnectionResolver.java -- resolves OM database service JSON into JDBC connection details
  data/
    DataSource.java         -- interface for any data source
    JdbcDataSource.java     -- JDBC source: random sampling, COUNT(*), implements QueryCapable
    SampleDataSource.java   -- backed by OM's stored sample data
    QueryCapable.java       -- interface: executeQuery(), getQueryTarget(), getDialect()
    SqlDialect.java         -- enum abstracting DB-specific SQL (POSTGRESQL, MYSQL, GENERIC)
    CsvDataReader.java      -- CSV reader (test utility)
    ColumnInfo.java         -- column name + type pair
  metrics/
    Metric.java             -- interface: getName(), getDescription(), compute()
    SqlAwareMetric.java     -- extends Metric with computeNative(QueryCapable, ...) for SQL-native computation
    MetricRegistry.java     -- registers metrics with their applicable column types
    EntropyMetric.java      -- Shannon entropy
    RelativeEntropyMetric.java -- KL divergence vs uniform
    KurtosisMetric.java     -- distribution tail heaviness (Apache Commons Math)
    SkewnessMetric.java     -- distribution asymmetry (Apache Commons Math)
    SeasonalityMetric.java  -- autocorrelation-based period detection
    ValueAgeMetric.java     -- median staleness of timestamp values (dialect-aware SQL)
  export/
    ProfileResult.java      -- holds table + column metrics + basic stats + omUrl
    JsonResultWriter.java   -- JSON export
    CsvResultWriter.java    -- CSV export
    HtmlResultWriter.java   -- HTML report: collapsible cards, health scores, color-coded metrics
src/main/resources/
  logback.xml               -- logging config (INFO default, DEBUG for verbose)
```

## Metric Applicability by Column Type

| Metric | Numeric | String/Categorical | Timestamp | Min Data Points |
|--------|---------|-------------------|-----------|-----------------|
| Entropy | yes | yes | yes | 1 |
| Relative Entropy | yes | yes | yes | 1 |
| Kurtosis | yes | no | no | 4 |
| Skewness | yes | no | no | 3 |
| Seasonality | yes | no | no | 4 |
| ValueAge | no | no | yes | 1 |

## OpenMetadata API Reference

### Call Sequence

```
1. POST /api/v1/users/login                                         -> get JWT token
2. GET  /api/v1/tables/name/{fqn}?fields=columns,sampleData,customMetrics  -> table info + data
3. PUT  /api/v1/tables/{id}/customMetric                             -> register each metric definition (once per metric per column)
4. [Compute metrics locally]
5. PUT  /api/v1/tables/{id}/tableProfile                             -> push all results
```

### API 1: Login

```
POST http://localhost:8585/api/v1/users/login

Headers:
  Content-Type: application/json

Body:
{
  "email": "admin@open-metadata.org",
  "password": "YWRtaW4="                 // password must be Base64-encoded
}

Response:
{
  "accessToken": "eyJraWQ...",
  "tokenType": "Bearer",
  "expiryDuration": 1776497204946
}
```

### API 2: Get Table by FQN

```
GET http://localhost:8585/api/v1/tables/name/{fqn}?fields=columns,sampleData,customMetrics

Headers:
  Authorization: Bearer <accessToken>

Response:
{
  "id": "c2ee1a76-...",
  "fullyQualifiedName": "local_postgres.openmetadata_db.public.table_entity",
  "columns": [
    {"name": "id", "dataType": "NUMERIC", "fullyQualifiedName": "...table_entity.id"},
    {"name": "json", "dataType": "VARCHAR", "fullyQualifiedName": "...table_entity.json"},
    {"name": "updatedAt", "dataType": "TIMESTAMP", "fullyQualifiedName": "...table_entity.updatedAt"}
  ],
  "sampleData": {
    "columns": ["id", "json", "updatedAt"],
    "rows": [["1", "{...}", "1776336700955"]]
  },
  "customMetrics": []
}
```

### API 3: Register Custom Metric Definition

The UI will NOT display custom metric values unless the definition is registered first.

```
PUT http://localhost:8585/api/v1/tables/{tableId}/customMetric

Body (column-level):
{
  "name": "entropy",
  "description": "Shannon entropy - randomness/disorder in column data",
  "columnName": "city",
  "expression": "SELECT 0 as entropy"
}

Body (table-level, omit columnName):
{
  "name": "entropy",
  "description": "Shannon entropy - randomness/disorder",
  "expression": "SELECT 0 as entropy"
}
```

### API 4: Push Table + Column Profiles

```
PUT http://localhost:8585/api/v1/tables/{tableId}/tableProfile

Body:
{
  "tableProfile": {
    "timestamp": 1776494509000,
    "rowCount": 120,
    "columnCount": 5,
    "profileSample": 500,
    "profileSampleType": "ROWS",
    "customMetrics": [{"name": "entropy", "value": 4.21}]
  },
  "columnProfile": [
    {
      "name": "id",
      "timestamp": 1776494509000,
      "valuesCount": 120,
      "nullCount": 0,
      "nullProportion": 0.0,
      "uniqueCount": 120,
      "uniqueProportion": 1.0,
      "min": 1,
      "max": 120,
      "mean": 60.5,
      "customMetrics": [
        {"name": "entropy", "value": 4.21},
        {"name": "kurtosis", "value": 2.15}
      ]
    }
  ]
}
```

### API 5: Get Latest Profile

```
GET http://localhost:8585/api/v1/tables/{fqn}/tableProfile/latest
```

### API 6: Get Database Service (for auto-discovery)

```
GET http://localhost:8585/api/v1/services/databaseServices/name/{serviceName}

Response includes connection.config with hostPort, username, password, database, serviceType.
ConnectionResolver maps serviceType -> JDBC URL prefix.
```

### API 7: List Tables in Schema (for wildcard)

```
GET http://localhost:8585/api/v1/tables?databaseSchema={schemaFqn}&limit=100&after={cursor}

Paginated. Returns fullyQualifiedName for each table.
```

### API 8: Delete a Custom Metric

```
DELETE http://localhost:8585/api/v1/tables/{tableId}/customMetric/{columnName}/{metricName}
```

## OM Docker Architecture

```
+-----------------------------------------------------------------+
|                     Docker Network (app_net)                      |
|                                                                   |
|  +----------+    +---------------------+    +------------------+  |
|  |  MySQL   |<-->|  OM Server (:8585)  |<-->| Elasticsearch    |  |
|  |  (:3306) |    |  (API + UI)         |    | (:9200)          |  |
|  +----------+    +---------+-----------+    +------------------+  |
|       ^                    |                                      |
|       |                    | REST calls                           |
|       |                    v                                      |
|       |          +---------------------+                          |
|       +----------|  Airflow/Ingestion  |                          |
|                  |  (:8080)            |                          |
|                  +---------------------+                          |
+-----------------------------------------------------------------+
```

## Developer Notes

### Multi-Dialect JDBC (SqlDialect)

- `SqlDialect` enum: `POSTGRESQL`, `MYSQL`, `GENERIC` (GENERIC uses PostgreSQL syntax as fallback)
- Each dialect overrides: `randomSampleQuery()`, `timestampAgeHoursSql()`, `epochAgeHoursSql()`, `stddevFunction()`
- Auto-detected from JDBC URL prefix via `SqlDialect.fromJdbcUrl(String)`
- POSTGRESQL: `ORDER BY RANDOM()`, `EXTRACT(EPOCH FROM ...)`, `STDDEV_SAMP`
- MYSQL: `ORDER BY RAND()`, `TIMESTAMPDIFF(SECOND,...)`, `UNIX_TIMESTAMP()`, `STDDEV_SAMP`
- Basic stats SQL (COUNT, MIN, MAX, AVG, COUNT DISTINCT) is standard SQL -- no dialect needed

### QueryCapable Interface

- Decouples metrics from raw `java.sql.Connection`
- `executeQuery(String sql)` -> `List<Map<String, String>>`
- `getQueryTarget()` -> table name, `getDialect()` -> SqlDialect
- JdbcDataSource implements it; SampleDataSource does not (falls back to in-memory)
- `SqlAwareMetric.computeNative(QueryCapable, columnName, colType)` replaces old `computeSql(Connection, ...)`

### Auto-Discovery (ConnectionResolver)

- User provides just FQN in config -- no JDBC URL, credentials, or table name needed
- `OMClient.fetchDatabaseService(serviceName)` gets connection config from OM
- `ConnectionResolver.resolve(serviceJson, fqn)` maps serviceType to JDBC URL prefix
- Supported: postgres, mysql, mariadb, mssql, redshift, snowflake
- Extracts table name from FQN (4th segment: `service.database.schema.table`)
- 3-tier fallback: explicit JDBC in config > auto-discover from OM > OM sample data

### Schema Wildcard Profiling

- Config entry `"fqn": "service.db.schema.*"` expands to all tables in that schema
- `OMClient.listTables(schemaFqn)` handles pagination via `after` cursor
- Each discovered table is processed with the same config entry's settings (sampleLimit, sampleType)
- Progress logged every 25 tables: "Progress: X/Y tables processed"

### Basic Column Stats (Self-Sufficient Profiling)

- Computed in `Profiler.computeBasicStats()` -- SQL path for JDBC, in-memory fallback otherwise
- SQL path runs on full table (not sample) for accuracy: `COUNT(*)`, `COUNT(col)`, `COUNT(DISTINCT col)`, `MIN`, `MAX`, `AVG`
- Stored in `ProfileResult.columnBasicStats` and included in OM profile payload
- Pushed as standard OM fields: `nullCount`, `nullProportion`, `uniqueCount`, `uniqueProportion`, `min`, `max`, `mean`
- SQL may fail for unsupported types (jsonb, boolean) -- falls back silently to in-memory
- Basic stats contribute to health score: null proportion >10% and uniqueness <50% count as unhealthy

### HTML Report

- Collapsible table cards using `<details>/<summary>` -- click to expand
- Health scores: overall (average of all tables) and per-table (based on basic stats + advanced metrics)
- Color coding: green (good), amber (warn), red (bad), grey (N/A) for all metric values
- Basic Profile table: nulls, null%, unique, unique%, min, max, mean -- all color-coded
- Advanced Metrics table: entropy, relative entropy, kurtosis, skewness, seasonality, value age
- "View in OpenMetadata" link per table -> `{omUrl}/table/{fqn}/profiler/table-profile`
- Non-numeric min/max/mean (UUIDs, text) shown as "--" (dash)
- Epoch timestamps (>10 billion) shown as "--" to avoid displaying raw millis

### Sampling Strategy

- JdbcDataSource uses dialect-aware random sampling (`ORDER BY RANDOM()` for PG, `ORDER BY RAND()` for MySQL)
- `SELECT COUNT(*)` runs first for real row count; sample size reported via `profileSample`/`profileSampleType`
- Configurable as fixed row count (`"sampleType": "ROWS"`) or percentage (`"sampleType": "PERCENTAGE"`)
- Percentage converted to effective row limit using COUNT(*) result

### SQL-Native Metrics

- Metrics implementing `SqlAwareMetric` get a `QueryCapable` source with dialect awareness
- **ValueAgeMetric**: uses `dialect.timestampAgeHoursSql()` / `dialect.epochAgeHoursSql()` for exact median age computation
- **SeasonalityMetric**: computed from OM profile history (last 90 days), not from SQL -- needs >= 4 profile runs

### Connection Management

- JdbcDataSource implements `AutoCloseable`. ProfileRunner uses try-with-resources.
- All SQL-native metrics reuse the same connection via `QueryCapable.executeQuery()`

### Custom Metric Idempotency

- Existing `customMetrics` parsed from `fetchTable` response before registering
- Already-registered metrics skipped, avoiding redundant PUT calls on repeat runs

### Logging

- Logback config at `src/main/resources/logback.xml` (was previously misplaced in `src/main/java/...`)
- Default level: INFO -- shows login, discovery, progress (every 25 tables), summary
- DEBUG: shows JDBC connections, metric registration, SQL queries, query failures
- OkHttp and Jackson loggers set to WARN to suppress library noise

### OM Timeseries Integration

- Each `PUT /tables/{id}/tableProfile` appends a new timeseries entry in OM (not a replace)
- Running repeatedly builds up history shown as line charts in OM UI
- No built-in scheduling -- use external scheduling (cron, Airflow, etc.) for recurring runs

## Completed

- [x] Entropy and relative entropy metrics with tests
- [x] Kurtosis and skewness metrics with numerical stability checks
- [x] Value-age metric (median staleness of timestamp columns)
- [x] Seasonality metric via autocorrelation on OM profile history
- [x] MetricRegistry with column-type-based metric applicability
- [x] JDBC data source with random sampling and COUNT(*)
- [x] OM sample data source (API-based fallback)
- [x] JSON/CSV/HTML export with per-table directories
- [x] Push custom metrics to OM via tableProfile API
- [x] Custom metric idempotency (skip already-registered)
- [x] HTML report with health scores, color coding, interpretations
- [x] Multi-dialect JDBC (SqlDialect enum: PostgreSQL, MySQL, Generic)
- [x] QueryCapable interface decoupling metrics from raw Connection
- [x] Auto-discovery of JDBC connections from OM database service API
- [x] Schema-level wildcard profiling with pagination
- [x] Basic column stats (nullCount, uniqueCount, min, max, mean) -- self-sufficient profiling
- [x] Health score includes basic stats (null proportion + uniqueness)
- [x] Collapsible table cards in HTML report
- [x] "View in OpenMetadata" links in HTML report
- [x] Non-numeric / epoch values filtered from min/max/mean display
- [x] Logging cleanup -- verbose logs moved to DEBUG, logback.xml placed in correct location
- [x] MySQL JDBC driver added to build

## Known Limitations / Remaining Work

- No built-in scheduler. For timeseries to be useful, run via cron or integrate with OM's profiler agent schedule.
- No guardrails for huge tables -- COUNT(*) and ORDER BY RANDOM() on billion-row tables could be slow. Consider estimated counts.
- Seasonality on random-sampled data (in-memory fallback) is unreliable since ordering is lost.
- No UI or Java endpoints -- CLI tool only.
- Snowflake/Redshift JDBC drivers not included in build (add as needed).

## Reference Links

- https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler
- https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics
- https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/profiler/metrics/registry.py
- https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/profiler-workflow
- http://localhost:8585/table/local_postgres.openmetadata_db.public.table_entity/profiler/table-profile
