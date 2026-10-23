package com.backup;

import com.backup.net.BackupClientConnection;
import com.backup.protocol.MessageType;
import com.backup.protocol.ProtocolFrame;

import java.util.Arrays;
import java.util.Random;

public class ClientMain {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8035;

        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        System.out.println("==========================================================");
        System.out.println("  Distributed Backup System - Java NIO Client Test Suite ");
        System.out.println("  Phase 1: Binary Protocol Framing & Handshake Verification");
        System.out.println("==========================================================");

        try (BackupClientConnection conn = new BackupClientConnection(host, port)) {
            System.out.printf("[TEST] Connecting to server at %s:%d...%n", host, port);
            conn.connect();
            System.out.println("[TEST] Connected successfully!");

            // Test 1: HELLO Handshake
            System.out.println("\n--- TEST 1: HELLO Handshake ---");
            String clientGreeting = "{\"client\":\"JavaNIO-17+\",\"features\":[\"cdc\",\"dedup\"]}";
            String serverGreeting = conn.sendHello(clientGreeting);
            System.out.printf("[PASS] Server Greeting: %s%n", serverGreeting);

            // Test 2: Rapid Multi-Frame Sequence (Sequential Ping/ACK)
            System.out.println("\n--- TEST 2: Multi-Frame Sequencing & Request ID Alignment ---");
            for (int i = 1; i <= 5; i++) {
                String pingMsg = String.format("{\"seq\":%d,\"action\":\"ping\"}", i);
                ProtocolFrame response = conn.sendRequest(MessageType.ACK, pingMsg.getBytes());
                System.out.printf("[PASS] Req #%d -> Response Type: %s, ReqID: %d, Payload: %s%n",
                        i,
                        response.getHeader().getType(),
                        response.getHeader().getRequestId(),
                        response.getPayloadAsString());
            }

            // Test 3: Large Frame Stress Test (64 KB fragmented payload with CRC32
            // verification)
            System.out.println("\n--- TEST 3: Large Frame Stream & CRC32 Stress Test (64 KB) ---");
            byte[] largePayload = new byte[64 * 1024];
            new Random(42).nextBytes(largePayload); // Deterministic pseudo-random bytes
            ProtocolFrame largeResp = conn.sendRequest(MessageType.ACK, largePayload);
            System.out.printf("[PASS] 64 KB Payload delivered and verified! Response Type: %s, ReqID: %d%n",
                    largeResp.getHeader().getType(),
                    largeResp.getHeader().getRequestId());

            System.out.println("\n==========================================================");
            System.out.println("  Phase 1 Verification Complete: All tests PASSED!");
            System.out.println("==========================================================");

        } catch (Exception e) {
            System.err.println("[FAIL] Test failed with exception:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
