---
name: compare-jfr
description: Compare two JVM Flight Recorder (.jfr) recordings — a baseline and a current one — to check whether a change regressed performance. Use whenever the user asks to compare two recordings, check for a performance regression between two .jfr files, diff a before/after recording, or asks something like "did this change regress performance" or "compare this recording to last week's baseline".
---

# JFR Baseline Comparison

You are checking whether a change regressed JVM performance, using two `.jfr`
recordings of the same workload: a baseline (before the change, or a prior
release) and a current one (after the change). The jfrdoc MCP server's
`jfr_baseline_diff` tool does the comparison; this skill defines how to use
and present it.

This is a different task from a full single-recording analysis — use the
`analyze-jfr` skill instead if the user only gave you one recording.

## Inputs

- **Baseline and current `.jfr` paths** — both required. If the user only
  gave one, ask for the other; do not guess which existing file is the
  baseline.
- **Container memory limit** — optional, applied to both recordings. Pass it
  as `container_memory_mb` if the user provides it.
- **Framework** — optional (`spring`, `quarkus`, `other`), same inference
  rule as `analyze-jfr`: check `jvm.javaArguments` if ambiguous.
- **Thresholds** — the tool ships sensible defaults (20% allocation-rate
  increase, 2 percentage-point GC-overhead increase, 30% GC-p99 increase,
  15% memory increase). Only override them if the user asks for a different
  sensitivity; do not invent a reason to tighten or loosen them yourself.

## Workflow

1. Call `jfr_baseline_diff` once with both paths (and `container_memory_mb`
   if known). It runs the full comparison in a single call — do not call the
   individual analysis tools (`jfr_allocation`, `jfr_gc_stats`, etc.)
   separately for this task.
2. Report the top-level `verdict` (PASS or FAIL) first, in one sentence.
3. For each entry in `metrics`, mention it only if `regressed` is true, or if
   `evaluable` is false (say plainly what couldn't be checked and why — e.g.
   NMT not available on one side). Do not narrate metrics that passed cleanly;
   a clean comparison should read as short.
4. If `container_fit.downgraded` is true, call this out explicitly and with
   priority — a verdict moving toward `exceeded` is the most operationally
   important signal this tool can produce, same as in a single-recording
   report.
5. If `verdict` is FAIL, list every entry in `regressions` explicitly with
   its baseline value, current value, and delta, so the reader can see the
   actual numbers driving the verdict — never just say "something regressed."

## Non-negotiables

- Every number comes from the tool's output. Do not estimate or round beyond
  what it returned.
- Do not speculate about *why* a metric regressed (a code change, a data
  size difference, noise) unless the user's own message already supplies
  that context — the tool only tells you *that* something changed, not why.
- A metric with `evaluable: false` is not a finding either way — say it
  wasn't checked, don't imply it passed.
