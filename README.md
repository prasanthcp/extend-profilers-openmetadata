# Extend OpenMetadata Profiler

Adds advanced statistical metrics to OpenMetadata's profiler: entropy, relative entropy, kurtosis, skewness, seasonality, and value-age. Connects directly to your database via JDBC, computes metrics per column, pushes results as custom metrics into OM, and generates a standalone HTML dashboard.

Built for [Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662).

## What It Does

Standard OM profiling gives you row counts, null ratios, and distinct counts. This tool adds metrics that catch subtler problems:

- **Entropy / Relative Entropy** — measures value diversity. Catches constant columns, near-constant columns, and skewed categorical distributions.
- **Kurtosis / Skewness** — distribution shape for numeric columns. Flags heavy tails, outliers, and asymmetric data.
- **Value Age** — hours since the most recent timestamp. Catches stale data that should be fresh.
- **Seasonality** — detects repeating patterns in OM profile history (needs 4+ prior profiler runs).

## Features

- **Multi-dialect JDBC**: PostgreSQL, MySQL, MariaDB, MSSQL, Redshift, Snowflake
- **Auto-discovery**: provide a table FQN and DB credentials, JDBC URL resolved from OM's service config
- **Schema wildcards**: `"fqn": "service.db.schema.*"` profiles every table in a schema
- **HTML dashboard**: collapsible tables, color-coded metrics, health scores, OM deep links
- **OM integration**: registers custom metric definitions and pushes profiles to OM's timeseries API

## Prerequisites

- **JDK 21+**
- **Running OpenMetadata instance** with API access and at least one database service ingested

Don't have OM running? Start it with Docker:
```bash
git clone https://github.com/open-metadata/OpenMetadata.git
cd OpenMetadata/docker/development
docker compose up -d
```
OM will be at `http://localhost:8585` (login: `admin@open-metadata.org` / `admin`).

## Quick Start

```bash
./gradlew fatJar
java -jar build/libs/extend-profilers-openmetadata-0.1.0-SNAPSHOT-all.jar sample-config.json
```

Or run directly via Gradle:
```bash
./gradlew run --args='sample-config.json'
```

Run tests:
```bash
./gradlew test
```

## Config

`dbUser` and `dbPassword` are required at the top level — the tool uses them for all JDBC connections.

Minimal config (JDBC URL auto-discovered from OM):
```json
{
  "omUrl": "http://localhost:8585",
  "omEmail": "admin@open-metadata.org",
  "omPassword": "YWRtaW4=",
  "dbUser": "postgres",
  "dbPassword": "password",
  "tables": [
    { "fqn": "local_postgres.openmetadata_db.public.*" }
  ]
}
```

Explicit JDBC (overrides auto-discovery):
```json
{
  "omUrl": "http://localhost:8585",
  "omEmail": "admin@open-metadata.org",
  "omPassword": "YWRtaW4=",
  "dbUser": "postgres",
  "dbPassword": "password",
  "tables": [
    {
      "fqn": "local_postgres.openmetadata_db.public.table_entity",
      "jdbcUrl": "jdbc:postgresql://localhost:5432/openmetadata_db",
      "tableName": "table_entity"
    }
  ]
}
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `omUrl` | Yes | | OpenMetadata server URL |
| `omEmail` | Yes | | Login email |
| `omPassword` | Yes | | Base64-encoded password |
| `dbUser` | Yes | | Database username (used for all JDBC connections) |
| `dbPassword` | Yes | | Database password |
| `outputDir` | No | `output/` | Export directory |
| `tables[].fqn` | Yes | | Table FQN, or `schema.*` for wildcard |
| `tables[].jdbcUrl` | No | auto-discover | JDBC URL (resolved from OM if not provided) |
| `tables[].tableName` | No | from FQN | Table name for SQL queries |
| `tables[].sampleLimit` | No | 500 | Row count or percentage |
| `tables[].sampleType` | No | `ROWS` | `ROWS` or `PERCENTAGE` |

## Output

Each run produces 3 files in the output directory:

```
output/
  LatestReport.html   -- HTML dashboard with collapsible tables, health scores
  results.json        -- all tables and metrics as JSON
  results.csv         -- all tables and metrics as CSV (includes table-level metrics)
```

Results are also pushed to OM's Profiler tab as custom metrics. Repeated runs build timeseries graphs in OM.

## Demo Data

The repo includes demo data to showcase clean vs problematic tables:

```bash
# Load into your Postgres instance
docker exec -i openmetadata_postgresql psql -U postgres -d openmetadata_db < misc/demo_data.sql

# Profile with demo config
java -jar build/libs/extend-profilers-openmetadata-0.1.0-SNAPSHOT-all.jar demo-config.json
```

`demo_sales_clean` has balanced distributions across all columns. `demo_sales_dirty` has skewed categories, extreme outliers, stale timestamps, and constant columns — the kind of data quality issues these metrics catch.

## Screenshots

**Custom metrics in OpenMetadata UI** — entropy timeseries built from repeated profiler runs:

![OpenMetadata Profiler UI](misc/screenshot%201.png)

**HTML dashboard** — collapsible tables, color-coded metrics, health scores:

![HTML Report](misc/screenshot%202.png)

## Where AI Was Used

- Gradle/Java project setup and dependency configuration
- Test scaffolding and generation
- Understanding OpenMetadata API structure and profiler internals
- Demo data design and SQL generation

See [Dev Notes.md](Dev%20Notes.md) for architecture and API details.
