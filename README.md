# extend-profilers-openmetadata
Extend the profiler with advanced statistical metrics (entropy, kurtosis, skewness, seasonality, value-age) and make them exportable for downstream consumers

# Objectives: 
- Implementations of new metrics as Metric classes (entropy, kurtosis, value-age, time-series seasonality detectors)
- exporters to emit metrics to JSON/CSV and OpenMetadata profile payloads

# Starter tasks
- implement entropy and relative-entropy (per-column) and add tests against known distributions
- add kurtosis and non-parametric skew metrics with numerical stability checks
- value-age metric: compute median age of values (timestamp-bearing columns)
- seasonal/detection metric for time-series columns (autocorrelation or seasonal decomposition)
- integrate metrics into MetricRegistry and ensure Profiler picks them up via config

# Hackathon Idea 
https://github.com/open-metadata/OpenMetadata/issues/26662


# Architecture:
Ingest: connects to a database (e.g., PostgreSQL/Snowflake) using JDBC.
Compute: Java library like Apache Commons Math to calculate the "Advanced Metrics" (Entropy, Kurtosis, etc.)
Export: Provide a REST endpoint or UI button to download these results as CSV/JSON.
Sync: Use the OpenMetadata Java client to push these values back to the platform.
