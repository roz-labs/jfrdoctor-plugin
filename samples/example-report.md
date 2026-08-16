<!-- Generated end-to-end by the jfrdoc plugin (2026-08-16), against the rewritten
     samples/gen-sample.sh workload (multi-threaded: CPU-bound recursion, allocation
     churn, synchronized-monitor contention, blocking-queue parking, a slow loopback
     socket exchange, and exceptions with jdk.JavaExceptionThrow explicitly enabled):
     claude -p "analyze samples/sample.jfr — it's a plain Java batch app, container memory limit is 1000Mi, no CPU limit"
     Committed verbatim as UX evidence. -->
# jfrdoc Analysis Report

## Executive Summary
Memory footprint is well within budget (309 MB committed of the 1000 MB container limit, 69% headroom) and GC overhead is negligible (0.02% pause time), so there is no OOMKill or GC-latency risk in this recording. The recording's characteristic behavior is a CPU profile almost entirely consumed by one recursive method (`JfrLoad.fibonacci`, 99.9% of samples), near-total-duration blocking on a single loopback socket endpoint, and a steady stream of exceptions whose method names and messages (`exceptionLoop`, `"synthetic-2"`, `"not-a-number-0"`) indicate this is a synthetic/load-generation workload rather than typical batch-job traffic. None of these rise to a stability or capacity concern at the observed scale.

## Recording Context
- **File**: samples/sample.jfr
- **Duration**: 30.0 s
- **JVM**: OpenJDK 64-Bit Server VM 21.0.10+7-Ubuntu-124.04 (linux-amd64)
- **OS**: linux-amd64
- **Framework**: other (no Spring/Quarkus signals detected; main class appears to be `JfrLoad`)
- **Container limits**: memory=1000 MB, cpu=none
- **Total events captured**: 24,669

## Memory Footprint

Container fit: **SAFE** — 308.7 MB committed of 1000 MB limit (69.1% headroom). The dominant category is Java Heap, which is expected. NMT was enabled (`-XX:NativeMemoryTracking=summary`) so this is a full per-category breakdown, not a degraded estimate. Heap is committed at 216 MB but only 19.1 MB peak used — very light actual usage for a 30 s window. Metaspace and code cache are both tiny and far from any ceiling; the 19 active threads (8 daemon) contribute an estimated ~19 MB of stack memory.

### Memory Breakdown
- **Java Heap**: 216 MB committed (70%, 3416 MB reserved)
- **GC**: 45.2 MB committed (14.6%, 107.7 MB reserved)
- **Tracing**: 16.9 MB committed (5.5%, 16.9 MB reserved)
- **Shared class space**: 12.6 MB committed (4.1%, 16 MB reserved)
- **Code**: 8.7 MB committed (2.8%, 242.3 MB reserved)
- **Metaspace**: 4.4 MB committed (1.4%, 64 MB reserved)
- **Symbol**: 1.5 MB committed (0.5%, 1.5 MB reserved)
- **Arena Chunk**: 0.8 MB committed (0.3%, 0.8 MB reserved)

## Garbage Collection

The recording uses G1 (G1New/G1Old), 4 parallel GC threads, 1 concurrent GC thread. Exactly one young GC occurred in the 30 s window (2/min), a G1 Evacuation Pause lasting 4.51 ms — pause overhead is 0.02% of the recording, effectively zero.

### GC Anomalies
No anomalies detected.

## CPU Profile
On-CPU Java time is almost entirely user code: 99.9% user_code, 0% framework, 0.1% jdk, with 0% unattributed samples (full attribution quality). For a plain, framework-less batch process this concentration is expected and not itself a red flag — it simply means nearly all measured CPU time is spent inside the application's own recursive computation.

### Top Hotspots
1. `JfrLoad.fibonacci:100` — 1744 samples (99.9%, user_code) ← called from `JfrLoad.fibonacci`
   - Self-recursive Fibonacci computation accounts for essentially all on-CPU time — a classic CPU-bound recursive workload; naive recursive Fibonacci is exponential-time, so if this reflects real production logic (not a synthetic load generator) it is a strong candidate for memoization.
