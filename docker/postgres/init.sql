-- Runs once when the PostgreSQL container is first initialised.
-- fraud_db is already created by POSTGRES_DB env var; create the Java client DB here.

CREATE DATABASE fraud_client_db
    WITH OWNER fraud_user
    ENCODING 'UTF8'
    LC_COLLATE 'en_US.utf8'
    LC_CTYPE 'en_US.utf8';
