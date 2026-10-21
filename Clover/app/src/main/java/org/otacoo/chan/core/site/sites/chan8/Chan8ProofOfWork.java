package org.otacoo.chan.core.site.sites.chan8;

import android.os.Process;

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
        final byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);

        try {
            for (int i = 0; i < cores; i++) {
                final int start = i;
                exec.submit(() -> {
                    // Hashing loop: yield CPU to the UI, same result, less jank.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    try {
                        MessageDigest digest = MessageDigest.getInstance(algorithm == 512 ? "SHA-512" : "SHA-256");
                        // One buffer for the token and the counter and one for
                        // the hash, so that a candidate does not allocate.
                        byte[] input = new byte[tokenBytes.length + 20];
                        System.arraycopy(tokenBytes, 0, input, 0, tokenBytes.length);
                        byte[] hash = new byte[digest.getDigestLength()];
                        long n = start;
                        while (!cancelled && solution.get() == -1) {
                            int len = tokenBytes.length + writeNumber(input, tokenBytes.length, n);
                            digest.reset();
                            digest.update(input, 0, len);
                            int hashLength = digest.digest(hash, 0, hash.length);
                            int bits = countLeadingZeroBits(hash, hashLength);
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

    // Writes the decimal number into the buffer, the same bytes that
    // "token + number" would produce.
    private static int writeNumber(byte[] buffer, int offset, long value) {
        if (value == 0) {
            buffer[offset] = '0';
            return 1;
        }
        int digits = 0;
        for (long v = value; v > 0; v /= 10) {
            digits++;
        }
        long v = value;
        for (int i = digits - 1; i >= 0; i--) {
            buffer[offset + i] = (byte) ('0' + (v % 10));
            v /= 10;
        }
        return digits;
    }

    private static int countLeadingZeroBits(byte[] hash, int length) {
        int bits = 0;
        for (int i = 0; i < length; i++) {
            int value = hash[i] & 0xFF;
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
