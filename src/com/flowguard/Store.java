package com.flowguard;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * In-memory "database" mirroring the required tables:
 *  api_routes, request_logs, cache_entries, circuit_states, rate_limit_configs
 */
public final class Store {
    private Store() {}

    // ---- api_routes ----
    public static final class Route {
        public String id;            // e.g. "weather"
        public String path;          // e.g. "/weather/bangalore"
        public String target;        // downstream base (simulated)
        public int cacheTtlSec = 60;
        public int rateLimitPerMin = 100;
        public long createdAt = System.currentTimeMillis();

        public Map<String,Object> toMap() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("path", path); m.put("target", target);
            m.put("cacheTtlSec", cacheTtlSec); m.put("rateLimitPerMin", rateLimitPerMin);
            m.put("createdAt", createdAt);
            return m;
        }
    }

    // ---- cache_entries ----
    public static final class CacheEntry {
        public String key;
        public int status;
        public String body;
        public long expiresAt;
        public long createdAt = System.currentTimeMillis();
    }

    // ---- request_logs ----
    public static final class RequestLog {
        public long ts = System.currentTimeMillis();
        public String routeId;
        public String method;
        public String path;
        public int status;
        public long latencyMs;
        public boolean cacheHit;
        public int attempts;
        public String outcome; // SUCCESS | RETRIED | RATE_LIMITED | CIRCUIT_OPEN | FAILED
    }

    // ---- circuit_states ----
    public enum Circuit { CLOSED, OPEN, HALF_OPEN }

    public static final class CircuitState {
        public volatile Circuit state = Circuit.CLOSED;
        public volatile int consecutiveFailures = 0;
        public volatile long openedAt = 0;
        public volatile long lastTransitionAt = System.currentTimeMillis();
        public volatile int halfOpenAttempts = 0;
    }

    // ---- tables ----
    public static final ConcurrentHashMap<String, Route> apiRoutes = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, CacheEntry> cacheEntries = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    public static final ConcurrentLinkedQueue<RequestLog> requestLogs = new ConcurrentLinkedQueue<>();
    public static final AtomicLong requestLogCount = new AtomicLong(0);

    // ---- simulator config ----
    public static volatile String simulatorMode = "NORMAL"; // NORMAL|SLOW|ERROR_500|ERROR_429|TIMEOUT|RANDOM
    public static volatile int simulatorLatencyMs = 30;

    // ---- metrics counters ----
    public static final AtomicLong totalRequests = new AtomicLong(0);
    public static final AtomicLong successCount = new AtomicLong(0);
    public static final AtomicLong errorCount = new AtomicLong(0);
    public static final AtomicLong cacheHits = new AtomicLong(0);
    public static final AtomicLong cacheMisses = new AtomicLong(0);
    public static final AtomicLong retryCount = new AtomicLong(0);
    public static final AtomicLong circuitBreaks = new AtomicLong(0);
    public static final AtomicLong rateLimitedCount = new AtomicLong(0);
    public static final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

    public static CircuitState circuitFor(String routeId) {
        return circuitStates.computeIfAbsent(routeId, k -> new CircuitState());
    }

    public static void addLog(RequestLog log) {
        requestLogs.add(log);
        requestLogCount.incrementAndGet();
        // bound queue to last 5000
        while (requestLogs.size() > 5000) requestLogs.poll();
        // bound latencies to last 5000
        latencies.add(log.latencyMs);
        while (latencies.size() > 5000) latencies.poll();
    }

    public static Map<String,Object> circuitMap(String routeId, CircuitState cs) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("routeId", routeId);
        m.put("state", cs.state.name());
        m.put("consecutiveFailures", cs.consecutiveFailures);
        m.put("openedAt", cs.openedAt);
        m.put("lastTransitionAt", cs.lastTransitionAt);
        return m;
    }

    public static void resetMetrics() {
        totalRequests.set(0); successCount.set(0); errorCount.set(0);
        cacheHits.set(0); cacheMisses.set(0); retryCount.set(0);
        circuitBreaks.set(0); rateLimitedCount.set(0);
        requestLogs.clear(); latencies.clear(); requestLogCount.set(0);
        cacheEntries.clear();
        circuitStates.clear();
    }
}
