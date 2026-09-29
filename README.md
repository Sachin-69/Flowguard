# FlowGuard — Intelligent API Reliability Layer (Day 2)

API gateway middleware that protects apps from rate limits, downstream failures,
latency spikes and unnecessary API calls via **rate limiting, caching, retries,
circuit breaker, timeouts, metrics** + a **failure simulator** for demos.

```
Client → FlowGuard → [RateLimiter | Cache | Retry | CircuitBreaker | Timeout | Metrics | Simulator] → External API
```

Pure **Java (JDK only, zero dependencies)** — runs with `javac`/`java`, no Maven needed.

## Quick start

```bat
run.bat
```

Then open:

- Dashboard: http://localhost:8080/
- Health: http://localhost:8080/health
- Metrics: http://localhost:8080/metrics
- Routes: http://localhost:8080/routes

## APIs

| Method | Path | Description |
|---|---|---|
| POST | `/routes` | Create route `{id, path, target?, cacheTtlSec?=60, rateLimitPerMin?=100}` |
| GET | `/routes` | List routes (`api_routes` table) |
| DELETE | `/routes/:id` | Delete route |
| GET/POST/PUT/DELETE | `/proxy/:route[/*subpath]` | Gateway entry. Applies circuit → rate-limit → cache → retry+timeout → downstream |
| GET | `/metrics` | Requests, successRate, p95, cacheHitRate, retries, circuitBreaks + charts + recent logs |
| GET | `/health` | `{status:"UP", ...}` |
| GET/POST | `/simulator/config` | `{mode: NORMAL\|SLOW\|ERROR_500\|ERROR_429\|TIMEOUT\|RANDOM, latencyMs?}` |
| POST | `/admin/reset` | Reset metrics/caches/circuits (demo helper) |

## Features

1. **Rate limiter** — 100 req/min per route (sliding window). Exceeded → `429 {"error":"rate_limit_exceeded","retry_after":12}`.
2. **Cache** — GET responses cached (TTL per route, default 60s). `X-Cache: HIT/MISS`. Hit rate in metrics.
3. **Retry engine** — transient `500/502/503/504` + timeouts retried, max 3 attempts, backoff 200ms → 500ms.
4. **Circuit breaker** — `CLOSED → OPEN → HALF_OPEN → CLOSED`. 5 consecutive 5xx/timeouts open the circuit for 10s; trial request half-opens it. Open → `503 {"error":"circuit_open"}` without calling downstream.
5. **Timeout** — 2000ms downstream timeout (simulator `TIMEOUT` sleeps past it → `504`).
6. **Failure simulator** — fake downstream: Normal / Slow (1.2s) / 500 errors / 429 errors / Timeout / Random.
7. **Dashboard** — live cards (Requests, Success Rate, P95, Cache Hit Rate, Retries, Circuit Breaks), canvas charts (volume, errors, cache, retries), simulator buttons, routes + circuit states + request logs.

## Day-2 demo script (the strong backend demo)

```powershell
# 1. normal traffic (all 200s)
Invoke-RestMethod http://localhost:8080/proxy/weather?city=bangalore

# 2. break downstream
Invoke-RestMethod -Method Post -ContentType "application/json" -Body '{"mode":"ERROR_500"}' http://localhost:8080/simulator/config

# 3. fire 20 requests -> observe Retry -> Retry -> Circuit OPEN (503 circuit_open)
1..20 | % { (Invoke-WebRequest http://localhost:8080/proxy/payments -SkipHttpErrorCheck).StatusCode }

# 4. check metrics: retries climbing, circuitBreaks=1+, circuits=[{payments: OPEN}]
Invoke-RestMethod http://localhost:8080/metrics | ConvertTo-Json -Depth 5

# 5. heal downstream
Invoke-RestMethod -Method Post -ContentType "application/json" -Body '{"mode":"NORMAL"}' http://localhost:8080/simulator/config

# 6. wait 10s (open timeout), send 1 request -> HALF_OPEN trial succeeds -> CLOSED
Start-Sleep 11; Invoke-RestMethod http://localhost:8080/proxy/payments
```

Or click **"Send 20 requests (Day-2 demo)"** in the dashboard after selecting **500 errors**.

## In-memory "database" (mirrors required tables)

- `apiRoutes` ↔ `api_routes`
- `requestLogs` ↔ `request_logs`
- `cacheEntries` ↔ `cache_entries`
- `circuitStates` ↔ `circuit_states`
- per-route `rateLimitPerMin` + sliding windows ↔ `rate_limit_configs`

## Project layout

```
src/com/flowguard/FlowGuardApp.java  — HTTP server + routing + proxy pipeline
src/com/flowguard/GuardCore.java     — rate limiter, cache, retry, breaker, simulator, metrics
src/com/flowguard/Store.java         — in-memory tables + counters
src/com/flowguard/Json.java          — tiny JSON util (no deps)
public/index.html                    — dashboard (no CDN, works offline)
build.bat / run.bat
```

## Config

- `PORT` env var (default 8080)
- Circuit: threshold 5 failures, open 10s (`GuardCore.java`)
- Retry: 3 attempts, backoff 200/500ms; Timeout 2000ms
