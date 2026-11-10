package io.floci.aws.services.rds.container;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresReadinessProbeTest {

    /** Answers one startup packet with the given reply bytes. */
    private static int serve(byte[] reply) throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (server; Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int length = in.readInt();
                in.readFully(new byte[length - 4]);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.write(reply);
                out.flush();
            } catch (Exception ignored) {
                // the probe side asserts
            }
        });
        return server.getLocalPort();
    }

    private static byte[] error(String message) {
        byte[] text = ("SFATAL\0M" + message + "\0\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(5 + text.length);
        buffer.put((byte) 'E').putInt(4 + text.length).put(text);
        return buffer.array();
    }

    @Test
    void anAuthenticationRequestMeansTheServerIsUp() throws Exception {
        byte[] authRequest = {'R', 0, 0, 0, 8, 0, 0, 0, 10};
        assertTrue(RdsContainerManager.postgresAcceptsConnections("127.0.0.1", serve(authRequest), "master"));
    }

    @Test
    void aServerStillRecoveringIsNotReady() throws Exception {
        int port = serve(error("the database system is starting up"));
        assertFalse(RdsContainerManager.postgresAcceptsConnections("127.0.0.1", port, "master"));
    }

    @Test
    void anyOtherErrorMeansTheServerIsUp() throws Exception {
        int port = serve(error("password authentication failed for user master"));
        assertTrue(RdsContainerManager.postgresAcceptsConnections("127.0.0.1", port, "master"));
    }
}
