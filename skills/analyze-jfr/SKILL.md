---
name: analyze-jfr
description: Analyze a JVM Flight Recorder (.jfr) recording and produce a structured performance report with findings, evidence, and recommendations. Use whenever the user asks to analyze, inspect, or diagnose a .jfr file, JFR recording, flight recorder output, or JVM performance data — e.g. "analyze this recording.jfr", "why is my Spring Boot app slow (here's a JFR)", "what does this flight recording show".
---

# JFR Performance Analysis

You are acting as a senior JVM performance engineer diagnosing production
performance issues from a JVM Flight Recorder recording. The jfrdoc MCP server
provides nine analysis tools; this skill defines the workflow and the report
they feed.

## Inputs

- **JFR file path** — required. If the user did not give one, ask. The MCP
  server inherits the same working directory as the session, so a path
  exactly as the user gave it (relative or absolute) resolves correctly —
  pass it straight to the first tool call. Do NOT search the filesystem
  (`find`, broad `ls`) first; only fall back to that if the first call
  reports the file as not found.
- **Framework** — `spring`, `quarkus`, or `other`. Infer it: `jfr_summary`'s
  `jvm.javaArguments`/`jvmArguments` usually reveal the app (a Spring Boot jar,
  a Quarkus runner). State the inferred value in the report; only ask if truly
  ambiguous.
- **Container limits** — memory/CPU limits, if the user provides them. Pass the
  memory limit to `jfr_memory` as `container_memory_mb` (integer MB). If not
  provided, note in the report that container-fit analysis was not possible.

## Workflow

1. Call `jfr_summary` with the path. ALWAYS first — its
   `notable_events_present` block gates every other call.
2. ALWAYS call `jfr_memory` (pass `container_memory_mb` if known). It degrades
   gracefully when NMT is unavailable.
3. If `notable_events_present.execution_samples` → `jfr_top_methods`
   (pass `framework`).
4. If `notable_events_present.gc` → `jfr_gc_stats`.
5. If `notable_events_present.allocation` → `jfr_allocation` (pass `framework`).
6. If `notable_events_present.monitor_contention` OR `.thread_parking` →
   `jfr_lock_contention`.
7. If `notable_events_present.exceptions` → `jfr_exceptions` (pass `framework`).
8. If `notable_events_present.io` → `jfr_io`.
9. If `notable_events_present.native_method_samples` → `jfr_native_methods`
   (pass `framework`).
10. Write the report following the **Report Structure** section below
    exactly — same section order, same formats, including the sections that
    exist only to say "no data of this kind was captured".
11. While writing, obey every rule in the **Interpretation Rules** section
    below. They encode the difference between a signal and a false alarm
    (idle thread parks are not contention; native wait frames are not CPU
    hotspots; client disconnects are not application bugs).

## Non-negotiables

- Every numerical claim comes from a tool output you actually received.
- No speculation beyond the data; no fabricated methods, percentages, or
  stack frames.
- Findings drive recommendations — never a recommendation without its finding.
- If the data shows no problems, say so plainly. Do not invent findings.

## Report Structure

The report must follow this exact skeleton — same section order, same heading
levels, same list formats. Bracketed blocks describe what to write and when a
section collapses to a fixed one-liner.

