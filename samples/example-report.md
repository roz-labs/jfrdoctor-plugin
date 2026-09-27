<!-- Generated end-to-end by jfrdoc 0.4.0 (2026-09-27) with Claude Code, from a
     recording made by samples/gen-sample.sh (multi-threaded: CPU-bound recursion,
     allocation churn, synchronized-monitor contention, blocking-queue parking, a
     slow loopback socket exchange, and exceptions with jdk.JavaExceptionThrow
     explicitly enabled), using the prompt:
     analyze samples/sample.jfr — it's a plain Java batch app, container memory limit is 1000Mi, no CPU limit
     The skill ran all nine tools; the report below is the model's output,
     committed verbatim. Every number in it comes from those tools' output. -->
# jfrdoc Analysis Report

## Executive Summary
Memory fits comfortably in the 1000Mi container limit — 308.6 MB committed (69.1% headroom) — with no GC or memory pressure of any kind. This recording is not organic production traffic but a synthetic JFR-event generator (main class `JfrLoad`, with methods named `cpuBurnLoop`, `exceptionLoop`, `lockContentionLoop`, `producerLoop`/`consumerLoop`, and exception messages like `"synthetic-2"`), so most signals below reflect intentional test behavior rather than defects. The one operationally relevant fact for deployment: the workload is 99.5% CPU-bound in a single recursive method, and with no CPU limit configured, it can consume all available CPU on its node.

## Recording Context
- **File**: samples/sample.jfr
- **Duration**: 29.998 s
- **JVM**: OpenJDK 64-Bit Server VM 21.0.10+7-Ubuntu-124.04
- **OS**: linux-amd64
- **Framework**: other (plain Java app; no Spring/Quarkus signals in JVM flags or main class)
- **Container limits**: memory=1000Mi cpu=none
- **Total events captured**: 24533

## Memory Footprint
Container fit: SAFE — 308.6 MB committed of 1000 MB limit (69.1% headroom). The dominant category is Java Heap, but heap itself is tiny relative to what's committed (216 MB committed, only 19.1 MB peak used — 8.8% utilization), so the app is far from any container-fit risk. Metaspace and code cache are both minimal (4.8 MB and 240 MB committed respectively, with only ~5.5 MB of code cache actually used), and thread count is low (19 active threads, ~19 MB estimated stack). NMT was enabled, giving a full per-category breakdown.

### Memory Breakdown
- **Java Heap**: 216 MB committed (70%, 3422 MB reserved)
- **GC**: 45.2 MB committed (14.6%, 107.8 MB reserved)
- **Tracing**: 16.9 MB committed (5.5%, 16.9 MB reserved)
- **Shared class space**: 12.6 MB committed (4.1%, 16 MB reserved)
- **Code**: 8.6 MB committed (2.8%, 242.3 MB reserved)
- **Metaspace**: 4.4 MB committed (1.4%, 64 MB reserved)
- **Symbol**: 1.5 MB committed (0.5%, 1.5 MB reserved)

