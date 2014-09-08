/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
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
package org.voltdb.vertica;

import com.vertica.sdk.BlockReader;
import com.vertica.sdk.BlockWriter;
import com.vertica.sdk.ColumnTypes;
import com.vertica.sdk.DestroyInvocation;
import com.vertica.sdk.ParamReader;
import com.vertica.sdk.ScalarFunction;
import com.vertica.sdk.ScalarFunctionFactory;
import com.vertica.sdk.ServerInterface;
import com.vertica.sdk.SizedColumnTypes;
import com.vertica.sdk.UdfException;
import com.vertica.sdk.VerticaType;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientImpl;
import org.voltdb.client.ClientResponse;
import org.voltdb.types.TimestampType;
import org.voltdb.utils.BulkLoaderErrorHandler;
import org.voltdb.utils.CSVBulkDataLoader;
import org.voltdb.utils.CSVDataLoader;
import org.voltdb.utils.CSVTupleDataLoader;
import org.voltdb.utils.RowWithMetaData;

/**
 *
 */
public class VoltDBLoader extends ScalarFunctionFactory {

    private Client m_client;
    private String m_table;
    private String m_procedure = "";
    private CSVDataLoader m_loader;
    private int m_batch = 200;

    private String m_server = "localhost";

    public static class VerticaBulkLoaderErrorHandler implements BulkLoaderErrorHandler {
        private final ServerInterface m_si;
        private final static AtomicLong m_failedCount = new AtomicLong(0);
        public static long m_maxerrors = 100;
        public static boolean m_stop = false;

        public VerticaBulkLoaderErrorHandler(ServerInterface si) {
            m_si = si;
        }