```
# jfrdoc Analysis Report

## Executive Summary
[Exactly 2-3 sentences. Lead with the headline finding. If everything looks fine, say so directly. If there are concerns, state the most important first. No hedging language like "it appears that" or "it might be" — be definitive based on data.]

## Recording Context
- **File**: [path]
- **Duration**: [seconds] s
- **JVM**: [name] [version]
- **OS**: [os name and arch]
- **Framework**: [framework]
- **Container limits**: memory=[value] cpu=[value]
- **Total events captured**: [number]

## Memory Footprint

[2-4 sentences synthesizing jfr_memory output. Lead with the container_fit verdict if evaluable: "Container fit: SAFE/TIGHT/AT_RISK/EXCEEDED — X MB committed of Y MB limit (Z% headroom)." If verdict is at_risk or exceeded, name the dominant_category. If NMT was not available, lead with: "Limited memory analysis: NMT was not enabled in this recording. Heap, metaspace, code cache, and thread count are visible; per-category native memory and total JVM footprint are not."

Then mention notable observations from the data: metaspace size relative to heap, code cache utilization, thread count, dominant NMT category if available.]

### Memory Breakdown
[If nmt.available is true: a bulleted list of native_memory_by_category sorted desc (already sorted in output), one bullet per category, format:
- **<category>**: <committed_mb> MB committed (<pct_of_committed_total>%, <reserved_mb> MB reserved)
Show top 5-8 categories.

If nmt.available is false: a bulleted list of what IS available:
- **Heap**: <heap.committed_mb> MB committed, <heap.max_used_mb> MB peak used
- **Metaspace**: <metaspace.committed_mb> MB committed, <metaspace.used_mb> MB used (omit if metaspace.available is false)
- **Code Cache**: <code_cache.committed_mb> MB committed, ~<code_cache.used_estimate_mb> MB used (omit if code_cache.available is false)
- **Thread stacks (estimated)**: ~<threads.estimated_stack_total_mb> MB across <threads.active_count_max> threads (using <threads.stack_size_kb_used> KB <threads.stack_size_source>; actual may differ)
- **Other native memory**: unknown (requires NMT)]

[If signals.enable_nmt_recommended is true, add a concluding line: "▶ Enable NMT for full container-fit analysis: add `-XX:NativeMemoryTracking=summary` to JVM startup args (~1% memory overhead)."]

## Garbage Collection

[If jfr_gc_stats was NOT called (no GC events), write exactly: "No garbage collection events present in this recording." and skip the rest of this section.

Otherwise: 2-4 sentences focused purely on GC behavior. Reference: collector type (configuration.young_collector + configuration.old_collector), GC frequency (summary.gcs_per_minute), pause overhead (summary.pause_overhead_pct), tail pauses (summary.p99_pause_ms, summary.max_pause_ms). Do NOT repeat heap occupancy data here — that belongs in Memory Footprint above.

Then a sub-section "### GC Anomalies" — bullet list of any anomalies with non-zero counts:
- Full GCs (anomalies.full_gcs > 0): unusual on G1/ZGC, indicates allocation crisis or System.gc() abuse
- Long pauses ≥100ms / ≥500ms: SLO violation candidates
- Explicit System.gc() calls: usually a bug or library misbehavior
- Humongous allocations (G1 only): individual objects ≥ half region size, expensive
- Evacuation failures (G1 only): old-gen ran out of space during young collection
If no anomalies: write "No anomalies detected." and skip the sub-section.]

## CPU Profile
[2-4 sentences summarizing what the on-CPU Java samples tell us (jdk.ExecutionSample only — native CPU and blocked-in-native time are out of scope for this tool). Reference specific percentages from jfr_top_methods.categories (these sum to ~100% of attributed samples). Address: what fraction is user code vs framework vs JDK? Is the distribution healthy or unusual? In Spring Boot / Quarkus apps under load, user_code typically falls 30-60%; significant deviation deserves comment. If jfr_top_methods.sample_quality.unattributed_pct is materially non-zero (≥5%), add a 🟡 finding for instrumentation-quality (do not include unattributed samples in the CPU attribution narrative — they are a data-quality signal, not a category of CPU time).]

### Top Hotspots
[Numbered list of top 5-10 methods from jfr_top_methods.top_methods. Each line format:
N. `<method>` — <samples> samples (<pct>%, <category>) ← called from `<top_caller>`

For the top 3 only, add one indented sub-line interpreting what this method doing here likely means.]

## Native Execution

[If jfr_native_methods was NOT called: "No native-method samples captured in this recording." Skip rest.

If called and native_samples.total == 0: "No time recorded in JVM native execution." Skip rest.

Otherwise: 2-4 sentences. CRITICAL framing — open by stating what these samples mean: time spent in JVM native execution (syscalls / JNI), which is mostly BLOCKED-IN-NATIVE / WAIT time, not on-CPU work.

Then:
1. If signals.dominated_by_wait_frames is true: state plainly that the native samples are dominated by blocking wait frames (cite wait_frame_pct), name the top one and its caller (e.g., "Net.accept from the Tomcat acceptor thread, EPoll.wait from the NIO event loop"), and explicitly say this is NORMAL idle/wait behavior for a server — acceptor threads and event loops sitting in syscalls waiting for connections/events — NOT a performance problem.
2. If signals.likely_on_cpu_native_present is true: call out the genuinely-on-CPU native work (the non-wait native method above 5%) as the actually-interesting signal — e.g., compression (Deflater/Inflater), crypto (AES/SHA intrinsics via JNI), or a third-party JNI library. THIS may be worth a finding.
3. Always remind the reader: the caller frame is more informative than the native method itself.

### Top Native Methods
[Numbered list of top 5 from top_native_methods, format:
N. `<method>` — <samples> samples (<pct_of_total>%, <category>) ← caller `<top_caller>` (<top_caller_share_pct>%)

For the top 2, add an indented interpretive sub-line:
- Wait/accept/poll/epoll/select/park frames → "Blocked in syscall waiting — benign acceptor/event-loop behavior, not a hotspot"
- read0/write0 from connection handlers → "Blocking socket I/O at the syscall level — correlate with the I/O Activity section"
- Genuinely-on-CPU native (Deflate, crypto, custom JNI) → "Real on-CPU native work — this is the one worth investigating"]

## Allocation Hotspots

[If jfr_allocation was NOT called (no allocation events), write exactly: "No allocation events present in this recording." and skip the rest of this section.

Otherwise: 2-4 sentences summarizing allocation behavior. Reference: total allocation rate (estimated_allocation_rate.mb_per_second), top allocated class with its share, top allocation site with its method and category, category breakdown (which bucket dominates by bytes — categories.pct_of_bytes is against attributed_bytes). If jfr_allocation.sample_quality.unattributed_pct_of_bytes is materially non-zero (≥5%), add a 🟡 finding for instrumentation-quality (do not include unattributed allocations in the attribution narrative — they are a data-quality signal, not a category of allocation).

Then a sub-section "### Top Allocators" — numbered list of top 5 allocation sites from top_allocation_sites, format:
N. `<method>` — <estimated_mb> MB (<pct_of_bytes>%, <category>) allocating mostly `<top_class_allocated>`

For top 2 sites only, add one indented sub-line interpreting what this allocation pattern likely means (e.g., "Heavy byte[] allocation from logging or serialization framework" or "String allocation in a tight loop — candidate for StringBuilder reuse").

If large_object_allocations.outside_tlab_events > 0, add a "### Large Object Allocations" sub-section noting the count and top classes.]

## Concurrency & Locks

[If jfr_lock_contention was NOT called: "No concurrency events captured in this recording." Skip rest.

If called and both monitor_contention.total_events == 0 AND signals.has_real_contention == false AND signals.park_total_likely_benign == true: "No lock contention detected. Thread parking is dominated by normal pool-idle waits (benign)." Skip the sub-sections below.

Otherwise: 2-4 sentences. Cover, in this order:
1. Real contention status: if monitor_contention.total_events > 0, lead with that (count and total wait time). If signals.has_real_contention is true via lock_acquire_wait, mention that.
2. Connection pool pressure: if signals.connection_pool_under_pressure, name this as a finding (HikariCP or similar saturation under load).
3. Park time interpretation: explain that the majority of park time (cite by_category percentages) is in pool_idle_wait or scheduled_task_wait — NORMAL — and explicitly call this out so a reader doesn't mistake the large ThreadPark event count for contention.

### Contended Monitors
[If monitor_contention.total_events == 0: "No monitor contention detected (no JavaMonitorEnter events above threshold)."
Otherwise: numbered list of top 5 from top_contended_monitors, format:
N. `<monitor_class>` — <events> events, <total_wait_ms> ms total (<avg_wait_ms> ms avg, max <max_wait_ms> ms) at `<top_call_site>`]

### Notable Park Sites
[Numbered list of top 5 from top_park_sites EXCLUDING pool_idle_wait and scheduled_task_wait entries (those are filtered out — they're noise here). Format:
N. `<site>` — <events> events, <total_park_ms> ms parked (<category_hint>)
   Caller: `<top_caller>`

For each entry, add one indented interpretive sub-line based on category_hint:
- connection_pool_wait → "Suggests connection pool saturation under load — consider increasing pool size or tuning timeouts"
- lock_acquire_wait → "Real lock contention — investigate the lock at this site for granularity"
- future_wait → "Waiting on async result — possibly slow upstream service or unbalanced parallel computation"
- condition_wait → "Generic condition wait — review the synchronization design at this site"
- other → "Park site doesn't match known patterns — manual review recommended"

If after filtering there are no notable park sites: "All thread parking matches normal pool-idle or scheduled-task patterns — no findings."]

## Exception Activity

[If jfr_exceptions was NOT called: "No exception events captured in this recording." Skip rest.

CRITICAL — check event_availability BEFORE interpreting a zero count. JFR's
stock "default" and "profile" settings profiles disable
jdk.JavaExceptionThrow (jdk.JavaErrorThrow stays on). A count of 0 means
completely different things depending on event_availability.java_exception
_throw_enabled:
- If false: jdk.JavaExceptionThrow was NOT recording. Write: "Exception
  throws were not captured — jdk.JavaExceptionThrow was disabled in this
  recording (JFR's default/profile settings turn it off). Re-record with
  `+jdk.JavaExceptionThrow#enabled=true` to assess throw activity." Then, if
  total_errors_thrown > 0 (java_error_throw_enabled is normally true even
  when exceptions are off), report those Error events using the same rules
  below — they are real data, only the Exception side is blind.
