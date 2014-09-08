vertica-udxloader
=================


1. Clone the repo on vertica installed box. (Does not have to be your production environment)

2. Install VoltDB (This is only for the purpose of building and not running)

3. specify env variables VERTICA and VOLTDB to point to base directories eg. /opt/vertica and /opt/voltdb

4. Run ant which will build the jar. install the library as specified in install.sql and run UDx functions.

5. See sample in test.sql
