package org.otacoo.chan.core.net.pow;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-SHA512 proof-of-work used by Lynxchan sites (including 8chan) that have
 * the hashcash addon enabled. After solving the captcha via renewBypass.js, the
 * server issues a bypass cookie embedding a PBKDF2-SHA512 challenge. This solver
 * finds the iteration that reproduces the target hash, which is then submitted
 * to validateBypass.js to activate the bypass.
 */
public class LynxchanProofOfWork {
    private final String bypass;
    private volatile boolean cancelled = false;

    public LynxchanProofOfWork(String bypass) {
        this.bypass = bypass;
    }

    public void cancel() {
        cancelled = true;
    }

    public Integer find() {
        if (bypass == null || bypass.length() < 24) return null;

        try {
            String session = bypass.substring(24, Math.min(bypass.length(), 24 + 344));
            String hashPart = bypass.length() > 24 + 344 ? bypass.substring(24 + 344) : null;
            if (hashPart == null) return null;
            byte[] targetHash = Base64.getDecoder().decode(hashPart.trim());

            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            ExecutorService exec = Executors.newFixedThreadPool(cores);
            AtomicInteger solution = new AtomicInteger(-1);

            for (int i = 0; i < cores; i++) {
                final int index = i;
                exec.submit(() -> {
                    try {
                        SecretKeyFactory secretFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
                        final int length = targetHash.length * 8;
                        final int iter = 16384;
                        char[] sessionArray = session.toCharArray();
                        long iteration = index;
                        while (!cancelled && solution.get() == -1) {
                            PBEKeySpec spec = new PBEKeySpec(sessionArray, Long.toString(iteration).getBytes(), iter, length);
                            byte[] attempt = secretFactory.generateSecret(spec).getEncoded();
                            if (attempt.length == targetHash.length) {
                                boolean eq = true;
                                for (int j = 0; j < attempt.length; j++) {
                                    if (attempt[j] != targetHash[j]) { eq = false; break; }
                                }
                                if (eq) {
                                    solution.compareAndSet(-1, (int) iteration);
                                    break;
                                }
                            }
                            iteration += cores;
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }

            exec.shutdown();
            while (!exec.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (cancelled) break;
            }

            int v = solution.get();
            return v >= 0 ? v : null;
        } catch (Exception e) {
            org.otacoo.chan.utils.Logger.e("LynxchanProofOfWork", "Failed", e);
            return null;
        }
    }
}
