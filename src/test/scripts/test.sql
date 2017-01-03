/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

/*
 *
 * VoltDB functions for vertica
 *
 * Current functions:
 * voltdbload() - allows you to load data into VoltDB using SQL
 *
 */


SELECT SET_CONFIG_PARAMETER('JavaBinaryForUDx','/usr/bin/java');

-- Step 1: Create LIBRARY
\set libSfile '\''`pwd`'/../../../voltdb-udxload.jar\''

CREATE LIBRARY VoltDBFunctions AS :libSfile LANGUAGE 'JAVA';
-- Step 2: Create Functions
CREATE FUNCTION voltdbload AS LANGUAGE 'Java' NAME 'org.voltdb.vertica.VoltDBLoader' LIBRARY VoltDBFunctions ;

-- Create table with all types except binary
CREATE TABLE T (
    c1 BOOLEAN
    , c2 CHAR(16)
    , c3 VARCHAR(16)
    , c4 LONG VARCHAR(16)
    , c5 DATE
    , c6 DATETIME
    , c7 SMALLDATETIME
    -- , c8 TIME Vertica SDK gives a Unknown type for this.
    , c9 TIMESTAMP
    , c10 INTERVAL
    , c11 DOUBLE PRECISION
    , c12 FLOAT
    , c13 FLOAT(4)
    , c14 FLOAT8
    , c15 REAL
    , c16 INTEGER
    , c17 INT
    , c18 BIGINT
    , c19 INT8
    , c20 SMALLINT
    , c21 TINYINT
    , c22 DECIMAL       -- DECIMAL TYPES NEED to be ::float
    , c23 NUMERIC(4,2)       -- DECIMAL TYPES NEED to be ::float
    , c24 NUMBER       -- DECIMAL TYPES NEED to be ::float
    , c25 MONEY       -- DECIMAL TYPES NEED to be ::float
);
COPY T FROM STDIN DELIMITER ',';
true,VOLT,VOLTDB,VOLTDBINC,10/10/2014,Wed Sep 3 05:45:59 EDT 2014,Wed Sep 3 05:45:59 EDT 2014,1999-12-12,10,-9999.9999,1.1,4.1234,8.12345678,-8.12345678,0,1,3,4,5,6,-9.12345678,-8.12345678,-7.12345678,100.30
\.

SELECT * from T;

-- Invoke using table option
SELECT c1, c2, c3, voltdbload(c1, c2, c3, c4, c5, c6, c7, c9, DAY(c10), c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21, c22::float, c23::float, c24::float,
                      c25::float using parameters maxerrors=200, voltservers='localhost', volttable='T') FROM T;
-- Invoke using procedure option
SELECT c1, c2, c3, voltdbload(c1, c2, c3, c4, c5, c6, c7, c9, DAY(c10), c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21, c22::float, c23::float, c24::float,
                      c25::float using parameters maxerrors=200, voltservers='localhost', procedure='T.insert') FROM T;

--
DROP TABLE T;
-- select * from user_functions;
DROP LIBRARY VoltDBFunctions CASCADE;
