package org.otacoo.chan.core.site.sites.chan8;

import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;

public class Chan8ProofOfWork {
    private final String token;
    private final int difficulty;
    private final int algorithm; // 256 or 512
    private volatile boolean cancelled = false;

    public Chan8ProofOfWork(String token, int difficulty, int algorithm) {
        this.token = token;
        this.difficulty = difficulty;
        this.algorithm = algorithm;
    }

    public void cancel() {
        cancelled = true;
    }

    public Integer find() {
        if (token == null || token.isEmpty() || difficulty < 0) return null;

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService exec = Executors.newFixedThreadPool(cores);
        AtomicLong solution = new AtomicLong(-1);

        try {
            for (int i = 0; i < cores; i++) {
                final int start = i;
                exec.submit(() -> {
                    try {
                        MessageDigest digest = MessageDigest.getInstance(algorithm == 512 ? "SHA-512" : "SHA-256");
                        long n = start;
                        while (!cancelled && solution.get() == -1) {
                            digest.reset();
                            byte[] hash = digest.digest((token + n).getBytes(StandardCharsets.UTF_8));
                            int bits = countLeadingZeroBits(hash);
                            if (bits >= difficulty) {
                                solution.compareAndSet(-1, n);
                                break;
                            }
                            n += cores;
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }

            exec.shutdown();
            // wait until finished or cancelled
            while (!exec.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (cancelled) {
                    exec.shutdownNow();
                    break;
                }
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }

        long v = solution.get();
        return v >= 0 ? (int) v : null;
    }

    private static int countLeadingZeroBits(byte[] hash) {
        int bits = 0;
        for (byte b : hash) {
            int value = b & 0xFF;
            for (int bit = 7; bit >= 0; bit--) {
                if ((value & (1 << bit)) == 0) {
                    bits++;
                } else {
                    return bits;
                }
            }
        }
        return bits;
    }
}
