-- psql -X --set=schema=public --file=scripts/inspect-db-history.sql
-- Apply no migrations here: the shared V20 history must be inspected first.
\set ON_ERROR_STOP on
\pset pager off
\if :{?schema}
\else
\set schema public
\endif

BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '2s';

SELECT current_database() AS database_name,
       :'schema' AS inspected_schema,
       current_setting('server_version') AS server_version,
       current_setting('transaction_read_only') AS transaction_read_only;

SELECT nspname AS existing_schema
FROM pg_catalog.pg_namespace
WHERE nspname = :'schema';

SELECT pg_catalog.to_regclass(pg_catalog.format('%I.flyway_schema_history', :'schema'))
           IS NOT NULL AS flyway_history_found;

SELECT pg_catalog.format(
    'SELECT installed_rank, version, description, type, script, checksum, installed_on, success FROM %I.flyway_schema_history ORDER BY installed_rank',
    :'schema')
WHERE pg_catalog.to_regclass(pg_catalog.format('%I.flyway_schema_history', :'schema')) IS NOT NULL
\gexec

SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = :'schema'
  AND table_name IN ('reservation', 'payment', 'payment_refund', 'reservation_refund_request')
ORDER BY table_name, ordinal_position;

SELECT t.relname AS table_name, c.conname AS constraint_name,
       pg_catalog.pg_get_constraintdef(c.oid) AS definition
FROM pg_catalog.pg_constraint c
JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = :'schema'
  AND t.relname IN ('reservation', 'payment', 'payment_refund', 'reservation_refund_request')
ORDER BY t.relname, c.conname;

SELECT tablename, indexname, indexdef
FROM pg_catalog.pg_indexes
WHERE schemaname = :'schema'
  AND tablename IN ('reservation', 'payment', 'payment_refund', 'reservation_refund_request')
ORDER BY tablename, indexname;

-- Only existence is needed to distinguish an empty database from a populated one.
SELECT pg_catalog.format('SELECT %L AS table_name, EXISTS (SELECT 1 FROM %I.%I) AS has_rows',
                         t.relname, n.nspname, t.relname)
FROM pg_catalog.pg_class t
JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = :'schema' AND t.relkind IN ('r', 'p')
  AND t.relname IN ('reservation', 'payment', 'payment_refund', 'reservation_refund_request')
ORDER BY t.relname
\gexec

SELECT extname, extversion
FROM pg_catalog.pg_extension
WHERE extname IN ('btree_gist', 'pg_trgm')
ORDER BY extname;

COMMIT;
