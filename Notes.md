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
- Understanding OpenMetadata architecture and flows




┌─────────────────────────────────────────────────────────────────────┐
│                         Docker Network (app_net)                    │
│                                                                     │
│  ┌──────────┐    ┌─────────────────────┐    ┌───────────────────┐   │
│  │  MySQL   │◄──►│  OM Server (:8585)  │◄──►│ Elasticsearch     │   │
│  │  (:3306) │    │  (API + UI)         │    │ (:9200)           │   │
│  └──────────┘    └─────────┬───────────┘    └───────────────────┘   │
│       ▲                    │                                        │
│       │                    │ REST calls                             │
│       │                    ▼                                        │
│       │          ┌─────────────────────┐                            │
│       └──────────│  Airflow/Ingestion  │                            │
│                  │  (:8080)            │                            │
│                  └─────────────────────┘                            │
└─────────────────────────────────────────────────────────────────────┘


Relavant API's:
------------------
Call: GET 

URL: http://localhost:8585/api/v1/tables/sample_data.ecommerce_db.shopify.dim_address/tableProfile?startTs=1775865600000&endTs=1776556799999

Response:  {
    "data": [
        {
            "timestamp": 1776336700955,
            "profileSampleType": "PERCENTAGE",
            "columnCount": 12.0,
            "rowCount": 14567.0,
            "sizeInByte": 16890.0,
            "createDateTime": "2023-07-24T07:00:48.000000Z",
            "customMetrics": [
                {
                    "name": "CountOfUSAddress",
                    "value": 15467.0
                },
                {
                    "name": "CountOfFRAddress",
                    "value": 1467.0
                }
            ]
        },
        {
            "timestamp": 1776333793040,
            "profileSampleType": "PERCENTAGE",
            "columnCount": 12.0,...


# OpenMetadata API Reference (for custom profiler integration)

## Metric Applicability by Column Type

| Metric | Numeric | String/Categorical | Timestamp | Min Data Points |
|--------|---------|-------------------|-----------|-----------------|
| Entropy | yes | yes | yes | 1 |
| Kurtosis | yes | no | no | 4 |
| Skewness | yes | no | no | 3 |
| Seasonality | yes | no | no | 4 |
| ValueAge | no | no | yes | 1 |

## Call Sequence

```
1. POST /api/v1/users/login                                         -> get JWT token
2. GET  /api/v1/tables/name/{fqn}?fields=columns,sampleData,customMetrics  -> table info + data
3. PUT  /api/v1/tables/{id}/customMetric                             -> register each metric definition (once per metric per column)
4. [Compute metrics locally]
5. PUT  /api/v1/tables/{id}/tableProfile                             -> push all results
```

## API 1: Login

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

## API 2: Get Table by FQN (columns + sample data + existing custom metrics)

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
    "rows": [
      ["1", "{\"key\":\"val\"}", "1776336700955"],
      ["2", "{\"key\":\"val2\"}", "1776336800000"]
    ]
  },
  "customMetrics": []
}
```

## API 3: Register Custom Metric Definition (once per metric per column)

The UI will NOT display custom metric values unless the definition is registered first.

```
PUT http://localhost:8585/api/v1/tables/{tableId}/customMetric

Headers:
  Authorization: Bearer <accessToken>
  Content-Type: application/json

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

Response: full table entity JSON (confirms registration)
```

Metrics to register:

| name | description |
|------|-------------|
| entropy | Shannon entropy - randomness/disorder in data |
| kurtosis | Tail heaviness of distribution - outlier indicator |
| skewness | Asymmetry of distribution |
| seasonality | Dominant repeating period via autocorrelation |
| valueAge | Median age (hours) of timestamp values - staleness |

## API 4: Push Table + Column Profiles with Custom Metric Values

```
PUT http://localhost:8585/api/v1/tables/{tableId}/tableProfile

Headers:
  Authorization: Bearer <accessToken>
  Content-Type: application/json

Body:
{
  "tableProfile": {
    "timestamp": 1776494509000,
    "rowCount": 120,
    "columnCount": 5
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
      "customMetrics": [
        {"name": "entropy", "value": 4.21},
        {"name": "kurtosis", "value": 2.15},
        {"name": "skewness", "value": 0.67},
        {"name": "seasonality", "value": 0.0}
      ]
    },
    {
      "name": "city",
      "timestamp": 1776494509000,
      "valuesCount": 120,
      "nullCount": 5,
      "nullProportion": 0.04,
      "customMetrics": [
        {"name": "entropy", "value": 3.42}
      ]
    },
    {
      "name": "created_at",
      "timestamp": 1776494509000,
      "valuesCount": 120,
      "nullCount": 0,
      "nullProportion": 0.0,
      "customMetrics": [
        {"name": "entropy", "value": 2.89},
        {"name": "valueAge", "value": 456.7}
      ]
    }
  ]
}

Response: full table entity JSON with profile + column profiles
```

## API 5: Get Latest Profile (verify pushed data)

```
GET http://localhost:8585/api/v1/tables/{fqn}/tableProfile/latest

Headers:
  Authorization: Bearer <accessToken>