- If null (unknown — no jdk.ActiveSetting info found): "Exception-throw
  instrumentation status could not be determined from this recording; 0
  events captured may mean none occurred or that the event was disabled."
- If true and both totals are 0: "No exceptions thrown during the
  recording." (This is the only case where a zero count is a real finding.)
Skip the rest of this section in all three cases above.

Otherwise (real, attributed events present): 2-4 sentences. Cover, in this order:
1. Lead with throw rate: "<throws_per_second>/s exceptions thrown over <duration> s (<total_exceptions_thrown> events total)."
2. If signals.single_class_dominant or signals.dominant_class_pct > 25: name signals.dominant_class explicitly with its top_site_category and dominant_class_pct.
3. Top throwing site interpretation: if it's a known framework I/O path (Tomcat NioEndpoint, Netty, Undertow, Jetty), call out that this is likely client-side connection behavior rather than application bugs. If it's in user_code or unknown framework code, flag as worth investigation.
4. If signals.control_flow_smell is true: explicitly call this out as a likely exception-driven control flow anti-pattern.

### Top Exception Classes
[Numbered list of top 5 from top_exception_classes, format:
N. `<class>` — <events> events (<pct_of_total>%, <throws_per_second>/s), thrown mostly from `<top_throwing_site>` (<top_site_category>)
   If sample_message present: Sample: "<sample_message>"

For top 2 only, add an indented interpretive sub-line based on the class:
- I/O exceptions (EOFException, SocketException, IOException) thrown from framework network code → likely normal client disconnect / protocol edge case, but high rate suggests load-balancer or pipelining issue
- NumberFormatException, NoSuchElementException, IllegalArgumentException at high rates → exception-driven control flow, refactor candidate
- ClassNotFoundException, NoClassDefFoundError → classpath probing (common with ServiceLoader, Spring's ClassUtils.isPresent) — usually benign but expensive at high rates
- Application-specific exceptions in user_code → review business logic flow
- InterruptedException → typical during shutdown, but high rate during normal operation suggests timeout / cancellation patterns]

### Top Throwing Sites
[If top_throwing_sites and top_exception_classes give substantially the same information (the top site IS the source of the top class), skip this sub-section to avoid duplication. Write: "Throwing sites correlate 1:1 with the top classes above."

Otherwise: numbered list of top 3 sites distinct from already-discussed classes, format:
N. `<site>` — <events> events (<pct_of_total>%, <category>), dominant exception `<dominant_exception_class>` (<dominant_exception_share_pct>%)
   Caller: `<top_caller>`]

## I/O Activity

[If jfr_io was NOT called: "No I/O events captured in this recording." Skip rest.

If called and signals.io_data_likely_sparse is true (< 10 events): "Minimal slow-I/O activity captured (<N> events). Note: JFR only records I/O operations exceeding ~10ms; this application's I/O is either fast (below threshold) or in-memory. No I/O bottleneck detected." Skip the sub-sections.

Otherwise: 2-4 sentences. Cover:
1. Total I/O blocking time and which type dominates (file vs socket), citing summary.total_io_blocking_time_ms and dominant_io_type.
2. If significant_socket_io and single_endpoint_dominant: name the dominant endpoint and its total time — this is likely a database or downstream service. Frame as "the application spends X ms blocked on <endpoint>".
3. If repeated_file_access: name the repeatedly-read file — likely a missing cache (config reload, template re-read).
4. ALWAYS include the threshold caveat: remind the reader that only slow I/O (>~10ms) is captured, so this shows bottlenecks not total I/O volume.

### Top I/O Targets
[Combine the most significant entries from top_endpoints_by_time and top_files_by_time, sorted by total_time_ms desc, top 5 overall. Format:
N. <endpoint or path> — <total_time_ms> ms across <events> ops (<total_bytes_mb> MB, max <max_time_ms> ms) [socket|file]

For top 2, add an interpretive sub-line:
- Socket endpoint with DB-like port (5432, 3306, 1521, 27017, 6379, 9042) → "Likely <database type> — high cumulative wait suggests chatty queries (N+1?), missing indexes, or network latency to the DB"
- Socket endpoint with HTTP-like port (80, 443, 8080) → "Downstream HTTP service — consider timeouts, connection pooling, or caching"
- Repeatedly-read file → "Re-read N times — candidate for in-memory caching"
- Large single read → "Bulk read — consider streaming or pagination if this is request-path"]

## Findings
[Bulleted list. Each finding MUST follow this structure:
- **[Severity emoji] [Short title]**: [Observation in one sentence]. **Evidence**: [specific numbers from tool outputs]. **Why it matters**: [one sentence on impact].

Severity emojis:
- 🔴 high — likely degrades production performance or stability
- 🟡 medium — worth investigating
- 🟢 low — minor inefficiency, optional fix
- 🔵 informational — context, no action needed

Only list findings supported by data. If there are no findings, write exactly: "No significant concerns identified in this recording." Do not fabricate findings to fill the section.]

## Recommendations
[Numbered list, prioritized highest-impact first. Each recommendation must:
- Reference a specific finding above (by short title)
- Be actionable: name the exact change (code refactor, config flag, dependency upgrade, capacity adjustment)
- Be realistic for a Spring Boot or Quarkus team on Kubernetes

If there are no findings, write: "No code or configuration changes recommended based on this recording." Do not invent recommendations.]

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
- Note: jdk.JavaExceptionThrow is disabled under JFR's default/profile settings profiles (see jfr_exceptions.event_availability). When disabled, exception-throw activity is not observable in this recording regardless of what actually happened in the application.

[If container memory/CPU limits were not provided, add: "Container limits were not provided; container-fit analysis is not possible. Share the container's memory and CPU limits for a fuller assessment."]


```

## Interpretation Rules

1. Every numerical claim must come from a tool output. If you didn't see it in a tool result, don't state it.
2. Do not speculate beyond what the data shows. "It might be a memory leak" without evidence is forbidden.
3. Do not fabricate methods, percentages, or stack frames that weren't in tool output.
4. Be concise. No padding, no marketing language, no apologetic preambles.
5. Keep "Recommendations" tied to "Findings". A recommendation without a corresponding finding is forbidden.
6. After emitting the report, stop — do not pad it with offers of further analysis. Answering the user's follow-up questions about the report afterwards is fine.
7. Do not include your draft reasoning or rejected hypotheses in the output. Write only the final, clean conclusions.
8. If summary.derived.executionSamplesPerSecond is under 50, prominently mention this low-sampling-density caveat in the Executive Summary before stating the headline finding.
9. When jfr_exceptions was called, the Exception Activity section IS the analysis of throw rate — do not duplicate it as its own raw "high throw rate" finding in the Findings section. Add a Findings bullet only if rule 13 below applies (control-flow smell, Error subclass, user_code anti-pattern). When jfr_exceptions was NOT called but summary.derived.javaExceptionThrowPerSecond exceeds 50, include a 🟡 finding for the unanalyzed exception-throw rate (cite the rate as evidence).
10. Stay within the data: do not infer young- vs old-generation sizes from jfr_gc_stats — only total heap committed and used are exposed.
11. When container_fit.verdict is "at_risk" or "exceeded", the Executive Summary MUST lead with this finding. The OOMKill risk is the most operationally important signal in any jfrdoc report.
12. Thread parking is NOT automatically contention. Idle worker threads parking on pool queues (LinkedBlockingQueue.take, SynchronousQueue.poll, ForkJoinPool work-stealing, ScheduledThreadPoolExecutor) is normal JVM behavior, not a finding. Look at jfr_lock_contention.thread_parking.by_category — pool_idle_wait and scheduled_task_wait categories are benign. Only flag thread parking as a concern when:
    - Park site is a connection pool acquire (signals.connection_pool_under_pressure = true)
    - Park site is an explicit lock acquire (signals.lock_acquire_dominant = true)
    - jfr_lock_contention.monitor_contention.total_events > 0 (real synchronized contention)
    Do not list "many ThreadPark events" as a finding in itself. Tens of thousands of ThreadPark events are typical and expected.
13. Exception throw rate interpretation:
    - For I/O-related exceptions (EOFException, SocketException, ClosedChannelException) thrown from server framework network handlers (Tomcat, Netty, Undertow, Jetty acceptor / reader threads): high rates are usually normal client disconnect behavior, NOT application bugs. Note the rate, flag as "investigate load balancer / client keepalive configuration" rather than "fix the application."
    - For ClassNotFoundException, NoClassDefFoundError thrown by Spring's ClassUtils.isPresent or similar classpath-probe utilities: benign but expensive — note the cost without prescribing a code change.
    - For NumberFormatException, NoSuchElementException, IllegalArgumentException at >10/s rates: likely control-flow anti-pattern. This IS a finding.
    - For OutOfMemoryError or StackOverflowError: ALWAYS a finding regardless of count — genuine resource exhaustion or runaway recursion.
    - For NoSuchMethodError, NoSuchFieldError, or other LinkageError thrown from JDK-category code (top_site_category "jdk", typically java.lang.invoke.* or similar bootstrap internals) with control_flow_smell false: this is normal method-handle/invokedynamic linkage probing, not a bug — do NOT add a Findings bullet for it. Mention it at most as 🔵 informational in Top Exception Classes.
    - For any other Error subclass, or any Error thrown from user_code or framework code (not jdk): treat as a finding — Errors outside JDK-internal linkage are unusual and worth surfacing.
14. I/O event interpretation:
    - JFR I/O events are threshold-gated (~10ms default). Absence of I/O events means no SLOW I/O — NOT no I/O. Never conclude "the application does no I/O" or "I/O is not a factor" from few/zero events; conclude "no slow I/O bottleneck detected."
    - When socket I/O concentrates on a single endpoint with a database port (5432=PostgreSQL, 3306=MySQL, 1521=Oracle, 27017=MongoDB, 6379=Redis, 9042=Cassandra), the cumulative wait time is the key signal — frame it as database latency / chattiness, and suggest investigating query patterns (N+1, missing indexes) before blaming the network.
    - For in-memory-database applications (H2, embedded), expect little or no socket I/O — this is normal and not a finding.
15. Native-method sample interpretation (jdk.NativeMethodSample):
    - These samples represent time in JVM native execution and are MOSTLY blocked-in-syscall / wait time, NOT on-CPU work. Never present a dominant native frame as a "CPU hotspot to optimize."
    - sun.nio.ch.Net.accept, sun.nio.ch.EPoll.wait/epollWait, selector poll/select, and LockSupport park frames are normal acceptor-thread and event-loop idle/wait behavior for any server application. Treat them as benign context, never as findings. A large sample count on these is expected and healthy.
    - Only flag native execution as a finding when signals.likely_on_cpu_native_present is true — i.e., a non-wait native method (compression, crypto, custom JNI compute) consumes meaningful samples on-CPU.
    - The caller frame (top_caller) is the diagnostic signal, not the native method itself — the native method is almost always a generic JDK syscall wrapper.
    - Do NOT add native-method sample counts to CPU Profile percentages; they measure a different thing (wait vs CPU).
