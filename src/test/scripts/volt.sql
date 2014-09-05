-- Create table with all data types that can be mapped from vertica.
CREATE TABLE T (
    c1 INT
    , c2 VARCHAR(16)
    , c3 VARCHAR(16)
    , c4 VARCHAR(16)
    , c5 TIMESTAMP
    , c6 TIMESTAMP
    , c7 TIMESTAMP
    -- , c8 TIMESTAMP -- TIME in VERTICA SDK says unknown type.
    , c9 TIMESTAMP
    , c10 INT -- INTERVAL
    , c11 DECIMAL -- DOUBLE PRECISION
    , c12 FLOAT -- FLOAT
    , c13 FLOAT -- FLOAT(4)
    , c14 FLOAT -- FLOAT8
    , c15 DECIMAL -- REAL
    , c16 INTEGER
    , c17 INT NOT NULL
    , c18 BIGINT
    , c19 INT -- INT8
    , c20 SMALLINT
    , c21 TINYINT
    , c22 FLOAT
    , c23 FLOAT -- NUMERIC
    , c24 FLOAT -- NUMBER
    , c25 FLOAT -- MONEY
);
PARTITION TABLE T On COLUMN c17;
CREATE TABLE T2 (
   d DECIMAL
);