## Garbage Collection
G1 (G1New/G1Old) ran a single young collection in the whole 30 s recording — 2 GCs/minute — with a 4.55 ms pause (p50 = p95 = p99 = max, since there's only one). Pause overhead is 0.02% of wall-clock time, effectively negligible.

No anomalies detected.

## CPU Profile
On-CPU Java time is almost entirely user code: 99.7% user_code vs. 0.3% jdk, with 0% unattributed (clean instrumentation). This is not a healthy "typical service" distribution because it isn't a service under mixed load — it's one hot method dominating everything.

### Top Hotspots
1. `JfrLoad.fibonacci:100` — 1864 samples (99.5%, user_code) ← called from `JfrLoad.fibonacci`
   Self-recursive Fibonacci consuming essentially all on-CPU time — this is the app's intentional CPU-burn workload (invoked via `cpuBurnLoop`, which also appears lower in the list).
2. `java.lang.Thread.beforeSleep:456` — 1 sample (0.1%, jdk) ← `java.lang.Thread.sleep`
   Negligible — JFR's own sleep-event instrumentation, not application work.
3. `JfrLoad.ioServerLoop:166` — 1 sample (0.1%, user_code) ← `JfrLoad.lambda$main$5`
   Negligible sample count; this is the app's local socket-server loop (see I/O Activity below).
4. `JfrLoad.consumerLoop:90` — 1 sample (0.1%, user_code) ← `JfrLoad.lambda$main$1`
5. `java.lang.invoke.LambdaForm$MH...invoke` — 1 sample (0.1%, jdk) ← `LambdaForm$MH...invoke`

## Native Execution
These are JVM native-execution samples — mostly blocked-in-syscall/wait time, not on-CPU work. Samples here are dominated by wait frames (99.7%): the top native method, `sun.nio.ch.Net.poll` (99.7%), is called from `sun.nio.ch.NioSocketImpl.park` — a socket thread parked waiting for I/O, which is normal, benign event-loop/read-wait behavior tied to the socket-read activity described below, not a CPU hotspot. No genuinely on-CPU native work was detected (`likely_on_cpu_native_present` is false).

### Top Native Methods
1. `sun.nio.ch.Net.poll` — 1474 samples (99.7%, jdk) ← caller `sun.nio.ch.NioSocketImpl.park` (100%)
   Blocked in syscall waiting — benign socket-wait behavior, not a hotspot.
2. `java.lang.Thread.sleep0` — 2 samples (0.1%, jdk) ← caller `java.lang.Thread.sleep` (100%)
   Ordinary sleep — benign.
3. `sun.nio.ch.SocketDispatcher.write0` — 2 samples (0.1%, jdk) ← caller `sun.nio.ch.SocketDispatcher.write` (100%)
4. `sun.nio.ch.NativeThread.current0` — 1 sample (0.1%, jdk) ← caller `sun.nio.ch.NativeThread.current` (100%)

## Allocation Hotspots
Allocation rate is modest at 3.1 MB/s (92.8 MB total over 30 s), with 0% unattributed bytes. `byte[]` dominates by class (75.1% of bytes), and the top allocation site is `JfrLoad.producerLoop:77` at 74.4% of bytes (69 MB). By category, user_code accounts for 74.4% of attributed bytes vs. 25.6% jdk (driven mostly by a single large `ConcurrentHashMap` table-init event).

### Top Allocators
1. `JfrLoad.producerLoop:77` — 69 MB (74.4%, user_code) allocating mostly `byte[]`
   Heavy byte[] allocation from the producer loop — consistent with simulating message/payload production for the JFR-load generator.
2. `java.util.concurrent.ConcurrentHashMap.initTable:2301` — 21.5 MB (23.1%, jdk) allocating mostly `ConcurrentHashMap$Node[]`
   A single one-time table-initialization event (1 sample) rather than a recurring allocation pattern.
3. `java.util.HashMap.newNode:1909` — 0.3 MB (0.3%, jdk) allocating `HashMap$Node`
4. `java.lang.Thread.beforeSleep:458` — 0.3 MB (0.3%, jdk) allocating `ThreadSleepEvent`
5. `java.lang.Throwable.fillInStackTrace` — 0.3 MB (0.3%, jdk) allocating `Object[]`

## Concurrency & Locks
Two real `JavaMonitorEnter` contention events were recorded, totaling 65.7 ms of wait time — genuine, if very small, monitor contention (`has_real_contention` is true). No connection-pool pressure was detected. Thread parking is dominated (100% of 24,485 ms parked, 1446 events) by the `condition_wait` category on `LockSupport.parkNanos` via `AbstractQueuedSynchronizer$ConditionObject.awaitNanos` — unlike typical pool-idle/scheduled-task parking, the tool explicitly flags this as **not** classified as benign (`park_total_likely_benign` is false), so it's called out below rather than dismissed as routine.

### Contended Monitors
1. `java.lang.Object` — 1 event, 54.9 ms total (54.9 ms avg, max 54.9 ms) at `JfrLoad.lockContentionLoop:122`
2. `jdk.jfr.internal.PlatformRecorder` — 1 event, 10.8 ms total (10.8 ms avg, max 10.8 ms) at `jdk.jfr.internal.PlatformRecorder.periodicTask:514`

### Notable Park Sites
1. `java.util.concurrent.locks.LockSupport.parkNanos:269` — 1446 events, 24485.3 ms parked (condition_wait)
   Caller: `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos:1797`
   Generic condition wait — review the synchronization design at this site (likely the producer/consumer loop coordination, given the app's `producerLoop`/`consumerLoop` methods, but not automatically classified as benign here).

## Exception Activity
17.7/s exceptions thrown over 29.998 s (531 events total, plus 8 Error events). `NumberFormatException` is co-dominant at 36.7% (tied with `IllegalStateException`, also 36.7%), thrown from JDK's `NumberFormatException.forInputString` via `Integer.parseInt`. The top user-code throwing site, `JfrLoad.exceptionLoop:141`, carries an explicitly-labeled sample message (`"synthetic-2"`), and the top JDK site's sample message (`"For input string: \"not-a-number-0\""`) is likewise clearly synthetic test data rather than organic bad input. No control-flow-anti-pattern smell was detected (`control_flow_smell` is false).

### Top Exception Classes
1. `java.lang.NumberFormatException` — 198 events (36.7%, 6.6/s), thrown mostly from `java.lang.NumberFormatException.forInputString:67` (jdk)
   Sample: "For input string: \"not-a-number-0\""
   The literal placeholder string indicates synthetic test data generation, not a real production parsing defect.
2. `java.lang.IllegalStateException` — 198 events (36.7%, 6.6/s), thrown mostly from `JfrLoad.exceptionLoop:141` (user_code)
   Sample: "synthetic-2"
   Explicitly labeled synthetic — intentionally generated exception traffic from the workload generator, not an application defect.
3. `java.lang.ArrayIndexOutOfBoundsException` — 119 events (22.1%, 4/s), thrown mostly from `JfrLoad.exceptionLoop:140` (user_code)
   Sample: "Index 4 out of bounds for length 2"
4. `java.lang.NoSuchMethodError` — 24 events (4.5%, 0.8/s), thrown mostly from `java.lang.invoke.MethodHandleNatives.resolve` (jdk) 🔵
   Normal method-handle/invokedynamic linkage probing from JDK internals, not a bug.

### Top Throwing Sites
Throwing sites correlate 1:1 with the top classes above.

## I/O Activity
Total I/O blocking time is 29,859.4 ms, entirely socket reads (no file I/O). All of it concentrates on a single endpoint, `localhost` (1918 read events, avg 15.6 ms, max 72.7 ms) — given the app's own `ioServerLoop` method, this is almost certainly the application's local/loopback socket simulation rather than an external dependency. Only slow I/O (>~10ms) is captured here, so this reflects blocking hotspots, not total I/O volume.

### Top I/O Targets
1. `localhost` — 29859.4 ms across 1918 ops (0 MB recorded, max 72.7 ms) [socket]
   No port information is exposed (address masked), and the app's own `ioServerLoop` suggests this is self-contained loopback I/O rather than a downstream database or service dependency.

## Findings
- **🟡 CPU-bound workload with no CPU limit**: 99.5% of on-CPU samples are in a single self-recursive method, `JfrLoad.fibonacci:100`, and the container has no CPU limit configured. **Evidence**: `jfr_top_methods` — 1864/1874 samples (99.5%) in `JfrLoad.fibonacci`; user-supplied container config has no CPU limit. **Why it matters**: an uncapped, fully CPU-bound job can monopolize all available CPU on its node, degrading co-located workloads.
- **🟡 Thread parking flagged as non-benign**: 1446 `LockSupport.parkNanos` events (24,485 ms total, spanning most of the 30 s recording) fall entirely in the `condition_wait` category, and the tool explicitly does not classify this as benign pool-idle waiting. **Evidence**: `jfr_lock_contention.thread_parking` — 100% of park time in `condition_wait`; `signals.park_total_likely_benign = false`. **Why it matters**: this volume of parking should be confirmed as expected producer/consumer coordination rather than a symptom of a stalled consumer or unbalanced queue.
- **🔵 Recording is a synthetic JFR-event generator, not production traffic**: the main class (`JfrLoad`) and method names (`cpuBurnLoop`, `exceptionLoop`, `lockContentionLoop`, `producerLoop`, `consumerLoop`, `ioServerLoop`) plus literal exception messages (`"synthetic-2"`, `"not-a-number-0"`) indicate this recording exercises JFR event types deliberately rather than capturing organic application behavior. **Evidence**: `jvm.mainClassOrJar = "JfrLoad"`; method/site names throughout `jfr_top_methods`, `jfr_allocation`, `jfr_lock_contention`, `jfr_exceptions`; exception `sample_message` values. **Why it matters**: the exceptions, contention, and allocation patterns above should be read as intentional test coverage, not defects to fix.
- **🔵 NoSuchMethodError from method-handle linkage probing**: 24 `NoSuchMethodError` events (4.5%) originate from `java.lang.invoke.MethodHandleNatives.resolve`, JDK-internal invokedynamic linkage machinery. **Evidence**: `jfr_exceptions.top_exception_classes` rank 4; `top_site_category = "jdk"`; `signals.control_flow_smell = false`. **Why it matters**: this is normal JDK bootstrap behavior, not an application error — noted for completeness only.

## Recommendations
1. **(CPU-bound workload with no CPU limit)** Set an explicit Kubernetes CPU request/limit for this container so the recursive-Fibonacci-driven CPU burn cannot consume unbounded node CPU when co-scheduled with other workloads.
2. **(Thread parking flagged as non-benign)** Confirm that the 1446 `ConditionObject.awaitNanos` parks correspond to expected producer/consumer blocking-queue coordination (`producerLoop`/`consumerLoop`); if this pattern appears in a non-synthetic recording of the same app, re-check for consumer stalls or queue imbalance.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
- Note: jdk.JavaExceptionThrow is disabled under JFR's default/profile settings profiles (not the case here — it was enabled in this recording)