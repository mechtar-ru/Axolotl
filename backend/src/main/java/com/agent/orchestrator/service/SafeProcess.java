package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Helper to safely run processes: always destroys on timeout, drains stdout/stderr to prevent pipe deadlocks.
 */
public class SafeProcess {

    private static final Logger log = LoggerFactory.getLogger(SafeProcess.class);

    /**
     * Run a process with a timeout. Returns stdout output if successful, null on timeout/failure.
     * Always destroys the process on timeout. Drains both stdout and stderr.
     */
    public static String run(ProcessBuilder pb, long timeout, TimeUnit unit) {
        return run(pb, timeout, unit, null);
    }

    /**
     * Run a process with a timeout and optional stdin input.
     */
    public static String run(ProcessBuilder pb, long timeout, TimeUnit unit, String stdin) {
        pb.redirectErrorStream(true); // merge stderr into stdout
        Process process = null;
        try {
            process = pb.start();
            final Process p = process; // effectively final reference for inner classes

            // Write stdin if provided
            if (stdin != null && !stdin.isEmpty()) {
                try (OutputStreamWriter writer = new OutputStreamWriter(p.getOutputStream())) {
                    writer.write(stdin);
                }
            }

            // Drain stdout in a background thread to prevent pipe deadlock
            StringBuilder output = new StringBuilder();
            Thread drainer = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException e) {
                    // stream closed when process is destroyed — expected
                }
            });
            drainer.setDaemon(true);
            drainer.start();

            boolean finished = p.waitFor(timeout, unit);
            if (!finished) {
                log.warn("Process timed out after {} {}: {}", timeout, unit, pb.command());
                p.destroyForcibly();
                drainer.join(2000); // give drainer time to finish
                return null;
            }

            drainer.join(2000);
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                log.debug("Process exited with code {}: {}", exitCode, pb.command());
            }
            return output.toString().trim();

        } catch (IOException e) {
            log.error("Failed to start process: {}", pb.command(), e);
            if (process != null && process.isAlive()) process.destroyForcibly();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Process interrupted: {}", pb.command());
            if (process != null && process.isAlive()) process.destroyForcibly();
            return null;
        } finally {
            // Ensure any remaining streams are closed
            if (process != null) {
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                try { process.getErrorStream().close(); } catch (Exception ignored) {}
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
            }
        }
    }
}
