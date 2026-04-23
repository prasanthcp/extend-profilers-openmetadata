# Extend OpenMetadata Profiler with Advanced Statistical Metrics

Extends OpenMetadata's profiler with advanced statistical metrics (entropy, kurtosis, skewness, seasonality, value-age) plus basic column stats (nulls, uniques, min, max, mean). Pushes everything as custom metrics into the platform and generates local HTML/JSON/CSV reports.

Built for [OpenMetadata Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662).

## Features

- **6 advanced metrics**: entropy, relative entropy, kurtosis, skewness, seasonality, value-age
- **Basic column stats**: nullCount, uniqueCount, min, max, mean -- computed independently, no dependency on OM's profiler
- **Multi-dialect JDBC**: PostgreSQL, MySQL, MariaDB, MSSQL, Redshift, Snowflake
- **Auto-discovery**: provide just a table FQN -- JDBC credentials are fetched from OM's database service API automatically
- **Schema wildcards**: `"fqn": "service.db.schema.*"` profiles all tables in a schema
- **3-tier data access**: explicit JDBC > auto-discovered JDBC > OM sample data fallback
- **HTML report**: collapsible table cards, color-coded metrics, health scores, "View in OpenMetadata" links
- **OM integration**: pushes profiles via API so results appear in OM's Profiler tab with timeseries graphs

## Quick Start

```bash
# Prerequisites: JDK 21+, running OpenMetadata instance

./gradlew fatJar
./gradlew run --args='sample-config.json'

# Or via fat JAR:
java -jar build/libs/extend-profilers-openmetadata-0.1.0-SNAPSHOT-all.jar my-config.json
```

## Config

Minimal (auto-discovers JDBC from OM):
```json
{
  "omUrl": "http://localhost:8585",
  "omEmail": "admin@open-metadata.org",
  "omPassword": "YWRtaW4=",
  "tables": [
    { "fqn": "local_postgres.openmetadata_db.public.table_entity" },
    { "fqn": "local_postgres.openmetadata_db.public.*" }
  ]
}
```

Explicit JDBC (when auto-discovery isn't available):
```json
{
  "fqn": "local_postgres.openmetadata_db.public.table_entity",
  "jdbcUrl": "jdbc:postgresql://localhost:5432/openmetadata_db",
  "dbUser": "postgres",
  "dbPassword": "password",
  "tableName": "table_entity",
  "sampleLimit": 500,
  "sampleType": "ROWS"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `omUrl` | Yes | OpenMetadata server URL |
| `omEmail` | Yes | Login email |
| `omPassword` | Yes | Base64-encoded password |
| `outputDir` | No | Export directory (default: `output/`) |
| `tables[].fqn` | Yes | Fully qualified table name, or `schema.*` for wildcard |
| `tables[].jdbcUrl` | No | Explicit JDBC URL. If omitted, auto-discovers from OM. |
| `tables[].sampleLimit` | No | Row count (default 500) or percentage |
| `tables[].sampleType` | No | `ROWS` (default) or `PERCENTAGE` |

## Output

- **OM UI**: Profiler tab shows custom metrics + timeseries graphs
- **HTML**: `output/LatestReport.html` -- collapsible tables, health scores, color-coded metrics
- **JSON/CSV**: per-table `latest.json`/`latest.csv` + timestamped history

## Tests

```bash
./gradlew test
```

## Logging

Log level is configured in `src/main/resources/logback.xml`. Default: INFO. Change to DEBUG for verbose output, WARN for minimal.

## Where AI Was Used

- Gradle/Java setup, dependency configuration
- Test file generation
- Understanding OpenMetadata architecture and API flows

## Links

- [Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662)
- [OM Profiler Metrics Docs](https://docs.open-metadata.org/v1.12.x/how-to-guides/data-quality-observability/profiler/metrics)
- [OM Table Profiler API](https://docs.open-metadata.org/v1.11.x/api-reference/data-assets/tables/profiler#table-profiler)
