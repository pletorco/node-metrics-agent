# Host-Safe and Security-Critical Module Guidelines (Agent MUST Follow)

## 0) Scope and Intent
This repository may contain modules that execute inside host-critical processes:
- JVM agents / instrumentation
- Log4j2 appenders and telemetry pipelines
- Kafka broker login/callback handlers (including validation callbacks)
- Kafka broker authorizers

These modules are powerful and risky. A bug can cause:
- host outage or latency regression
- security policy bypass or unintended deny
- data loss or audit blind spots

This document defines shared engineering rules that are reusable across module types.

### Definitions (to avoid ambiguity)
- **Host hot path / host threads**: request-processing threads, network I/O threads, authentication/
  authorization callback threads, logging publication threads.
- **Decision path**: code that directly determines allow/deny outcomes (for example, `authorize()`,
  login/callback validation).
- **External dependency**: any network/DNS call, filesystem I/O, subprocess execution, or remote
  KMS/IdP/HTTP/DB/cache access.

---

## 1) Rule Precedence (Conflict Resolution)
When rules conflict, apply this order:
1. **Security Decision Integrity** (authn/authz correctness and explicit fail policy)
2. **Host Stability** (no crash, no host-thread blocking, no behavior corruption)
3. **Performance/Overhead Budgets** (bounded CPU/alloc/p99 impact)
4. **Telemetry Completeness** (best effort only unless policy says otherwise)

Notes:
- For security-decision modules (login/callback/authorizer), integrity can require fail-closed.
- For telemetry modules (appender/agent telemetry), fail-open is default unless explicitly approved
  otherwise.

---

## 2) Module Classification (MUST declare in PR)
Every change MUST classify target code as one of:
- **Telemetry Module**: appender, tracing, metric exporter, passive agent hooks
- **Security-Decision Module**: login/callback validation, authorizer, permission evaluator
- **Mixed Module**: contains both; treat each path by its stricter class

PR template MUST include:
- module class
- host entry points (hot paths)
- selected fail policy
- degraded/bypass behavior

If the repository does not provide a PR template, include the above items in the PR description.

---

## 3) Universal Non-Negotiables (MUST)

### 3.1 Preserve Host Safety
- Do not propagate uncaught exceptions to host call sites.
- Guard host entry points with `catch (Throwable)` and isolate failure.
- For security-decision paths, caught internal errors MUST be mapped to explicit fail policy outcomes
  (deny/allow) and returned deterministically; do not silently swallow without policy mapping.
- Do not block host threads. Timeouts are safety brakes, not permission to block decision/hot paths.
- Blocking examples forbidden on host threads:
  - `Future.get()` / `join()`
  - `await()` / `sleep()`
  - blocking queue `put()` / `take()` on hot/decision paths

### 3.2 No Unbounded Resources
- No unbounded queues/maps/caches/pools.
- Every structure must have hard limits and an explicit overflow policy.
- On saturation, never block host threads.

### 3.3 Hot-Path Cost Discipline
- Keep host-path logic O(1) or tightly bounded.
- Avoid expensive parsing/serialization/regex on hot paths unless explicitly justified and budgeted.
- Keep allocations minimal; avoid repeated large object creation.

### 3.4 Safe Internal Logging
- Internal module logs must be rate-limited.
- Avoid recursive logging loops (`module -> logger -> same module`).
- Never emit secrets in logs, metrics, or traces.

### 3.5 Change Safety
- New behavior must be feature-flagged or have safe defaults.
- Failure mode and rollback plan must be explicit.

---

## 4) Security Module Characteristics (MUST)
This section applies to login/callback/authorizer paths.

### 4.1 Decision Integrity First
- Never silently change allow/deny semantics in degraded mode.
- Any allow/deny semantic change is a **policy change**, not a performance optimization.
- Fail policy must be explicit and documented:
  - `FAIL_CLOSED` (recommended default for security)
  - `FAIL_OPEN` (requires explicit approval and audit note)

#### Fail Policy Semantics (MUST be explicit)
- `FAIL_CLOSED`: on internal error or unknown/invalid state ⇒ **DENY**
- `FAIL_OPEN`: on internal error or unknown/invalid state ⇒ **ALLOW**
- “unknown/invalid state” includes (non-exhaustive):
  - refresh failures/timeouts
  - missing/empty snapshot
  - stale snapshot beyond configured max-age
  - parse/validation errors of policy/config
  - unexpected exceptions on the decision path

### 4.2 No External Dependency on Decision Path
- No HTTP/DB/DNS/filesystem calls in decision path.
- If external data is needed:
  - fetch in background workers
  - keep an immutable in-memory snapshot
  - decision path does O(1) lookup only
- No blocking waits in the decision path (including waiting for refresh or cache fill).
- Decision-path reads must be non-blocking; prefer immutable snapshots + atomic swap over lock-based
  in-place mutation.

### 4.3 Snapshot and Staleness Control
- Snapshot replacement must use atomic swap.
- Snapshot TTL/max-age must be bounded and monitored.
- Stale/empty snapshot behavior must follow explicit fail policy.
- Snapshot refresh must enforce:
  - **single-flight per key/snapshot** (at most one in-flight refresh per key)
  - **overall refresh concurrency must be bounded** (small fixed limit)
  - minimum refresh interval
  - exponential backoff + jitter on failures (avoid retry storms/tight loops)

### 4.4 Security Observability
- Count allow/deny with reason codes.
- Expose snapshot age/version and refresh success/failure.
- Expose fail-open/fail-closed activation counts.
- Emit security events without leaking credentials/secrets.
- Under spikes (e.g., deny storms), security logs MUST be sampled/rate-limited or coalesced to avoid
  becoming a new outage source.

