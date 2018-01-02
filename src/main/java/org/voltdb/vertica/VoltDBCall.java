/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
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
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.concurrent.CountDownLatch;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;
import org.voltdb.types.TimestampType;

/**
 *
 */
public class VoltDBCall extends ScalarFunctionFactory {

    private Client m_client;
    private String m_procedure = "";
    private String m_server = "localhost";

    public class VoltCall extends ScalarFunction {

        @Override
        public void processBlock(ServerInterface si, BlockReader reader, BlockWriter writer) throws UdfException, DestroyInvocation {
            long cnt = 0;
            final String ODBC_DATE_FORMAT_STRING = "yyyy-MM-dd HH:mm:ss.SSS";

            final SimpleDateFormat dfmt
                    = new SimpleDateFormat(ODBC_DATE_FORMAT_STRING);

            do {
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
                    si.log("Current: %d, Value: %s, Type: %s", i, vals[i], vals[i] == null ? "null" : vals[i].getClass().getCanonicalName());
                }
                try {
                    //Call procedure synchronously
                    writer.setLong((m_client.callProcedure(m_procedure, vals)).getStatus() == ClientResponse.SUCCESS ? 0 : 1);
                } catch (IOException ex) {
                    writer.setLong(1);
                    si.log("Failed to call procedure %s, Error: %s", m_procedure, ex);
                } catch (ProcCallException ex) {
                    writer.setLong(1);
                    si.log("Failed to call procedure %s, Error: %s", m_procedure, ex);
                }
            } while (reader.next());

            try {
                m_client.drain();
            } catch (Exception ex) {
                si.log("Failed to flush voltdb bulkloader", ex);
            }
            //Report
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
                m_procedure = argReader.getString("procedure");
            } catch (UdfException udfex) {
                ;
            }

            si.log("Server: %s, Procedure: %s", m_server, m_procedure);
            connect(m_server);
            if (m_client == null) {
                throw new UdfException(0, "Failed to connect to server: " + m_server);
            }
        } catch (Exception ex) {
            si.log("Failed to load data in voltdb: %s", ex.toString());
            throw new UdfException(0, "Failed to load data in voltdb: " + ex.toString());
        }

        return new VoltCall();
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
        parameterTypes.addVarchar(1024, "voltservers");
        parameterTypes.addVarchar(1024, "procedure");
    }

}