2. `java.lang.invoke.Invokers$Holder.linkToTargetMethod` — 1 sample (0.1%, jdk) ← called from `JfrLoad.consumerLoop`
   - Single-sample method-handle linkage overhead, negligible.
3. `jdk.internal.jimage.ImageStringsReader.unmaskedHashCode:90` — 1 sample (0.1%, jdk) ← called from `jdk.internal.jimage.ImageStringsReader.hashCode`

## Native Execution

1486 `jdk.NativeMethodSample` events, 100% JDK-category. These represent time in JVM native execution — mostly blocked-in-syscall/wait time, not on-CPU work. Samples are dominated by wait frames (99.9%): the top native method is `sun.nio.ch.Net.poll` called from `sun.nio.ch.NioSocketImpl.park`, i.e., a thread blocked in a poll syscall waiting on socket I/O. This is normal blocking-socket-read behavior, not a CPU hotspot — no genuinely on-CPU native work was detected in this recording.

### Top Native Methods
1. `sun.nio.ch.Net.poll` — 1485 samples (99.9%, jdk) ← caller `sun.nio.ch.NioSocketImpl.park` (100%)
   - Blocked in a poll syscall waiting on socket I/O — benign wait tied to blocking socket reads, correlates with the I/O Activity section below, not a hotspot.
2. `sun.nio.ch.SocketDispatcher.write0` — 1 sample (0.1%, jdk) ← caller `sun.nio.ch.SocketDispatcher.write` (100%)
   - Single blocking write syscall sample, negligible.

## Allocation Hotspots

Estimated allocation rate is 3.1 MB/s (93.4 MB total over 30 s), with full attribution (0% unattributed bytes). `byte[]` dominates by class at 75% of bytes. The top allocation site is `JfrLoad.producerLoop:77` at 74.3% of bytes, allocating `byte[]`. By category, user_code accounts for 74.3% of bytes and jdk 25.7% — the jdk share is almost entirely a single one-time `ConcurrentHashMap` table initialization.

### Top Allocators
1. `JfrLoad.producerLoop:77` — 69.4 MB (74.3%, user_code) allocating mostly `byte[]`
   - Sustained byte[] allocation from a producer loop — consistent with a workload generating byte buffers at a steady rate; GC pause overhead is currently negligible (0.02%) so this isn't urgent, but buffer pooling would reduce allocation pressure if this pattern holds at higher throughput.
2. `java.util.concurrent.ConcurrentHashMap.initTable:2301` — 21.6 MB (23.1%, jdk) allocating mostly `ConcurrentHashMap$Node[]`
   - A single large one-time map table allocation, likely startup initialization of a pre-sized map — not a recurring cost.

## Concurrency & Locks

Real synchronized-monitor contention was observed but is minimal: 1 `JavaMonitorEnter` event totaling 10.1 ms wait, at `JfrLoad.lockContentionLoop:122`. Separately, 3 `Object.wait0` events accumulated 49,984.3 ms of total wait time (avg 16,661.4 ms each) — meaning some threads spent roughly half the recording blocked in `Object.wait`. Thread parking is dominated (99.6% of park time, 22,058.7 ms) by the `condition_wait` category via `LockSupport.parkNanos` called from `AbstractQueuedSynchronizer$ConditionObject.awaitNanos` — the tool explicitly flags this as **not** benign pool-idle waiting (`park_total_likely_benign: false`), so it should not be dismissed as routine idle-pool behavior.

### Contended Monitors
1. `java.lang.Object` — 1 event, 10.1 ms total (10.1 ms avg, max 10.1 ms) at `JfrLoad.lockContentionLoop:122`