        @Override
        public boolean handleError(RowWithMetaData metaData, ClientResponse response, String error) {
            if (response != null) {
                byte status = response.getStatus();
                if (status != ClientResponse.SUCCESS) {
                    m_si.log("Failed to Insert Row: %s, Response: %s, Error: %s", metaData.rawLine, response.getStatus(), response.getStatusString());
                    long fc = m_failedCount.incrementAndGet();
                    if ((m_maxerrors > 0 && fc > m_maxerrors)
                            || (status != ClientResponse.USER_ABORT && status != ClientResponse.GRACEFUL_FAILURE)) {
                        m_stop = true;
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean hasReachedErrorLimit() {
            long fc = m_failedCount.get();
            m_stop = m_maxerrors > 0 && fc > m_maxerrors;
            return m_stop;
        }

    }

    public class VoltLoader extends ScalarFunction {

        private CSVDataLoader m_loader;

        public VoltLoader(CSVDataLoader loader) {
            m_loader = loader;
        }

        @Override
        public void processBlock(ServerInterface si, BlockReader reader, BlockWriter writer) throws UdfException, DestroyInvocation {
            long cnt = 0;
            final String ODBC_DATE_FORMAT_STRING = "yyyy-MM-dd HH:mm:ss.SSS";

            final SimpleDateFormat dfmt
                    = new SimpleDateFormat(ODBC_DATE_FORMAT_STRING);

            do {
                if (VerticaBulkLoaderErrorHandler.m_stop) {
                    si.log("Reached max error limit for voltload: limit(%d)", VerticaBulkLoaderErrorHandler.m_maxerrors);
                    break;
                }
                //Read values and pass them to bulkloader.
                Object vals[] = new Object[reader.getNumCols()];
                SizedColumnTypes typemd = reader.getTypeMetaData();
                //What to do with LongVarchar and LongChar
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < reader.getNumCols(); i++) {
                    VerticaType vt = typemd.getColumnType(i);
                    if (vt.isInt()) {
                        vals[i] = reader.getLong(i);
                        sb.append(vals[i]).append(",");
                    } else if (vt.isStringType() || vt.isLongVarchar() || vt.isChar()) {
                        vals[i] = reader.getString(i);
                        sb.append(vals[i]).append(",");
                    } else if (vt.isFloat() || vt.isNumeric()) {
                        vals[i] = reader.getDouble(i);
                        sb.append(vals[i]).append(",");
                    } else if (vt.isTimestamp()) {
                        vals[i] = reader.getTimestamp(i);
                        if (vals[i] != null && vals[i] instanceof java.sql.Timestamp) {
                            java.sql.Timestamp ts = (java.sql.Timestamp) vals[i];
                            String tss = ts.toString();
                            vals[i] = tss;
                        }
                        sb.append(vals[i]).append(",");
                    } else if (vt.isDate()) {
                        Timestamp dt = reader.getTimestamp(i);
                        TimestampType ts = null;
                        if (!reader.isDateNull(i)) {
                            ts = new TimestampType(dt.getTime());
                        }
                        vals[i] = ts;
                        sb.append(vals[i]).append(",");
                    } else if (vt.isBool()) {
                        vals[i] = reader.getBoolean(i) == true ? 1 : 0;
                        sb.append(vals[i]).append(",");
                    } else {
                        si.log("Unknown data type please convert for loading or unsupported data type for voltDB loader: Index=%d", i);
                    }
                    //si.log("Current: %d, Value: %s, Type: %s", i, vals[i], vals[i] == null ? "null" : vals[i].getClass().getCanonicalName());
                }
                try {
                    m_loader.insertRow(new RowWithMetaData(sb.toString(), cnt++), vals);
                    writer.setLong(0);
                } catch (InterruptedException ex) {
                    si.log("Bulkloader interrupted: %s", ex);
                    writer.setLong(1);
                    break;
                }
            } while (reader.next());

            try {
                if (m_client != null) {
                    m_client.drain();
                }
                //expose flush from loader in CSVLoader to call here.
            } catch (Exception ex) {
                si.log("Failed to flush voltdb bulkloader: %s", ex);
            }
            //Report
            si.log("voltload failed to load %d rows, see UDx logs for row details.", VerticaBulkLoaderErrorHandler.m_failedCount.intValue());
        }

    }

    @Override
    public ScalarFunction createScalarFunction(ServerInterface si) {
        try {
            ParamReader argReader = si.getParamReader();

            int numCols = argReader.getNumCols();
            si.log("No of params: %d", numCols);
            if (numCols < 2) {
                throw new UdfException(0,
                    "Must supply at least 2 arguments");
            }

            m_server = argReader.getString("voltservers");
            try {
                m_table = argReader.getString("volttable");
            } catch (UdfException udfex) {
                ;
            }
            try {
                m_procedure = argReader.getString("procedure");
            } catch (UdfException udfex) {
                ;
            }
            try {
                VerticaBulkLoaderErrorHandler.m_maxerrors = argReader.getLong("maxerrors");
            } catch (UdfException udfex) {
                VerticaBulkLoaderErrorHandler.m_maxerrors = 100;
            }

            si.log("Server: %s, Max errors: %d", m_server, VerticaBulkLoaderErrorHandler.m_maxerrors);
            connect(m_server);
            if (m_client != null) {
                if (m_procedure != null && !m_procedure.trim().isEmpty()) {
                    si.log("Procedure: %s", m_procedure);
                    m_loader = new CSVTupleDataLoader((ClientImpl) m_client, m_procedure, new VerticaBulkLoaderErrorHandler(si));
                } else {
                    si.log("Table: %s", m_table);
                    m_loader = new CSVBulkDataLoader((ClientImpl) m_client, m_table, m_batch, new VerticaBulkLoaderErrorHandler(si));
                }
            }
        } catch (Exception ex) {
            si.log("Failed to load data in voltdb: %s", ex.toString());
            throw new UdfException(0, "Failed to load data in voltdb: " + ex.toString());
        }

        return new VoltLoader(m_loader);
    }

    /**
     * Connect to a single server with retry. Limited exponential backoff. No
     * timeout. This will run until the process is killed if it's not able to
     * connect.
     *
     * @param server hostname:port or just hostname (hostname can be ip).
     */
    void connectToOneServerWithRetry(String server) {
        int sleep = 1000;
        while (true) {
            try {
                m_client.createConnection(server);
                break;
            } catch (Exception e) {
                System.err.printf("Connection failed - retrying in %d second(s).\n", sleep / 1000);
                try {
                    Thread.sleep(sleep);
                } catch (Exception interruted) {
                }
                if (sleep < 8000) {
                    sleep += sleep;
                }
            }
        }
        System.out.printf("Connected to VoltDB node at: %s.\n", server);
    }

    /**
     * Connect to a set of servers in parallel. Each will retry until
     * connection. This call will block until all have connected.
     *
     * @param servers A comma separated list of servers using the hostname:port
     * syntax (where :port is optional).
     * @throws InterruptedException if anything bad happens with the threads.
     */
    void connect(String servers) throws InterruptedException {
        System.out.println("Connecting to VoltDB...");

        String[] serverArray = servers.split(",");
        final CountDownLatch connections = new CountDownLatch(serverArray.length);

        ClientConfig clientConfig = new ClientConfig("", "");

        m_client = ClientFactory.createClient(clientConfig);

        // use a new thread to connect to each server
        for (final String server : serverArray) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    connectToOneServerWithRetry(server);
                    connections.countDown();
                }
            }).start();
        }
        // block until all have connected
        connections.await();
    }

    @Override
    public void getPrototype(ServerInterface si, ColumnTypes argTypes, ColumnTypes returnType) {
        //Arguments "servers"
        argTypes.addAny();
        returnType.addInt();
    }


    @Override
    public void getParameterType(ServerInterface si,
                                 SizedColumnTypes parameterTypes)
    {
        //si.log("Param types: %d", parameterTypes.getColumnCount());
        parameterTypes.addVarchar(1024, "voltservers");
        parameterTypes.addVarchar(512, "volttable");
        parameterTypes.addVarchar(1024, "procedure");
        parameterTypes.addInt("maxerrors");
        //si.log("Param types: %d", parameterTypes.getColumnCount());
    }

}
