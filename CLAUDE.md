# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew compileJava          # Compile only
./gradlew test                 # Run all tests
./gradlew test --tests "MetricTests"  # Run a single test class
./gradlew run --args='sample-config.json'  # Run profiler with config
./gradlew fatJar               # Build fat JAR
java -jar build/libs/extend-profilers-openmetadata-0.1.0-SNAPSHOT-all.jar my-config.json
```

JDK 21+ required. Use `./gradlew` (wrapper), not a global Gradle install.

## Architecture

This is a CLI tool that extends OpenMetadata's profiler with advanced statistical metrics. It connects to databases, computes metrics, pushes results to OM's API, and generates local reports.

**Main flow** (ProfileRunner → Profiler → Export):

1. **ProfileRunner** parses JSON config, authenticates with OM, expands wildcard FQNs, and for each table attempts a 3-tier data source fallback: explicit JDBC → auto-discovered JDBC from OM → OM sample data API.

2. **Profiler.runWith()** is the core engine. It delegates to helpers:
   - `parseExistingMetrics()` — skips already-registered custom metrics
   - `computeTableMetrics()` — table-level metrics (entropy)
   - `computeSeasonality()` — from OM profile history (90-day lookback, needs ≥4 runs)
   - `computeColumnProfiles()` — per-column advanced metrics
   - `buildPayload()` — assembles JSON conforming to OM's `PUT /tables/{id}/tableProfile` API

3. **Export** writes per-table JSON/CSV + a consolidated `LatestReport.html`.

**Key interfaces:**
- `DataSource` — abstraction over JDBC and OM sample data
- `QueryCapable` — allows metrics to run native SQL (implemented by JdbcDataSource, not SampleDataSource)
- `Metric` / `SqlAwareMetric` — compute from in-memory values or native SQL
- `MetricRegistry` — maps metrics to column types (NUMERIC/STRING/TIMESTAMP) and levels (table/column)

**SQL dialect handling:** `SqlDialect` enum (POSTGRESQL, MYSQL, GENERIC) abstracts DB-specific SQL — random sampling, timestamp age, epoch conversion. Auto-detected from JDBC URL prefix. Basic stats SQL (COUNT, MIN, MAX, AVG, DISTINCT) is standard SQL and needs no dialect.

**Auto-discovery:** `ConnectionResolver` takes OM's database service JSON and builds a JDBC URL. Supports postgres, mysql, mariadb, mssql, redshift, snowflake. Service name extracted from FQN's first segment.

## OM API Integration

The tool uses these OM endpoints (see [API docs](https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler)):
- `POST /api/v1/users/login` — JWT auth
- `GET /api/v1/tables/name/{fqn}` — fetch table schema + sample data
- `PUT /api/v1/tables/{id}/customMetric` — register metric definition (required before values show in UI)
- `PUT /api/v1/tables/{id}/tableProfile` — push profiles (appends timeseries, not replace)
- `GET /api/v1/services/databaseServices/name/{svc}` — auto-discover JDBC config
- `GET /api/v1/tables?databaseSchema={fqn}` — wildcard table listing (paginated)

Custom metric definitions must be registered before their values appear in OM UI. The code checks existing definitions and skips redundant registrations.

## Metrics

Six advanced metrics registered via `MetricRegistry.defaults()`:
- **All types:** entropy, relative entropy
- **Numeric only:** kurtosis, skewness
- **Timestamp only:** valueAge (SQL-native via SqlAwareMetric)
- **Table-level special:** seasonality (computed from OM profile history, not from data)

Column type classification happens in `MetricRegistry.classifyOmType()` which maps OM dataType strings (e.g., "INT", "VARCHAR", "TIMESTAMP") to ColType enum values.

## Testing

Two test files:
- `MetricTests.java` — unit tests for each metric, registry classification, HTML rating logic
- `ProfilerEndToEndTest.java` — integration test using CSV data, runs full pipeline minus OM API calls

Tests use JUnit 5. No mocking framework — integration test creates real CSV files and runs actual computation.

## Conventions

- Logging: `src/main/resources/logback.xml`. INFO for progress (every 25 tables), DEBUG for per-metric/per-query detail. OkHttp and Jackson forced to WARN.
- JSON payloads are built via StringBuilder (no serialization library for OM payloads) — see `columnProfileJson()` and `buildPayload()` in Profiler.java.
- JDBC connections managed with try-with-resources in ProfileRunner. All SQL-native metrics reuse the same connection via QueryCapable.
- HTML report uses inline CSS, no external dependencies. Collapsible cards via `<details>/<summary>`.
- The `omPassword` field in config is Base64-encoded (OM's login API requirement).