### Notable Park Sites
1. `java.util.concurrent.locks.LockSupport.parkNanos:269` — 1478 events, 22,141.8 ms parked (condition_wait)
   Caller: `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos:1797`
   - Generic condition wait — review the synchronization/coordination design at this site (e.g., producer/consumer hand-off) to confirm this reflects expected work-queue draining rather than avoidable blocking.

## Exception Activity

Exception-throw instrumentation was enabled (`java_exception_throw_enabled: true`), so the following counts are real. 17.8/s exceptions thrown over 30 s (534 exception events + 8 error events). `NumberFormatException` is the single largest class at 36.9% (above the 25% dominance threshold). The top throwing site is JDK-internal (`Integer.parseInt` → `NumberFormatException.forInputString`), not framework network code; the sample message (`"not-a-number-0"`) and the two user_code sites (`JfrLoad.exceptionLoop`) with messages like `"synthetic-2"` indicate this recording is deliberately exercising exception-throwing paths rather than surfacing genuine application bugs. The tool's own control-flow-smell signal is false, consistent with this reading.

### Top Exception Classes
1. `java.lang.NumberFormatException` — 200 events (36.9%, 6.7/s), thrown mostly from `java.lang.NumberFormatException.forInputString:67` (jdk)
   Sample: "For input string: \"not-a-number-0\""
   - The literal test-string pattern indicates deliberately fed invalid input rather than a widespread production parsing bug; still worth confirming callers validate input before `Integer.parseInt` in real workloads.
2. `java.lang.IllegalStateException` — 199 events (36.7%, 6.6/s), thrown mostly from `JfrLoad.exceptionLoop:141` (user_code)
   Sample: "synthetic-2"
   - Thrown from a method literally named `exceptionLoop` with a message literally labeled "synthetic-2" — this strongly indicates a deliberate exception-generation code path, not a genuine defect.
3. `java.lang.ArrayIndexOutOfBoundsException` — 119 events (22%, 4/s), thrown mostly from `JfrLoad.exceptionLoop:140` (user_code)
   Sample: "Index 4 out of bounds for length 2"
4. `java.lang.NoSuchMethodError` — 24 events (4.4%, 0.8/s), thrown mostly from `java.lang.invoke.MethodHandleNatives.resolve` (jdk)
   Sample: "'void java.lang.invoke.DirectMethodHandle$Holder.invokeStatic(java.lang.Object, java.lang.Object, long)'"
   - 🔵 Normal method-handle/invokedynamic bootstrap linkage probing, not a runtime bug.

### Top Throwing Sites
Throwing sites correlate 1:1 with the top classes above.

## I/O Activity

Total slow-I/O blocking time is 29,857.1 ms — 99.5% of the entire 30 s recording — and it is entirely socket I/O (no slow file I/O captured). This is dominated by a single loopback endpoint, `localhost`, which accounts for all 1956 read events and the full 29,857.1 ms of blocking time (avg 15.3 ms/op, max 43.5 ms). The connection isn't on a recognized database or HTTP port, so it reads as a local socket peer (possibly an internal listener/consumer thread or IPC channel) rather than a conventional downstream dependency. Note: only I/O operations exceeding ~10 ms are captured, so this reflects slow-I/O time on this endpoint specifically, not total I/O volume.

### Top I/O Targets
1. `localhost` [socket] — 29,857.1 ms across 1956 ops (0 MB, max 43.5 ms)
   - Not a recognized DB/HTTP port — a loopback endpoint accumulating blocking-read time across nearly the entire recording. If a single thread is parked here throughout, it's likely a dedicated listener/consumer rather than a bottleneck on the batch job's critical path, but confirm what this endpoint represents in the real deployment.

