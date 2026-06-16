package com.agent.orchestrator.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages a Bun subprocess lifecycle for plugin execution.
 * <p>
 * Lifecycle: create → start → (send requests, receive responses) → stop
 * <p>
 * Communication: NDJSON (JSON-RPC 2.0) over the subprocess's stdin/stdout.
 */
public class PluginBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LINE_LENGTH = 100_000; // 100KB max per JSON line

    // ─── Configuration ───
    private final String name;
    private final String bunPath;
    private final String bridgeJsPath;
    private final int startupTimeoutMs;
    private final int requestTimeoutMs;
    private final int maxRestartAttempts;
    private final long restartBackoffMs;

    // ─── Process state ───
    private volatile Process process;
    private volatile boolean running;
    private volatile boolean started;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);

    // ─── I/O ───
    private BufferedWriter stdin;
    private Thread stdoutReader;
    /** Signals that the bridge process has started and is ready for initialization.
     *  Uses Phaser (not CountDownLatch) because Phaser is resettable on restart. */
    private volatile Phaser readyPhaser = new Phaser(1);
    private final Map<Integer, CompletableFuture<PluginMessage.ParsedMessage>> pendingRequests = new ConcurrentHashMap<>();

    // ─── Handlers for plugin-initiated messages ───
    private final List<Consumer<PluginMessage.ParsedMessage>> requestHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<PluginMessage.ParsedMessage>> notificationHandlers = new CopyOnWriteArrayList<>();
    private Consumer<String> logHandler;

    // ─── Auto-restart ───
    private ScheduledExecutorService restartScheduler;

    // ─── Plugin info collected during init ───
    private volatile String pluginId;
    private volatile String pluginVersion;
    private volatile long startTimeMs;

    // ─── Restart tracking ───
    private int restartCount;

    /** Callback invoked when the bridge disconnects unexpectedly. May trigger restart. */
    private volatile Runnable onDisconnect;

    public PluginBridge(String name, String bunPath, String bridgeJsPath,
                         int startupTimeoutMs, int requestTimeoutMs,
                         int maxRestartAttempts, long restartBackoffMs) {
        this.name = name;
        this.bunPath = bunPath;
        this.bridgeJsPath = bridgeJsPath;
        this.startupTimeoutMs = startupTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxRestartAttempts = maxRestartAttempts;
        this.restartBackoffMs = restartBackoffMs;
    }

    // ─── Lifecycle ───

    /**
     * Start the Bun subprocess. Blocks until the plugin sends "plugin/ready" or timeout.
     *
     * @param initParams parameters to pass in the initialize request
     * @throws PluginException if startup fails
     */
    public synchronized void start(Map<String, Object> initParams) throws PluginException {
        if (started) {
            log.warn("[{}] Plugin already started, restarting", name);
            stopInternal();
        }
        started = true;
        restartCount = 0;
        startInternal(initParams);
    }

    private void startInternal(Map<String, Object> initParams) throws PluginException {
        // Recreate restart scheduler if it was shut down by a previous stop/restart
        if (restartScheduler == null || restartScheduler.isShutdown()) {
            restartScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "plugin-restart-" + name);
                t.setDaemon(true);
                return t;
            });
        }

        try {
            // Fresh Phaser for this start cycle (CountDownLatch can't be reset — Phaser can)
            readyPhaser = new Phaser(1);

            // Build command: bun <bridge-js-path>
            List<String> command = new ArrayList<>();
            command.add(bunPath);
            command.add(bridgeJsPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false); // separate stderr for logging

            log.info("[{}] Starting Bun process: {}", name, String.join(" ", command));
            process = pb.start();

            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            running = true;
            startTimeMs = System.currentTimeMillis();

            // Start reader threads
            startStdoutReader();
            startStderrLogger();

            // Wait for "plugin/ready" notification (via Phaser)
            try {
                readyPhaser.awaitAdvanceInterruptibly(0, startupTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new PluginException("Plugin did not signal ready within " + startupTimeoutMs + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PluginException("Interrupted while waiting for plugin ready");
            }

            // Send initialize request
            int initId = nextId();
            String initMsg = PluginMessage.buildRequest("plugin/initialize", initParams, initId);

            if (!sendRaw(initMsg)) {
                throw new PluginException("Failed to send initialize request (stdin closed)");
            }

            PluginMessage.ParsedMessage initResp = waitForResponse(initId, startupTimeoutMs);
            if (initResp == null || initResp.isError()) {
                throw new PluginException("Plugin initialization failed: " +
                        (initResp != null ? initResp.error() : "no response"));
            }

            // Extract plugin info from response
            if (initResp.params() != null) {
                this.pluginId = initResp.params().path("pluginId").asText(name);
                this.pluginVersion = initResp.params().path("version").asText("unknown");
            }

            log.info("[{}] Plugin initialized: {} v{}", name, pluginId, pluginVersion);

        } catch (PluginException e) {
            stopInternal();
            throw e;
        } catch (Exception e) {
            stopInternal();
            throw new PluginException("Failed to start plugin: " + e.getMessage(), e);
        }
    }

    /**
     * Restart the plugin. Clears pending requests and respawns the process.
     */
    public synchronized void restart(Map<String, Object> initParams) throws PluginException {
        restartCount++;
        if (restartCount > maxRestartAttempts) {
            throw new PluginException("Plugin '" + name + "' failed after " + maxRestartAttempts + " restart attempts");
        }

        log.warn("[{}] Restarting (attempt {}/{})", name, restartCount, maxRestartAttempts);
        stopInternal();

        try {
            Thread.sleep(restartBackoffMs * restartCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("Interrupted during restart backoff", e);
        }

        startInternal(initParams);
    }

    /**
     * Graceful stop: sends shutdown notification, then kills process.
     */
    public synchronized void stop() {
        log.info("[{}] Stopping plugin", name);
        stopInternal();
    }

    private void stopInternal() {
        running = false;

        // Fail all pending requests
        for (CompletableFuture<PluginMessage.ParsedMessage> future : pendingRequests.values()) {
            future.completeExceptionally(new PluginException("Plugin stopped"));
        }
        pendingRequests.clear();

        // Shutdown restart scheduler so no stale restarts fire after stop
        restartScheduler.shutdownNow();

        // Close stdin to signal graceful shutdown
        if (stdin != null) {
            try { stdin.close(); } catch (Exception ignored) {}
            stdin = null;
        }

        // Kill process
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            process = null;
        }

        // Interrupt reader thread
        if (stdoutReader != null && stdoutReader.isAlive()) {
            stdoutReader.interrupt();
            stdoutReader = null;
        }

        log.info("[{}] Plugin stopped", name);
    }

    // ─── Request/Response ───

    /**
     * Send a request and wait for response.
     */
    public PluginMessage.ParsedMessage sendRequest(String method, Map<String, Object> params) throws PluginException {
        int id = nextId();
        String msg = PluginMessage.buildRequest(method, params, id);
        CompletableFuture<PluginMessage.ParsedMessage> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            if (!sendRaw(msg)) {
                pendingRequests.remove(id);
                throw new PluginException("Cannot send request '" + method + "': stdin closed");
            }
            PluginMessage.ParsedMessage response = future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new PluginException("Request '" + method + "' received null response");
            }
            if (response.isError()) {
                throw new PluginException("Plugin error: " + response.error());
            }
            return response;
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            // Send cancellation notification to Bun side
            try {
                sendNotification("plugin/cancel", Map.of("requestId", id));
            } catch (Exception cancelErr) {
                log.debug("Failed to send cancel notification: {}", cancelErr.getMessage());
            }
            throw new PluginException("Request '" + method + "' timed out after " + requestTimeoutMs + "ms");
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            throw new PluginException("Request '" + method + "' failed: " +
                    (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        } catch (Exception e) {
            pendingRequests.remove(id);
            if (e instanceof PluginException) throw (PluginException) e;
            throw new PluginException("Request '" + method + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * Send a notification (fire-and-forget, no response).
     */
    public void sendNotification(String method, Map<String, Object> params) {
        String msg = PluginMessage.buildNotification(method, params);
        sendRaw(msg);
    }

    /**
     * Send a raw string to the plugin's stdin.
     *
     * @return true if sent successfully, false if stdin is closed
     */
    public boolean sendRaw(String message) {
        if (stdin == null) {
            log.warn("[{}] Cannot send: stdin closed", name);
            return false;
        }
        try {
            synchronized (stdin) {
                stdin.write(message);
                stdin.flush();
            }
            return true;
        } catch (IOException e) {
            log.error("[{}] Failed to send message: {}", name, e.getMessage());
            return false;
        }
    }

    // ─── Handler registration ───

    /** Handle incoming requests FROM the plugin (e.g., tool/execute) */
    public void onRequest(Consumer<PluginMessage.ParsedMessage> handler) {
        requestHandlers.add(handler);
    }

    /** Handle incoming notifications FROM the plugin */
    public void onNotification(Consumer<PluginMessage.ParsedMessage> handler) {
        notificationHandlers.add(handler);
    }

    /** Set handler for plugin log messages */
    public void setLogHandler(Consumer<String> handler) {
        this.logHandler = handler;
    }

    /** Set callback for unexpected disconnects. Called from reader thread. */
    public void setOnDisconnect(Runnable callback) {
        this.onDisconnect = callback;
    }

    // ─── Getters ───

    public String getName() { return name; }
    public String getPluginId() { return pluginId; }
    public String getPluginVersion() { return pluginVersion; }
    public boolean isRunning() { return running && process != null && process.isAlive(); }
    public long getUptimeMs() { return running ? System.currentTimeMillis() - startTimeMs : 0; }
    public Process getProcess() { return process; }

    // ─── Internal ───

    private int nextId() {
        return requestIdCounter.getAndIncrement();
    }

    private void startStdoutReader() {
        stdoutReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.length() > MAX_LINE_LENGTH) {
                        log.warn("[{}] Plugin sent line exceeding {} chars, truncating", name, MAX_LINE_LENGTH);
                        line = line.substring(0, MAX_LINE_LENGTH);
                    }
                    if (line.isBlank() || !PluginMessage.looksLikeJson(line)) continue;
                    try {
                        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);

                        // Handle parse errors (#3: don't swallow silently)
                        if (msg.isInvalid()) {
                            log.warn("[{}] Unparseable plugin message: {} — {}",
                                    name, line.substring(0, Math.min(80, line.length())), msg.error());
                            continue;
                        }

                        handleMessage(msg);
                    } catch (Exception e) {
                        log.warn("[{}] Failed to parse plugin message: {}", name, e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log.warn("[{}] stdout reader error: {}", name, e.getMessage());
                }
            } finally {
                if (running) {
                    log.warn("[{}] Plugin stdout closed unexpectedly", name);
                    handleDisconnect();
                }
            }
        }, "plugin-stdout-" + name);
        stdoutReader.setDaemon(true);
        stdoutReader.start();
    }

    private void startStderrLogger() {
        Thread stderrReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (logHandler != null) {
                        logHandler.accept(line);
                    } else {
                        log.debug("[{}] stderr: {}", name, line);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log.debug("[{}] stderr reader closed: {}", name, e.getMessage());
                }
            }
        }, "plugin-stderr-" + name);
        stderrReader.setDaemon(true);
        stderrReader.start();
    }

    private void handleMessage(PluginMessage.ParsedMessage msg) {
        if (msg.isResponse()) {
            CompletableFuture<PluginMessage.ParsedMessage> future = pendingRequests.remove(msg.id());
            if (future != null) {
                future.complete(msg);
            } else {
                log.debug("[{}] Orphaned response for id={}", name, msg.id());
            }
        } else if (msg.isRequest()) {
            for (Consumer<PluginMessage.ParsedMessage> handler : requestHandlers) {
                handler.accept(msg);
            }
        } else if (msg.isNotification()) {
            String method = msg.method();
            if ("plugin/ready".equals(method)) {
                readyPhaser.arrive();
            } else if ("plugin/log".equals(method) && logHandler != null) {
                JsonNode params = msg.params();
                String level = params != null ? params.path("level").asText("info") : "info";
                String message = params != null ? params.path("message").asText("") : "";
                logHandler.accept("[" + level + "] " + message);
            }
            for (Consumer<PluginMessage.ParsedMessage> handler : notificationHandlers) {
                handler.accept(msg);
            }
        }
    }

    private PluginMessage.ParsedMessage waitForResponse(int id, int timeoutMs) throws PluginException {
        CompletableFuture<PluginMessage.ParsedMessage> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new PluginException("Initialization timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            throw new PluginException("Initialization failed: " +
                    (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        } catch (Exception e) {
            pendingRequests.remove(id);
            if (e instanceof PluginException) throw (PluginException) e;
            throw new PluginException("Initialization failed: " + e.getMessage(), e);
        }
    }

    private void handleDisconnect() {
        if (!running) return;

        // Notify pending requests that the process died
        for (CompletableFuture<PluginMessage.ParsedMessage> future : pendingRequests.values()) {
            future.completeExceptionally(new PluginException("Plugin process disconnected"));
        }
        pendingRequests.clear();

        // Schedule auto-restart
        Runnable callback = onDisconnect;
        if (callback != null) {
            log.info("[{}] Scheduling auto-restart in {}ms", name, restartBackoffMs);
            restartScheduler.schedule(callback, restartBackoffMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ─── Exception ───

    public static class PluginException extends RuntimeException {
        public PluginException(String message) { super(message); }
        public PluginException(String message, Throwable cause) { super(message, cause); }
    }
}
