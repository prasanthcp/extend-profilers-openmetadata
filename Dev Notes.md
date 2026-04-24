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
Config (JSON)
     |
     v
ProfileRunner  -->  OM API (push profiles)
  |  Profiler       JSON/CSV/HTML export
  |  MetricRegistry
  v
JDBC / OM Sample Data / Auto-Discovery (ConnectionResolver)
```

1. **Ingest** -- JDBC (random sample + COUNT(*)) or OM sample data API. Auto-discovers JDBC from OM's database service config if no URL given.
2. **Compute** -- runs advanced metrics per column based on type (numeric/string/timestamp).
3. **Sync** -- registers custom metric definitions in OM, pushes profiles via table profile API.
4. **Export** -- per-table JSON/CSV + single `LatestReport.html` with collapsible cards, health scores, color coding.

## Key Files

```
ProfileRunner.java      -- entry point, config, wildcard expansion, 3-tier fallback
Profiler.java           -- runs metrics, builds OM payloads
client/
  OMClient.java         -- OM REST wrapper (auth, fetch, push, listTables)
  ConnectionResolver.java -- OM service JSON -> JDBC connection
data/
  JdbcDataSource.java   -- JDBC sampling, implements QueryCapable
  SampleDataSource.java -- OM sample data fallback
  SqlDialect.java       -- DB-specific SQL (POSTGRESQL, MYSQL, GENERIC)
  QueryCapable.java     -- interface: executeQuery(), getDialect()
metrics/
  EntropyMetric, RelativeEntropyMetric, KurtosisMetric, SkewnessMetric,
  SeasonalityMetric, ValueAgeMetric
export/
  HtmlResultWriter.java -- the big HTML report
```

## Metrics

| Metric | Numeric | String | Timestamp | Min rows |
|--------|---------|--------|-----------|----------|
| Entropy | y | y | y | 1 |
| Relative Entropy | y | y | y | 1 |
| Kurtosis | y | - | - | 4 |
| Skewness | y | - | - | 3 |
| Seasonality | y | - | - | 4 |
| ValueAge | - | - | y | 1 |

## OM API Flow

```
POST /api/v1/users/login                         -- get JWT
GET  /api/v1/tables/name/{fqn}?fields=...        -- table + columns + sample data
PUT  /api/v1/tables/{id}/customMetric             -- register metric definition (once per metric)
PUT  /api/v1/tables/{id}/tableProfile             -- push computed profiles
GET  /api/v1/services/databaseServices/name/{svc} -- auto-discover JDBC config
GET  /api/v1/tables?databaseSchema={fqn}&limit=N  -- wildcard table listing
DELETE /api/v1/tables/{id}/customMetric/{col}/{metric} -- remove metric
```

Full API docs: [Table Profiler API](https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler) | [Profiler Metrics](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics)

Custom metric definitions must be registered before values show up in OM UI. Existing definitions are detected and skipped on repeat runs.

Each `PUT tableProfile` appends a timeseries entry (not replace) -- repeated runs build line charts in OM.

## Dialect Handling

`SqlDialect` enum auto-detected from JDBC URL. Overrides: `randomSampleQuery()`, `timestampAgeHoursSql()`, `epochAgeHoursSql()`.

- PG: `ORDER BY RANDOM()`, `EXTRACT(EPOCH FROM ...)`
- MySQL: `ORDER BY RAND()`, `TIMESTAMPDIFF(...)`, `UNIX_TIMESTAMP()`

## Auto-Discovery

Just provide FQN in config, no JDBC credentials needed. Tool calls `GET /databaseServices/name/{service}`, extracts hostPort/user/password, builds JDBC URL. Supports: postgres, mysql, mariadb, mssql, redshift, snowflake.

3-tier fallback: explicit JDBC > auto-discover from OM > OM sample data.

## Wildcard Profiling

`"fqn": "service.db.schema.*"` expands to all tables via paginated `listTables`. Progress logged every 25 tables.

## HTML Report

- Collapsible `<details>/<summary>` cards per table
- Health scores based on advanced metrics
- Color coding: green/amber/red/grey
- "View in OpenMetadata" link per table

## Logging

Config: `src/main/resources/logback.xml`. Default INFO. Set DEBUG for per-metric/per-query detail. OkHttp and Jackson forced to WARN.

## Completed

- 6 advanced metrics (entropy, relative entropy, kurtosis, skewness, seasonality, value-age)
- Multi-dialect JDBC (PostgreSQL, MySQL, Generic)
- Auto-discovery from OM database service API
- Schema-level wildcard profiling
- QueryCapable interface (decouples metrics from raw Connection)
- JSON/CSV/HTML export
- Push to OM via tableProfile API + custom metric registration
- Collapsible HTML cards, health scores, color coding
- Logging cleanup (verbose -> DEBUG)
- Unit tests for SqlDialect, ConnectionResolver, wildcard expansion
- Live verification against OM Docker (auto-discovery, wildcards, OM UI check)
- GitHub Pages for HTML report (easiest)
- Add code test runs and standard tests in git at each push. maybe qodo or copilot.

## Pending

- [ ] Stress test: 1M+ row tables, 100+ table schemas, wide tables (50+ cols), high-cardinality columns
- [ ] Scale test: production-sized DB, PERCENTAGE sampling on large tables, concurrent API calls
- [ ] Performance improvements:
  - `ORDER BY RANDOM()` does full table scan -- consider `TABLESAMPLE` for PG
  - No parallelism (sequential per column per table)
  - All sample data in memory -- large samples can OOM
  - No OkHttp connection pooling or timeout config
  - New JDBC connection per table (no reuse within same DB)

- [ ] Investigate bugs:
  - Wildcard doesn't deduplicate (table in both explicit + wildcard = profiled twice)
  - Auto-discovered password may be encrypted, not plain text
  - `listTables` param name may differ across OM versions
  - No JDBC query timeout -- slow query blocks everything
  
- [ ] Demo prep (OM Docker + sample DB + run + walkthrough)
- [ ] 3-min demo video:
  - 0:00-0:30 -- what it does, what problem it solves
  - 0:30-1:00 -- architecture + tech stack
  - 1:00-2:30 -- live run: config, execute, HTML report, OM UI
  - Video recording as "deployed" proof
  - 2:30-3:00 -- learnings + where AI was used


## Links

- [Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662)
- [Table Profiler API](https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler)
- [Profiler Metrics Docs](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics)
- [Profiler Workflow](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/profiler-workflow)
- [OM Profiler Registry (source)](https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/src/metadata/profiler/metrics/registry.py)
