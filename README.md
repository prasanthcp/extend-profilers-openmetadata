# Extend OpenMetadata Profiler

Adds 6 advanced statistical metrics (entropy, kurtosis, skewness, seasonality, value-age) to OpenMetadata. Pushes results as custom metrics into OM and generates local HTML/JSON/CSV reports.

Built for [Hackathon Issue #26662](https://github.com/open-metadata/OpenMetadata/issues/26662).

## Features

- **Advanced metrics**: entropy, relative entropy, kurtosis, skewness, seasonality, value-age
- **Multi-dialect JDBC**: PostgreSQL, MySQL, MariaDB, MSSQL, Redshift, Snowflake
- **Auto-discovery**: provide just a table FQN, JDBC credentials fetched from OM automatically
- **Schema wildcards**: `"fqn": "service.db.schema.*"` profiles all tables in a schema
- **HTML report**: collapsible tables, color-coded metrics, health scores, OM links

## Prerequisites

- **JDK 21+**
- **Running OpenMetadata instance** (API access + a database service to profile)

Don't have OM running? Start it with Docker:
```bash
git clone https://github.com/open-metadata/OpenMetadata.git
cd OpenMetadata/docker/development
docker compose up -d
```
OM will be available at `http://localhost:8585` (login: `admin@open-metadata.org` / `admin`).

See [OM Docker quickstart](https://docs.open-metadata.org/quick-start/local-docker-deployment) for details.

## Quick Start

```bash
./gradlew fatJar
java -jar build/libs/extend-profilers-openmetadata-0.1.0-SNAPSHOT-all.jar sample-config.json

# Or directly:
./gradlew run --args='sample-config.json'

# Tests:
./gradlew test
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

Explicit JDBC:
```json
{
  "fqn": "local_postgres.openmetadata_db.public.table_entity",
  "jdbcUrl": "jdbc:postgresql://localhost:5432/openmetadata_db",
  "dbUser": "postgres",
  "dbPassword": "password",
  "tableName": "table_entity"
}
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `omUrl` | Yes | | OpenMetadata server URL |
| `omEmail` | Yes | | Login email |
| `omPassword` | Yes | | Base64-encoded password |
| `outputDir` | No | `output/` | Export directory |
| `tables[].fqn` | Yes | | Table FQN, or `schema.*` for wildcard |
| `tables[].jdbcUrl` | No | auto-discover | JDBC URL |
| `tables[].sampleLimit` | No | 500 | Row count or percentage |
| `tables[].sampleType` | No | `ROWS` | `ROWS` or `PERCENTAGE` |

## Output

Each run produces 3 files in the output directory:

```
output/
  LatestReport.html   -- visual report with collapsible tables, health scores
  results.json        -- all tables and metrics in one JSON file
  results.csv         -- all tables and metrics in one CSV file
```

Results are also pushed to OM's Profiler tab (custom metrics + timeseries graphs).

## Screenshots

**Custom metrics in OpenMetadata UI** — entropy timeseries built from repeated profiler runs:

![OpenMetadata Profiler UI](misc/Screenshot%201.png)

**HTML report** — collapsible tables, color-coded metrics, health scores:

![HTML Report](misc/screenshot%202.png)

## Where AI Was Used

- Gradle/Java setup, dependency configuration
- Test file generation
- Understanding OpenMetadata architecture and API flows

See [Dev Notes.md](Dev%20Notes.md) for architecture, API details, and developer reference.