---

## 5) Telemetry Module Characteristics (MUST)
This section applies to appenders and telemetry agents.

### 5.1 Default Fail Behavior
- Fail-open by default: telemetry failure must not break host behavior.
- Never block host request threads waiting for telemetry sinks.
- Any deviation from fail-open requires explicit approval and must be documented.

### 5.2 Fast Path and Offload
- Preferred pattern: `Fast Path -> Bounded Buffer -> Worker`.
- Host path should do minimal capture/filter/sampling and return.
- Worker performs I/O, encoding, retries, batching, and backoff.

### 5.3 Durability vs Latency Tradeoff
- Inline I/O is forbidden by default.
- If local audit durability is explicitly required, document what may run inline and why.
- Any permitted inline I/O must be bounded, measured, and have overload behavior.
- Any exceptional inline I/O on host threads must be strictly bounded and must fail fast:
  - hard timeout
  - strict overhead budget
  - no inline retries/backoff/waits
  - overload degradation that preserves host non-blocking behavior
- Secondary sink failures must not break primary host behavior.

### 5.4 Overload Policy
Recommended order:
1. drop low-priority telemetry
2. increase sampling
3. coalesce/aggregate
4. bypass sink (keep minimal counters)

---

## 6) Mode Model (REQUIRED)
Modules should define runtime modes:
- Telemetry modules: `NORMAL`, `DEGRADED`, `BYPASS`
- Security modules: `NORMAL`, `OBS_DEGRADED`, `OBS_BYPASS`

### 6.1 Mode Semantics
- Telemetry modules:
  - DEGRADED/BYPASS may reduce payload, sampling, sinks
- Security modules:
  - OBS_DEGRADED/OBS_BYPASS may reduce **observability cost only**
  - Decision execution itself must not be bypassed; only explicit fail policy can change outcomes.

### 6.2 Trigger Examples
- queue fill ratio threshold
- worker error spike
- self CPU/alloc threshold exceeded
- sink outage
- snapshot refresh failure (security modules)

---

## 7) Concurrency and Memory Rules (MUST)
- Prefer lock-free/low-contention primitives (`LongAdder`, `AtomicReference`, CAS).
- Avoid blocking locks on host hot paths.
- Use immutable snapshot + atomic swap for config/state.
- Worker threads: daemon, bounded pool, clean shutdown.
- No unbounded retention of host objects.
- For security modules, decision-path reads MUST NOT block on refresh or locks; prefer immutable
  snapshots + atomic swap.

---

## 8) Observability Baseline (MUST)

### 8.1 Common Metrics
- processed count
- dropped count (with reason, if applicable)
- error count
- mode
- queue depth/fill ratio (if queue exists)

Metrics MUST avoid high-cardinality labels (for example, raw principal/resource IDs). Use bounded
enums/reason codes and sampled/aggregated logs for high-cardinality details.

### 8.2 Telemetry Module Metrics
- enqueue/dequeue counts
- sink success/failure/retry counts
- end-to-end latency (queue wait + processing)

### 8.3 Security Module Metrics
- decision count (allow/deny + reason)
- decision latency (p50/p95/p99)
- cache/snapshot hit-miss and age
- refresh success/failure and fail-policy activation

---

## 9) Security Hygiene (MUST)
- Never log secrets or raw credentials/tokens (including in metrics/traces).
- Mask sensitive configuration in logs and diagnostics.
- Validate external config values defensively; keep safe effective config on invalid values.
- Document trust boundaries and threat assumptions for security modules.

---

## 10) Testing Requirements (REQUIRED)

### 10.1 Common
- failure isolation tests (`catch(Throwable)` paths)
- saturation tests (queue/cache full)
- regression tests for configuration reload/invalid config

### 10.2 Telemetry Modules
- sink outage/timeout tests without host-thread blocking
- drop/sampling/coalesce behavior under load
- performance comparison vs baseline (CPU/alloc/p99) for hot-path-impacting changes

### 10.3 Security Modules
- allow/deny correctness tests (positive/negative/boundary)
- stale/missing snapshot behavior per fail policy
- refresh failure/partial corruption tests
- throughput and p99 decision latency tests for decision-path-impacting changes

---

## 11) Release and Merge Gate (PR MUST include)
- [ ] module classification declared
- [ ] host entry points identified
- [ ] bounded resources verified (queue/cache/pool limits)
- [ ] overload behavior verified (no host blocking)
- [ ] required metrics exposed
- [ ] failure injection results attached
- [ ] baseline vs enabled performance impact attached when changes affect hot/decision/concurrency paths
      (e.g., host-path logic, locking/synchronization strategy, queue/cache/backpressure, parsing/
      serialization, retry/batching/timeout policy)
- [ ] security modules: fail policy declared (`FAIL_OPEN` or `FAIL_CLOSED`) with rationale
- [ ] security modules: security impact note attached
- [ ] telemetry modules: fail-open default preserved; any exception has explicit approval

---

## 12) Agent Execution Rules (STRICT)
When implementing or modifying code, the agent MUST:
1. classify module and path type (telemetry vs security-decision)
2. apply precedence from Section 1
3. keep host paths bounded and non-blocking
4. isolate heavy work off-path unless explicitly justified
5. maintain bounded resource limits and overload policy
6. preserve explicit fail policy semantics
7. add/maintain required tests and metrics
8. if request conflicts with this document, raise risk explicitly before merge
