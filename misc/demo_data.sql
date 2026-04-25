-- Demo data for hackathon presentation
-- Load: docker exec -i openmetadata_postgresql psql -U postgres -d openmetadata_db < misc/demo_data.sql

DROP TABLE IF EXISTS demo_sales_clean;
DROP TABLE IF EXISTS demo_sales_dirty;

-- ============================================================
-- CLEAN TABLE: well-distributed, healthy data (500 rows)
-- ============================================================
CREATE TABLE demo_sales_clean (
    transaction_id  SERIAL PRIMARY KEY,
    product_category VARCHAR(50),
    amount          NUMERIC(12,2),
    customer_region VARCHAR(50),
    transaction_date TIMESTAMP
);

INSERT INTO demo_sales_clean (product_category, amount, customer_region, transaction_date)
SELECT
    -- 5 categories, ~100 each (high entropy)
    CASE (i % 5)
        WHEN 0 THEN 'Electronics'
        WHEN 1 THEN 'Books'
        WHEN 2 THEN 'Clothing'
        WHEN 3 THEN 'Groceries'
        WHEN 4 THEN 'Sports'
    END,
    -- bell-shaped distribution centered around $100, range ~$15-$200 (low skew, low kurtosis)
    ROUND((100 + 40 * SIN(i * 0.73) + 30 * COS(i * 1.17) + 15 * SIN(i * 2.31))::numeric, 2),
    -- 4 regions, ~125 each (high entropy)
    CASE (i % 4)
        WHEN 0 THEN 'North'
        WHEN 1 THEN 'South'
        WHEN 2 THEN 'East'
        WHEN 3 THEN 'West'
    END,
    -- last 7 days, spread evenly (low value age)
    NOW() - ((i % 168) || ' hours')::interval
FROM generate_series(1, 500) AS s(i);

-- ============================================================
-- DIRTY TABLE: problematic data that metrics will flag (500 rows)
-- ============================================================
CREATE TABLE demo_sales_dirty (
    transaction_id  SERIAL PRIMARY KEY,
    product_category VARCHAR(50),
    amount          NUMERIC(12,2),
    customer_region VARCHAR(50),
    transaction_date TIMESTAMP
);

INSERT INTO demo_sales_dirty (product_category, amount, customer_region, transaction_date)
SELECT
    -- 95% "Electronics", 5% scattered (very low entropy, high relative entropy)
    CASE
        WHEN i <= 475 THEN 'Electronics'
        WHEN i <= 480 THEN 'Books'
        WHEN i <= 485 THEN 'Clothing'
        WHEN i <= 490 THEN 'Groceries'
        WHEN i <= 495 THEN 'Sports'
        ELSE 'Home'
    END,
    -- 95% in $10-$50 range, 5% extreme outliers at $500K (high kurtosis, high skewness)
    CASE
        WHEN i <= 475 THEN ROUND((25 + 15 * SIN(i * 0.5))::numeric, 2)
        ELSE ROUND((500000 + 10000 * SIN(i * 1.3))::numeric, 2)
    END,
    -- constant region (entropy = 0, dead column)
    'North',
    -- all timestamps 3+ months ago (high value age)
    NOW() - INTERVAL '100 days' - ((i % 30) || ' days')::interval
FROM generate_series(1, 500) AS s(i);

-- Verify
SELECT 'demo_sales_clean' AS tbl, count(*) AS rows FROM demo_sales_clean
UNION ALL
SELECT 'demo_sales_dirty', count(*) FROM demo_sales_dirty;
