package com.flowguard;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** FlowGuard — API reliability gateway middleware. Pure-JDK, zero dependencies. */
public class FlowGuardApp {

    static final int PORT = port();

    public static void main(String[] args) throws Exception {
        seedRoutes();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", FlowGuardApp::handle);
        server.setExecutor(Executors.newFixedThreadPool(32, r -> {
            Thread t = new Thread(r, "flowguard");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("FlowGuard listening on http://localhost:" + PORT);
        System.out.println("Dashboard : http://localhost:" + PORT + "/");
        System.out.println("Health    : http://localhost:" + PORT + "/health");
        System.out.println("Metrics   : http://localhost:" + PORT + "/metrics");
    }

    // ---------------- routing ----------------
    static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        // normalize trailing slash (except root)
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length()-1);
        try {
            if (path.equals("/") && method.equals("GET")) { serveDashboard(ex); return; }
            if (path.equals("/health")) { health(ex); return; }
            if (path.equals("/metrics") && method.equals("GET")) { metrics(ex); return; }
            if (path.equals("/routes") && method.equals("GET")) { listRoutes(ex); return; }
            if (path.equals("/routes") && method.equals("POST")) { createRoute(ex); return; }
            if (path.startsWith("/routes/") && method.equals("DELETE")) { deleteRoute(ex, path.substring("/routes/".length())); return; }
            if (path.equals("/simulator/config") && method.equals("GET")) { getSim(ex); return; }
            if (path.equals("/simulator/config") && method.equals("POST")) { setSim(ex); return; }
            if (path.equals("/admin/reset") && method.equals("POST")) {
                Store.resetMetrics(); GuardCore.resetRateWindows(); seedRoutes();
                sendJson(ex, 200, "{\"ok\":true,\"reset\":true}");
                return;
            }
            if (path.startsWith("/proxy/")) { proxy(ex, method, path.substring("/proxy/".length())); return; }
            if ((method.equals("GET") || method.equals("HEAD")) && tryServeStatic(ex, path)) return;
            sendJson(ex, 404, "{\"error\":\"not_found\",\"path\":" + Json.stringify(path) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            try { sendJson(ex, 500, "{\"error\":\"internal\",\"message\":" + Json.stringify(String.valueOf(e.getMessage())) + "}"); }
            catch (IOException ignored) {}
        }
    }

    // ---------------- seed ----------------
    static void seedRoutes() {
        if (Store.apiRoutes.isEmpty()) {
            addRoute("weather", "/weather/bangalore", "sim://weather", 60, 100);
            addRoute("payments", "/payments/charge", "sim://stripe", 0, 100);
            addRoute("openai", "/openai/completions", "sim://openai", 0, 100);
        }
    }

    static void addRoute(String id, String path, String target, int ttl, int limit) {
        Store.Route r = new Store.Route();
        r.id = id; r.path = path; r.target = target; r.cacheTtlSec = ttl; r.rateLimitPerMin = limit;
        Store.apiRoutes.put(id, r);
    }

    // ---------------- handlers ----------------
    static void health(HttpExchange ex) throws IOException {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", "flowguard");
        m.put("time", System.currentTimeMillis());
        m.put("routes", Store.apiRoutes.size());
        m.put("simulator", Store.simulatorMode);
        sendJson(ex, 200, Json.stringify(m));
    }

    static void listRoutes(HttpExchange ex) throws IOException {
        List<Object> list = new ArrayList<>();
        for (Store.Route r : Store.apiRoutes.values()) list.add(r.toMap());
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("routes", list);
        sendJson(ex, 200, Json.stringify(m));
    }

    static void createRoute(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        Map<String,String> p = Json.parseFlat(body);
        String id = Json.get(p, "id", "").trim();
        String path = Json.get(p, "path", "/" + id).trim();
        if (id.isEmpty()) { sendJson(ex, 400, "{\"error\":\"id_required\"}"); return; }
        String target = Json.get(p, "target", "sim://" + id);
        int ttl = Json.getInt(p, "cacheTtlSec", 60);
        int limit = Json.getInt(p, "rateLimitPerMin", 100);
        addRoute(id, path, target, ttl, limit);
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("ok", true); m.put("route", Store.apiRoutes.get(id).toMap());
        sendJson(ex, 201, Json.stringify(m));
    }

    static void deleteRoute(HttpExchange ex, String id) throws IOException {
        if (id.contains("/")) id = id.substring(0, id.indexOf('/'));
        Store.Route removed = Store.apiRoutes.remove(id);
        if (removed == null) { sendJson(ex, 404, "{\"error\":\"route_not_found\"}"); return; }
        sendJson(ex, 200, "{\"ok\":true,\"deleted\":" + Json.stringify(id) + "}");
    }

    static void getSim(HttpExchange ex) throws IOException {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("mode", Store.simulatorMode);
        m.put("latencyMs", Store.simulatorLatencyMs);
        m.put("modes", Arrays.asList("NORMAL","SLOW","ERROR_500","ERROR_429","TIMEOUT","RANDOM"));
        sendJson(ex, 200, Json.stringify(m));
    }

    static void setSim(HttpExchange ex) throws IOException {
        Map<String,String> p = Json.parseFlat(readBody(ex));
        String mode = Json.get(p, "mode", "NORMAL").trim().toUpperCase();
        List<String> valid = Arrays.asList("NORMAL","SLOW","ERROR_500","ERROR_429","TIMEOUT","RANDOM");
        // allow aliases: "500 errors" -> ERROR_500 etc.
        if (mode.contains("500")) mode = "ERROR_500";
        else if (mode.contains("429")) mode = "ERROR_429";
        else if (mode.contains("TIME")) mode = "TIMEOUT";
        else if (mode.contains("SLOW")) mode = "SLOW";
        else if (mode.contains("RANDOM")) mode = "RANDOM";
        else if (mode.contains("NORMAL")) mode = "NORMAL";
        if (!valid.contains(mode)) { sendJson(ex, 400, "{\"error\":\"invalid_mode\",\"valid\":" + Json.stringify(valid) + "}"); return; }
        Store.simulatorMode = mode;
        int lat = Json.getInt(p, "latencyMs", Store.simulatorLatencyMs);
        if (lat >= 0 && lat <= 5000) Store.simulatorLatencyMs = lat;
        // Changing simulator back to NORMAL does not auto-close circuits; half-open trial will close them.
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("ok", true); m.put("mode", Store.simulatorMode);
        sendJson(ex, 200, Json.stringify(m));
    }

    // ---------------- metrics ----------------
    static void metrics(HttpExchange ex) throws IOException {
        Map<String,Object> m = new LinkedHashMap<>();
        long total = Store.totalRequests.get();
        m.put("requests", total);
        m.put("success", Store.successCount.get());
        m.put("errors", Store.errorCount.get());
        m.put("successRate", round(GuardCore.successRate()));
        m.put("p95LatencyMs", GuardCore.p95());
        m.put("avgLatencyMs", GuardCore.avgLatency());
        m.put("cacheHitRate", round(GuardCore.cacheHitRate()));
        m.put("cacheHits", Store.cacheHits.get());
        m.put("cacheMisses", Store.cacheMisses.get());
        m.put("retries", Store.retryCount.get());
        m.put("circuitBreaks", Store.circuitBreaks.get());
        m.put("rateLimited", Store.rateLimitedCount.get());
        m.put("simulator", Store.simulatorMode);
        m.put("cacheEntries", Store.cacheEntries.size());

        List<Object> circuits = new ArrayList<>();
        for (Map.Entry<String, Store.CircuitState> e : Store.circuitStates.entrySet())
            circuits.add(Store.circuitMap(e.getKey(), e.getValue()));
        m.put("circuits", circuits);

        // charts: bucket last 60s into per-second volume + errors; latency avg per bucket; cache hits per bucket
        Map<Long, int[]> buckets = new TreeMap<>(); // sec -> [total, errors, cacheHits, retries-proxy?]
        long nowSec = System.currentTimeMillis() / 1000;
        for (long s = nowSec - 29; s <= nowSec; s++) buckets.put(s, new int[4]);
        List<Store.RequestLog> recent = new ArrayList<>(Store.requestLogs);
        recent.sort(Comparator.comparingLong(l -> l.ts));
        if (recent.size() > 120) recent = recent.subList(recent.size()-120, recent.size());
        for (Store.RequestLog l : recent) {
            long s = l.ts / 1000;
            int[] b = buckets.get(s);
            if (b == null) continue;
            b[0]++;
            if (l.status >= 400) b[1]++;
            if (l.cacheHit) b[2]++;
            if (l.attempts > 1) b[3]++;
        }
        List<Object> vol = new ArrayList<>(), errs = new ArrayList<>(), ch = new ArrayList<>(), rt = new ArrayList<>();
        List<Object> labels = new ArrayList<>();
        for (Map.Entry<Long,int[]> e : buckets.entrySet()) {
            labels.add(e.getKey() % 100000);
            vol.add(e.getValue()[0]); errs.add(e.getValue()[1]);
            ch.add(e.getValue()[2]); rt.add(e.getValue()[3]);
        }
        Map<String,Object> charts = new LinkedHashMap<>();
        charts.put("labels", labels);
        charts.put("volume", vol); charts.put("errors", errs);
        charts.put("cacheHits", ch); charts.put("retries", rt);
        m.put("charts", charts);

        List<Object> logs = new ArrayList<>();
        List<Store.RequestLog> desc = new ArrayList<>(recent);
        Collections.reverse(desc);
        for (int i = 0; i < Math.min(30, desc.size()); i++) {
            Store.RequestLog l = desc.get(i);
            Map<String,Object> lm = new LinkedHashMap<>();
            lm.put("ts", l.ts); lm.put("route", l.routeId); lm.put("method", l.method);
            lm.put("status", l.status); lm.put("latencyMs", l.latencyMs);
            lm.put("cacheHit", l.cacheHit); lm.put("attempts", l.attempts); lm.put("outcome", l.outcome);
            logs.add(lm);
        }
        m.put("recentLogs", logs);
        sendJson(ex, 200, Json.stringify(m));
    }

    static double round(double d) { return Math.round(d * 10.0) / 10.0; }

    // ---------------- proxy (the gateway) ----------------
    static void proxy(HttpExchange ex, String method, String rest) throws IOException {
        long start = System.currentTimeMillis();
        String routeId = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        String subPath = rest.contains("/") ? rest.substring(rest.indexOf('/')) : "";
        String query = ex.getRequestURI().getQuery();
        if (query == null) query = "";
        // support query ?id= for route-less calls? no — routeId is path segment.
        Store.Route route = Store.apiRoutes.get(routeId);
        if (route == null) { sendJson(ex, 404, "{\"error\":\"route_not_found\",\"route\":" + Json.stringify(routeId) + "}"); return; }

        String outcome = "SUCCESS";
        int attempts = 0;
        boolean cacheHit = false;
        int finalStatus = 200;
        String finalBody = "";

        // 1. Circuit breaker pre-check
        GuardCore.CircuitDecision decision = GuardCore.circuitPreCheck(routeId);
        Store.CircuitState cs = Store.circuitFor(routeId);
        if (decision == GuardCore.CircuitDecision.REJECT_OPEN) {
            finalStatus = 503;
            finalBody = "{\"error\":\"circuit_open\",\"route\":" + Json.stringify(routeId)
                + ",\"state\":\"OPEN\",\"retry_after_ms\":" + Math.max(0, GuardCore.OPEN_TIMEOUT_MS - (System.currentTimeMillis() - cs.openedAt)) + "}";
            outcome = "CIRCUIT_OPEN";
            finishLog(ex, routeId, method, subPath, finalStatus, start, cacheHit, 0, outcome);
            ex.getResponseHeaders().set("X-FlowGuard-Circuit", "OPEN");
            sendJson(ex, finalStatus, finalBody);
            return;
        }
        boolean halfOpenTrial = (decision == GuardCore.CircuitDecision.ALLOW_HALF_OPEN_TRIAL);

        // 2. Rate limiter (100 req/min default)
        GuardCore.RateCheck rc = GuardCore.checkRateLimit(routeId, route.rateLimitPerMin);
        if (!rc.allowed) {
            finalStatus = 429;
            finalBody = "{\"error\":\"rate_limit_exceeded\",\"retry_after\":" + rc.retryAfterSec + "}";
            outcome = "RATE_LIMITED";
            Store.rateLimitedCount.incrementAndGet();
            finishLog(ex, routeId, method, subPath, finalStatus, start, false, 0, outcome);
            ex.getResponseHeaders().set("X-FlowGuard-RateLimited", "true");
            sendJson(ex, finalStatus, finalBody);
            return;
        }

        // 3. Cache lookup (GET only, ttl>0)
        String cacheKey = method + ":" + routeId + ":" + subPath + (query.isEmpty() ? "" : "?" + query);
        if (method.equals("GET") && route.cacheTtlSec > 0) {
            Store.CacheEntry hit = GuardCore.getCache(cacheKey);
            if (hit != null) {
                Store.cacheHits.incrementAndGet();
                Store.totalRequests.incrementAndGet();
                Store.successCount.incrementAndGet();
                Store.RequestLog log = new Store.RequestLog();
                log.routeId = routeId; log.method = method; log.path = subPath;
                log.status = hit.status; log.latencyMs = System.currentTimeMillis() - start;
                log.cacheHit = true; log.attempts = 0; log.outcome = "CACHE_HIT";
                Store.addLog(log);
                ex.getResponseHeaders().set("X-Cache", "HIT");
                ex.getResponseHeaders().set("X-FlowGuard-Circuit", cs.state.name());
                sendRaw(ex, hit.status, hit.body);
                return;
            } else {
                Store.cacheMisses.incrementAndGet();
            }
        }

        // 4. Retry loop with timeout + exponential backoff
        GuardCore.SimResult last = null;
        int maxAttempts = GuardCore.MAX_ATTEMPTS;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            try {
                last = GuardCore.callDownstream(route, subPath.isEmpty() ? route.path : subPath, method);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                last = new GuardCore.SimResult();
                last.status = 504; last.timeout = true; last.latencyMs = GuardCore.DOWNSTREAM_TIMEOUT_MS;
                last.body = "{\"error\":\"downstream_timeout\"}";
            }
            boolean retryable = last.timeout || GuardCore.isRetryable(last.status);
            if (!retryable) break;                       // success / 4xx / cached-able error -> stop
            if (attempt < maxAttempts) {
                Store.retryCount.incrementAndGet();
                outcome = "RETRIED";
                try { Thread.sleep(GuardCore.BACKOFF_MS[attempt-1]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        finalStatus = last.status;
        finalBody = last.body;
        boolean success = finalStatus >= 200 && finalStatus < 300;
        boolean downstreamFailure = last.timeout || (finalStatus >= 500 && finalStatus <= 599);

        // 5. Circuit breaker update (only 5xx/timeout trip the breaker; 429 does not)
        if (halfOpenTrial) {
            if (success) { GuardCore.circuitOnSuccess(routeId); outcome = "HALF_OPEN_CLOSED"; }
            else if (downstreamFailure) { GuardCore.circuitOnFailure(routeId); outcome = "HALF_OPEN_FAILED"; }
        } else {
            if (success) { GuardCore.circuitOnSuccess(routeId); }
            else if (downstreamFailure) {
                GuardCore.circuitOnFailure(routeId);
                if (Store.circuitFor(routeId).state == Store.Circuit.OPEN) outcome = "CIRCUIT_OPENED";
                else if (attempts > 1) outcome = "RETRIED_FAILED";
                else outcome = "FAILED";
            }
        }
        if (success && attempts > 1) outcome = "RETRIED_SUCCESS";
        if (success && attempts <= 1 && !outcome.startsWith("HALF")) outcome = "SUCCESS";
        if (!success && outcome.equals("SUCCESS")) outcome = "FAILED";

        // 6. Populate cache on success
        if (success && method.equals("GET") && route.cacheTtlSec > 0) {
            GuardCore.putCache(cacheKey, finalStatus, finalBody, route.cacheTtlSec);
        }

        finishLog(ex, routeId, method, subPath, finalStatus, start, false, attempts, outcome);
        Store.CircuitState after = Store.circuitFor(routeId);
        ex.getResponseHeaders().set("X-Cache", "MISS");
        ex.getResponseHeaders().set("X-FlowGuard-Attempts", String.valueOf(attempts));
        ex.getResponseHeaders().set("X-FlowGuard-Circuit", after.state.name());
        sendRaw(ex, finalStatus, finalBody);
    }

    static void finishLog(HttpExchange ex, String routeId, String method, String path, int status,
                          long start, boolean cacheHit, int attempts, String outcome) {
        long lat = System.currentTimeMillis() - start;
        Store.totalRequests.incrementAndGet();
        if (status >= 200 && status < 300) Store.successCount.incrementAndGet();
        else Store.errorCount.incrementAndGet();
        Store.RequestLog log = new Store.RequestLog();
        log.routeId = routeId; log.method = method; log.path = path;
        log.status = status; log.latencyMs = lat;
        log.cacheHit = cacheHit; log.attempts = attempts; log.outcome = outcome;
        Store.addLog(log);
    }

    // ---------------- dashboard ----------------
    static void serveDashboard(HttpExchange ex) throws IOException {
        byte[] html = loadDashboard();
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(html); }
    }

    static byte[] loadDashboard() {
        String[] candidates = {"public/index.html", "./public/index.html", "F:/Java/Flowguard/public/index.html"};
        for (String c : candidates) {
            try {
                Path p = Paths.get(c);
                if (Files.exists(p)) return Files.readAllBytes(p);
            } catch (Exception ignored) {}
        }
        String fallback = "<html><body><h1>FlowGuard is running</h1><p>public/index.html not found.</p></body></html>";
        return fallback.getBytes(StandardCharsets.UTF_8);
    }

    /** Serve static assets from public/ (hero-bg.jpg, css, js, images). Rejects path traversal. */
    static boolean tryServeStatic(HttpExchange ex, String path) throws IOException {
        if (path.contains("..") || !path.contains(".")) return false;
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.contains("/")) return false; // flat public/ only
        String lower = name.toLowerCase();
        boolean allowed = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            || lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".ico")
            || lower.endsWith(".mp4") || lower.endsWith(".webm")
            || lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".woff2");
        if (!allowed) return false;
        String[] candidates = {"public/" + name, "./public/" + name, "F:/Java/Flowguard/public/" + name};
        for (String c : candidates) {
            try {
                Path p = Paths.get(c);
                if (Files.exists(p) && Files.isRegularFile(p)) {
                    boolean isHead = ex.getRequestMethod().equalsIgnoreCase("HEAD");
                    if (isHead) {
                        ex.getResponseHeaders().set("Content-Type", contentTypeFor(lower));
                        ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
                        ex.sendResponseHeaders(200, -1);
                        ex.getResponseBody().close();
                        return true;
                    }
                    byte[] b = Files.readAllBytes(p);
                    ex.getResponseHeaders().set("Content-Type", contentTypeFor(lower));
                    ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
                    ex.sendResponseHeaders(200, b.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    static String contentTypeFor(String lower) {
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        return "image/jpeg";
    }

    // ---------------- http helpers ----------------
    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void sendRaw(HttpExchange ex, int status, String body) throws IOException {
        if (body == null) body = "";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (!ex.getResponseHeaders().containsKey("Content-Type"))
            ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static int port() {
        try {
            String p = System.getenv("PORT");
            if (p != null && !p.isBlank()) return Integer.parseInt(p.trim());
        } catch (Exception ignored) {}
        return 8080;
    }
}
