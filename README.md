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
