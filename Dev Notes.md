# Dev Notes

Hackathon issue: https://github.com/open-metadata/OpenMetadata/issues/26662

## Build

- JDK 21+, tested with Temurin 25. Gradle Wrapper 9.1.0 (`./gradlew` only, no global install).
- `./gradlew test`, `./gradlew run`, `./gradlew fatJar`
- Tests seem broken? `./gradlew clean test --rerun-tasks --info`
- Permission denied? `chmod +x gradlew`
- Corporate Java shim? `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`

## How It Works

```
Config (JSON) -- omUrl, dbUser/dbPassword, table FQNs
     |
     v
ProfileRunner  -->  OM API (register metrics + push profiles)
  |  Profiler       JSON/CSV/HTML export
  |  MetricRegistry
  v
JDBC (auto-discovered or explicit) --> ConnectionResolver
```

1. **Ingest** -- connects to DB via JDBC (random sample + COUNT(*)). JDBC URL either provided in config or auto-discovered from OM's database service API. DB credentials (`dbUser`/`dbPassword`) are mandatory top-level config fields.
2. **Compute** -- runs column-level metrics based on type (numeric/string/timestamp). Seasonality computed separately from OM profile history.
3. **Sync** -- registers custom metric definitions in OM (skips if already registered), pushes profiles via table profile API.
4. **Export** -- JSON + CSV (with table-level metrics) + `LatestReport.html` dashboard.

## Key Files

```
ProfileRunner.java        -- entry point, config parsing, wildcard expansion, JDBC connection management
Profiler.java             -- metric computation, seasonality from OM history, OM payload builder
client/
  OMClient.java           -- OM REST wrapper (auth, fetch, push, listTables)
  ConnectionResolver.java -- OM service JSON -> JDBC URL (with Docker hostname rewriting)
data/
  JdbcDataSource.java     -- JDBC sampling with TABLESAMPLE support, implements QueryCapable
  SqlDialect.java         -- DB-specific SQL (POSTGRESQL, MYSQL, GENERIC)
  QueryCapable.java       -- interface for SQL-native metrics
metrics/
  EntropyMetric, RelativeEntropyMetric, KurtosisMetric, SkewnessMetric,
  SeasonalityMetric, ValueAgeMetric
  MetricRegistry.java     -- maps metrics to column types + levels
export/
  HtmlResultWriter.java   -- HTML dashboard with collapsible cards, health scores
  CsvResultWriter.java    -- CSV export including table-level metrics
  ProfileResult.java      -- metric result container
```

## Metrics

Column-level metrics (registered in MetricRegistry):

| Metric | Numeric | String | Timestamp | Notes |
|--------|---------|--------|-----------|-------|
| Entropy | y | y | y | Shannon entropy -- measures value diversity |
| Relative Entropy | y | y | y | KL divergence vs uniform distribution |
| Kurtosis | y | - | - | Tail heaviness, needs 4+ values |
| Skewness | y | - | - | Distribution asymmetry, needs 3+ values |
| Value Age | y | - | y | Hours since latest value. SQL-native via SqlAwareMetric |

Table-level metric (computed separately, not via registry):

| Metric | Source | Notes |
|--------|--------|-------|
| Seasonality | OM profile history | Detects repeating row-count patterns. Needs 4+ profiler runs. Shows 0 if insufficient history. |

## OM API Flow

```
POST /api/v1/users/login                         -- get JWT
GET  /api/v1/tables/name/{fqn}?fields=...        -- table + columns
PUT  /api/v1/tables/{id}/customMetric             -- register metric definition
PUT  /api/v1/tables/{id}/tableProfile             -- push computed profiles (appends timeseries)
GET  /api/v1/services/databaseServices/name/{svc} -- auto-discover JDBC URL
GET  /api/v1/tables?databaseSchema={fqn}&limit=N  -- wildcard table listing (paginated)
```

Custom metric definitions must be registered before values show in OM UI. Existing definitions are detected and skipped.

## Auto-Discovery

Provide FQN and top-level `dbUser`/`dbPassword` in config. Tool calls `GET /databaseServices/name/{service}` to extract hostPort, builds JDBC URL. Docker hostnames (e.g., `openmetadata_postgresql`) are rewritten to `localhost` for host-side access.

Fallback: explicit `jdbcUrl` in table config > auto-discover from OM service.

Failed JDBC URLs are cached to avoid repeated connection timeouts when profiling large schemas.

## Wildcard Profiling

`"fqn": "service.db.schema.*"` expands to all tables via paginated `listTables`. Deduplicates against explicitly listed tables. Progress logged every 25 tables.

## HTML Dashboard

- Collapsible `<details>/<summary>` cards per table
- Health scores based on metric values
- Color coding: green/amber/red for metric ratings
- "View in OpenMetadata" deep link per table
- Collapsible "Get Started" guide at the top
- Deployed to GitHub Pages via CI workflow

## Logging

`src/main/resources/logback.xml`. Default INFO. Set DEBUG for per-metric/per-query detail. OkHttp and Jackson forced to WARN.

## Demo Data

`misc/demo_data.sql` creates two tables (500 rows each) in `openmetadata_db.public`:

- **demo_sales_clean** -- balanced categories, normal amount distribution, recent timestamps
- **demo_sales_dirty** -- 95% single category, extreme outlier amounts, stale timestamps, constant region

Profiling both side by side shows dramatic metric differences across all six metrics.

## Known Limitations

- No parallelism (sequential per column per table)
- No OkHttp connection pooling or timeout config
- Not stress-tested on 1M+ row tables or 50+ column tables

## Links

- [Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662)
- [Table Profiler API](https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler)
- [Profiler Metrics Docs](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics)
- [OM Profiler Registry (source)](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/profiler/metrics/registry.py)
