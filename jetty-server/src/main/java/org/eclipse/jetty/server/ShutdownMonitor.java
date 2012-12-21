//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.ShutdownThread;

/**
 * Shutdown/Stop Monitor thread.
 * <p>
 * This thread listens on the port specified by the STOP.PORT system parameter (defaults to -1 for not listening) for
 * request authenticated with the key given by the STOP.KEY system parameter (defaults to "eclipse") for admin requests.
 * <p>
 * If the stop port is set to zero, then a random port is assigned and the port number is printed to stdout.
 * <p>
 * Commands "stop" and "status" are currently supported.
 */
public class ShutdownMonitor extends Thread
{
    private static final ShutdownMonitor INSTANCE = new ShutdownMonitor();
    
    public static ShutdownMonitor getInstance()
    {
        return INSTANCE;
    }
    
    private final boolean DEBUG;
    private final int port;
    private final String key;
    private final ServerSocket serverSocket;

    private ShutdownMonitor()
    {
        Properties props = System.getProperties();

        // Use the same debug option as /jetty-start/
        this.DEBUG = props.containsKey("DEBUG");
        
        // Use values passed thru /jetty-start/
        int stopPort = Integer.parseInt(props.getProperty("STOP.PORT","-1"));
        String stopKey = props.getProperty("STOP.KEY",null);

        ServerSocket sock = null;

        try
        {
            if (stopPort < 0)
            {
                System.out.println("ShutdownMonitor not in use");
                sock = null;
                return;
            }

            setDaemon(true);
            setName("ShutdownMonitor");

            sock = new ServerSocket(stopPort,1,InetAddress.getByName("127.0.0.1"));
            if (stopPort == 0)
            {
                // server assigned port in use
                stopPort = sock.getLocalPort();
                System.out.printf("STOP.PORT=%d%n",stopPort);
            }

            if (stopKey == null)
            {
                // create random key
                stopKey = Long.toString((long)(Long.MAX_VALUE * Math.random() + this.hashCode() + System.currentTimeMillis()),36);
                System.out.printf("STOP.KEY=%s%n",stopKey);
            }

        }
        catch (Exception e)
        {
            debug(e);
            System.err.println("Error binding monitor port " + stopPort + ": " + e.toString());
        }
        finally
        {
            // establish the port and key that are in use
            this.port = stopPort;
            this.key = stopKey;

            this.serverSocket = sock;
            debug("STOP.PORT=%d", port);
            debug("STOP.KEY=%s", key);
            debug("%s", serverSocket);
        }

        this.start();
    }
    
    @Override
    public String toString()
    {
        return String.format("%s[port=%d]",this.getClass().getName(),port);
    }

    private void debug(Throwable t)
    {
        if (DEBUG)
        {
            t.printStackTrace(System.err);
        }
    }

    private void debug(String format, Object... args)
    {
        if (DEBUG)
        {
            System.err.printf("[ShutdownMonitor] " + format + "%n",args);
        }
    }

    @Override
    public void run()
    {
        while (true)
        {
            Socket socket = null;
            try
            {
                socket = serverSocket.accept();

                LineNumberReader lin = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                String key = lin.readLine();
                if (!this.key.equals(key))
                {
                    System.err.println("Ignoring command with incorrect key");
                    continue;
                }

                OutputStream out = socket.getOutputStream();

                String cmd = lin.readLine();
                debug("command=%s",cmd);
                if ("stop".equals(cmd))
                {
                    // Graceful Shutdown
                    debug("Issuing graceful shutdown..");
                    ShutdownThread.getInstance().run();

                    // Reply to client
                    debug("Informing client that we are stopped.");
                    out.write("Stopped\r\n".getBytes(StringUtil.__UTF8));
                    out.flush();
                    
                    // Shutdown Monitor
                    debug("Shutting down monitor");
                    close(socket);
                    close(serverSocket);

                    // Kill JVM
                    debug("Killing JVM");
                    System.exit(0);
                }
                else if ("status".equals(cmd))
                {
                    // Reply to client
                    out.write("OK\r\n".getBytes(StringUtil.__UTF8));
                    out.flush();
                }
            }
            catch (Exception e)
            {
                debug(e);
                System.err.println(e.toString());
            }
            finally
            {
                close(socket);
                socket = null;
            }
        }
    }

    private void close(Socket socket)
    {
        if (socket == null)
        {
            return;
        }

        try
        {
            socket.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }
    
    private void close(ServerSocket server)
    {
        if (server == null)
        {
            return;
        }

        try
        {
            server.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }
}