Response: full table with profile + column profiles + customMetrics
```

## API 6: Get Column Profile Time-Series

```
GET http://localhost:8585/api/v1/tables/{column_fqn}/columnProfile?startTs={epoch_ms}&endTs={epoch_ms}

Headers:
  Authorization: Bearer <accessToken>

Example:
GET http://localhost:8585/api/v1/tables/local_postgres.openmetadata_db.public.table_entity.json/columnProfile?startTs=1776400000000&endTs=1776500000000

Response:
{
  "data": [
    {
      "name": "json",
      "timestamp": 1776494509000,
      "valuesCount": 120.0,
      "customMetrics": [
        {"name": "entropy", "value": 4.21},
        {"name": "kurtosis", "value": 2.15}
      ]
    }
  ]
}
```

## API 7: Delete a Custom Metric (cleanup)

```
DELETE http://localhost:8585/api/v1/tables/{tableId}/customMetric/{columnName}/{metricName}

Headers:
  Authorization: Bearer <accessToken>

Example:
DELETE http://localhost:8585/api/v1/tables/c2ee1a76-.../customMetric/json/entropy
```

## What's been done (developer notes)

### Sampling strategy
- JdbcDataSource uses `ORDER BY RANDOM() LIMIT N` instead of plain `LIMIT N` to avoid insertion-order bias.
- `SELECT COUNT(*)` runs first to get the real row count; the sample size is then reported via `profileSample`/`profileSampleType` in the OM payload.
- Configurable as fixed row count (`"sampleType": "ROWS"`) or percentage (`"sampleType": "PERCENTAGE"`). Percentage is converted to an effective row limit using the COUNT(*) result.
- Note: `ORDER BY RANDOM()` is PostgreSQL syntax. For other databases (MySQL, Snowflake, etc.), the query would need to be adapted (e.g., `ORDER BY RAND()`, `TABLESAMPLE`).

### SQL-native metrics (SqlAwareMetric interface)
- Metrics that benefit from exact results or special ordering can implement `SqlAwareMetric` instead of plain `Metric`.
- `SqlAwareMetric.computeSql(Connection, tableName, columnName, orderByColumn)` is called when JDBC is available. If it returns null, the Profiler falls back to `compute()` on the in-memory sample.
- **ValueAgeMetric** uses SQL to compute `EXTRACT(EPOCH FROM (NOW() - col)) / 3600` on the full table -- exact median, no sampling, no string parsing.
- **SeasonalityMetric** uses SQL to fetch data `ORDER BY <timestampColumn>` since autocorrelation requires time-ordered data. The timestamp column is detected from OM's column metadata (not JDBC metadata). If no timestamp column exists, falls back to in-memory.

### Connection management
- JdbcDataSource implements `AutoCloseable`. ProfileRunner uses try-with-resources so the JDBC connection is closed after each table is profiled.
- All SQL-native metrics reuse the same connection from `DataSource.getConnection()` -- no new connections created per metric.

### Data input fallback
- If a table config entry has `jdbcUrl`, the JDBC path is used. If not, the profiler falls back to OM's sample data via the table API.
- SampleDataSource inherits default implementations from the DataSource interface -- no special handling needed.

### Output structure
- Each table gets its own directory under `outputDir`: `output/<table_fqn>/`
- Each run writes timestamped files (e.g., `2026-04-19T213345.json`) plus overwrites `latest.json` and `latest.csv` for stable consumer access.

### Metric descriptions
- Each metric's `getDescription()` includes practical ranges and actionable guidance (e.g., "Above 5 = heavy outliers, investigate extreme values").
- These descriptions flow to OM via `addCustomMetric` and appear as tooltips in the Profiler tab UI.

### OM timeseries integration
- Each `PUT /tables/{id}/tableProfile` call appends a new timeseries entry in OM (not a replace). Running the profiler repeatedly builds up history shown as line charts in the OM UI.
- We don't yet have built-in scheduling -- the profiler is a one-shot CLI tool. For recurring runs, use external scheduling (cron, Airflow, etc.).

### Known limitations / remaining work

- Scheduling: no built-in scheduler. For timeseries to be useful, run this via cron or integrate with OM's profiler agent schedule.
- `addCustomMetric` is called every run even if the metric is already registered. Should check existing metrics first or handle idempotently.
- `ORDER BY RANDOM()` is PostgreSQL-specific. Needs dialect abstraction for MySQL (`RAND()`), Snowflake (`TABLESAMPLE`), etc.
- No guardrails for huge tables -- COUNT(*) on a billion-row table could be slow. Consider using table statistics or estimated counts.
- CsvDataReader does not implement the DataSource interface and is only used in tests.
- Seasonality on random-sampled data (in-memory fallback) is unreliable since ordering is lost.
- No UI or Java endpoints -- this is a CLI tool only.

https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler

https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics

https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/profiler/metrics/registry.py

http://localhost:8585/table/local_postgres.openmetadata_db.public.table_entity/profiler/table-profile

https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/profiler-workflow

https://chatgpt.com/g/g-p-69a9a5cabce88191a9214da4df3ceca1-getting-remote-job/c/69e10716-b6b8-8320-b200-edad6af96baf