## Findings
- **🟡 Near-total-duration socket blocking on one loopback endpoint**: A single socket endpoint (`localhost`) accounts for the full 29,857.1 ms of blocking read time across all 1956 events — 99.5% of the 30 s recording. **Evidence**: `jfr_io` summary.total_io_blocking_time_ms=29,857.1, io_blocking_pct_of_recording=99.5%, single_endpoint_dominant=true. **Why it matters**: if this represents a thread on the batch job's critical path (rather than a benign background listener), it would dominate wall-clock time and needs to be identified.
- **🟢 Non-benign condition-wait parking**: 99.6% of thread-park time (22,058.7 ms of 22,141.8 ms) is `condition_wait` via `LockSupport.parkNanos`, and the tool explicitly marks this as not benign pool-idle waiting. **Evidence**: `jfr_lock_contention.thread_parking.by_category` condition_wait=1475 events/22,058.7 ms (99.6%); signals.park_total_likely_benign=false. **Why it matters**: this pattern warrants a look at the underlying producer/consumer coordination logic rather than being dismissed as routine idle-pool behavior.
- **🟢 Minor real monitor contention at a dedicated lock path**: One real `JavaMonitorEnter` contention event occurred, at a code path explicitly named for lock contention. **Evidence**: monitor_contention.total_events=1, total_wait_time_ms=10.1 at `JfrLoad.lockContentionLoop:122`. **Why it matters**: negligible at this scale, but worth re-checking under realistic production concurrency where this code path could see far more contention.
- **🔵 CPU time fully concentrated in one recursive method**: `JfrLoad.fibonacci` accounts for 99.9% of on-CPU samples. **Evidence**: `jfr_top_methods.top_methods[0]`: 1744/1746 samples. **Why it matters**: expected for a compute-bound recursive workload; if this reflects real production logic, naive recursive Fibonacci is exponential-time and a memoization/iterative rewrite would be worth considering.
- **🔵 Exception volume appears synthetic, not defect-driven**: 17.8 exceptions/s across three classes, with method names and messages (`exceptionLoop`, "synthetic-2", "not-a-number-0") indicating intentional exception generation. **Evidence**: `jfr_exceptions` summary.throws_per_second=17.8; sample_message fields cited above; signals.control_flow_smell=false. **Why it matters**: no action needed for this recording, but if this pattern shows up in a genuine production capture, the >35% share held by two single classes would merit investigating exception-driven control flow.

## Recommendations
1. **(Socket blocking)** Confirm what `localhost` represents in the real deployment — internal IPC, health-check listener, or an actual downstream call — and whether the thread blocked on it sits on the batch job's critical path; if so, consider whether blocking reads are appropriate or an async approach is warranted.
2. **(Condition-wait parking)** Review the producer/consumer (or equivalent condition-based) coordination logic responsible for the 22 s of `condition_wait` time to confirm it reflects expected queue-draining rather than avoidable blocking.
3. **(Monitor contention)** Load-test the code path around `JfrLoad.lockContentionLoop:122` under realistic concurrency to verify the synchronized block doesn't become a bottleneck at higher thread counts than this recording exercised.
4. **(CPU-bound recursion)** If `fibonacci`-style recursive computation reflects real business logic rather than a synthetic load generator, apply memoization or an iterative rewrite to avoid exponential-time growth.
5. **(Exception volume)** If similar exception rates appear in a genuine production JFR capture (not a synthetic/test recording), measure the cost of exception construction (~18 throws/s here) and avoid using exceptions for expected/validated failure paths.

## Analysis Limitations
This build analyzes CPU samples, GC behavior, object allocation, total memory footprint (with NMT for per-category native breakdown), lock contention / thread parking, exception throws (per-class breakdown), file/socket I/O wait, and JVM native-method execution (blocked-in-syscall / JNI). The following are NOT yet covered and would change the picture if data is available:
- Class loading and JIT compilation overhead
- Note: I/O analysis covers only operations exceeding ~10ms; high-frequency fast I/O is aggregated in CPU/allocation profiles instead
- Note: jdk.JavaExceptionThrow is disabled under JFR's default/profile settings profiles in general, though it was explicitly enabled for this recording (`jdk.JavaExceptionThrow#enabled=true`), so exception-throw activity here is fully observable.
