package com.flowguard;

import java.util.*;
import java.util.concurrent.*;

/**
 * Core reliability primitives: rate limiter, cache, retry, circuit breaker,
 * timeout, failure simulator, metrics.
 */
public final class GuardCore {
    private GuardCore() {}

    // ---------- Rate limiter: sliding window per route ----------
    private static final ConcurrentHashMap<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    public static final class RateCheck {
        public boolean allowed;
        public long retryAfterSec;
    }

    public static RateCheck checkRateLimit(String routeId, int limitPerMin) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L;
        ArrayDeque<Long> q = windows.computeIfAbsent(routeId, k -> new ArrayDeque<>());
        RateCheck rc = new RateCheck();
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < windowStart) q.pollFirst();
            if (q.size() < limitPerMin) {
                q.addLast(now);
                rc.allowed = true;
                rc.retryAfterSec = 0;
            } else {
                rc.allowed = false;
                long oldest = q.peekFirst();
                rc.retryAfterSec = Math.max(1, (oldest + 60_000L - now + 999) / 1000);
            }
        }
        return rc;
    }

    public static void resetRateWindows() { windows.clear(); }

    // ---------- Cache ----------
    public static Store.CacheEntry getCache(String key) {
        Store.CacheEntry e = Store.cacheEntries.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expiresAt) {
            Store.cacheEntries.remove(key);
            return null;
        }
        return e;
    }

    public static void putCache(String key, int status, String body, int ttlSec) {
        Store.CacheEntry e = new Store.CacheEntry();
        e.key = key; e.status = status; e.body = body;
        e.expiresAt = System.currentTimeMillis() + ttlSec * 1000L;
        Store.cacheEntries.put(key, e);
    }

    // ---------- Retry config ----------
    public static final int MAX_ATTEMPTS = 3;
    /** Backoff delays between attempts: 200ms, 500ms (spec). */
    public static final long[] BACKOFF_MS = {200, 500};

    public static boolean isRetryable(int status) {
        return status == 500 || status == 502 || status == 503 || status == 504;
    }

    // ---------- Circuit breaker ----------
    public static final int FAILURE_THRESHOLD = 5;
    public static final long OPEN_TIMEOUT_MS = 10_000L;

    public enum CircuitDecision { ALLOW, REJECT_OPEN, ALLOW_HALF_OPEN_TRIAL }

    public static CircuitDecision circuitPreCheck(String routeId) {
        Store.CircuitState cs = Store.circuitFor(routeId);
        if (cs.state == Store.Circuit.CLOSED) return CircuitDecision.ALLOW;
        if (cs.state == Store.Circuit.OPEN) {
            long now = System.currentTimeMillis();
            if (now - cs.openedAt >= OPEN_TIMEOUT_MS) {
                cs.state = Store.Circuit.HALF_OPEN;
                cs.halfOpenAttempts = 0;
                cs.lastTransitionAt = now;
                return CircuitDecision.ALLOW_HALF_OPEN_TRIAL;
            }
            return CircuitDecision.REJECT_OPEN;
        }
        return CircuitDecision.ALLOW_HALF_OPEN_TRIAL; // HALF_OPEN: allow trial
    }

    public static void circuitOnSuccess(String routeId) {
        Store.CircuitState cs = Store.circuitFor(routeId);
        if (cs.state == Store.Circuit.HALF_OPEN) {
            cs.state = Store.Circuit.CLOSED;
            cs.consecutiveFailures = 0;
            cs.lastTransitionAt = System.currentTimeMillis();
        } else {
            cs.consecutiveFailures = 0;
        }
    }

    public static void circuitOnFailure(String routeId) {
        Store.CircuitState cs = Store.circuitFor(routeId);
        long now = System.currentTimeMillis();
        if (cs.state == Store.Circuit.HALF_OPEN) {
            // trial failed -> back to OPEN
            cs.state = Store.Circuit.OPEN;
            cs.openedAt = now;
            cs.lastTransitionAt = now;
            Store.circuitBreaks.incrementAndGet();
        } else if (cs.state == Store.Circuit.CLOSED) {
            cs.consecutiveFailures++;
            if (cs.consecutiveFailures >= FAILURE_THRESHOLD) {
                cs.state = Store.Circuit.OPEN;
                cs.openedAt = now;
                cs.lastTransitionAt = now;
                Store.circuitBreaks.incrementAndGet();
            }
        } else { // OPEN (shouldn't happen, but refresh)
            cs.openedAt = now;
        }
    }

    // ---------- Failure simulator (fake downstream API) ----------
    public static final class SimResult {
        public int status;
        public String body;
        public long latencyMs;
        public boolean timeout;
    }

    private static final ExecutorService simPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sim");
        t.setDaemon(true);
        return t;
    });
    private static final Random rnd = new Random();

    public static final long DOWNSTREAM_TIMEOUT_MS = 2000L;

    /** Execute simulated downstream with timeout enforcement. */
    public static SimResult callDownstream(Store.Route route, String subPath, String method) throws InterruptedException {
        String mode = Store.simulatorMode;
        if ("RANDOM".equals(mode)) {
            double r = rnd.nextDouble();
            if (r < 0.20) mode = "ERROR_500";
            else if (r < 0.30) mode = "ERROR_429";
            else if (r < 0.38) mode = "SLOW";
            else if (r < 0.44) mode = "TIMEOUT";
            else mode = "NORMAL";
        }
        final String m = mode;
        Future<SimResult> f = simPool.submit(() -> runSim(route, subPath, method, m));
        try {
            return f.get(DOWNSTREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            SimResult r = new SimResult();
            r.status = 504;
            r.timeout = true;
            r.latencyMs = DOWNSTREAM_TIMEOUT_MS;
            r.body = "{\"error\":\"downstream_timeout\",\"route\":" + Json.stringify(route.id) + "}";
            return r;
        } catch (ExecutionException ee) {
            SimResult r = new SimResult();
            r.status = 500;
            r.latencyMs = 5;
            r.body = "{\"error\":\"downstream_exception\"}";
            return r;
        }
    }

    private static SimResult runSim(Store.Route route, String subPath, String method, String mode) {
        long start = System.currentTimeMillis();
        SimResult r = new SimResult();
        try {
            switch (mode) {
                case "SLOW":
                    sleep(1200);
                    r.status = 200;
                    r.body = simBody(route, subPath, "slow");
                    break;
                case "ERROR_500":
                    sleep(30 + rnd.nextInt(40));
                    r.status = 500;
                    r.body = "{\"error\":\"downstream_500\",\"route\":" + Json.stringify(route.id) + ",\"mode\":\"ERROR_500\"}";
                    break;
                case "ERROR_429":
                    sleep(20 + rnd.nextInt(30));
                    r.status = 429;
                    r.body = "{\"error\":\"downstream_rate_limited\",\"retry_after\":5}";
                    break;
                case "TIMEOUT":
                    sleep(DOWNSTREAM_TIMEOUT_MS + 1500); // exceeds timeout -> caller times out
                    r.status = 200;
                    r.body = simBody(route, subPath, "late");
                    break;
                default: // NORMAL
                    sleep(Store.simulatorLatencyMs + rnd.nextInt(25));
                    r.status = 200;
                    r.body = simBody(route, subPath, "ok");
                    break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            r.status = 504; r.timeout = true;
            r.body = "{\"error\":\"downstream_timeout\"}";
        }
        r.latencyMs = System.currentTimeMillis() - start;
        return r;
    }

    private static String simBody(Store.Route route, String subPath, String tag) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("route", route.id);
        m.put("path", route.path + (subPath == null ? "" : subPath));
        m.put("mode", Store.simulatorMode);
        m.put("result", tag + "-data-for-" + route.id);
        m.put("ts", System.currentTimeMillis());
        return Json.stringify(m);
    }

    private static void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }

    // ---------- Metrics ----------
    public static double successRate() {
        long t = Store.totalRequests.get();
        if (t == 0) return 100.0;
        return Store.successCount.get() * 100.0 / t;
    }

    public static double cacheHitRate() {
        long h = Store.cacheHits.get(), miss = Store.cacheMisses.get();
        long t = h + miss;
        if (t == 0) return 0.0;
        return h * 100.0 / t;
    }

    public static long p95() {
        List<Long> l = new ArrayList<>(Store.latencies);
        if (l.isEmpty()) return 0;
        Collections.sort(l);
        int idx = (int) Math.ceil(0.95 * l.size()) - 1;
        idx = Math.max(0, Math.min(idx, l.size()-1));
        return l.get(idx);
    }

    public static long avgLatency() {
        List<Long> l = new ArrayList<>(Store.latencies);
        if (l.isEmpty()) return 0;
        long s = 0; for (long v : l) s += v;
        return s / l.size();
    }
}
