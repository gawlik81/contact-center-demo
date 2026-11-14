package com.contactcenter.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal TCP forwarder used as a switch for a service on Testcontainers: while it is open it forwards to the target,
 * {@link #close()} cuts every established connection and refuses new ones (= "the service is down"); opening a new
 * forwarder on the same port is "the service is back". Lets a test break and restore Redis without touching the
 * shared container other tests use.
 */
public final class TcpForwarder implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    public TcpForwarder(int listenPort, String targetHost, int targetPort) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 50);
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Socket upstream = new Socket(targetHost, targetPort);
                    sockets.add(client);
                    sockets.add(upstream);
                    pipe(client, upstream);
                    pipe(upstream, client);
                } catch (IOException e) {
                    return;
                }
            }
        }, "test-tcp-forwarder");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** The bound local port (pass 0 to the constructor to let the OS pick one - no probe/bind race). */
    public int port() {
        return server.getLocalPort();
    }

    private static void pipe(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // connection closed
            }
        }, "test-tcp-pipe");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
