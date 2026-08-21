# jfrdoc product roadmap

This document captures the product-strategy direction agreed for turning
jfrdoc from a point tool into a product, and why. It is a living reference,
not a contract — update it as assumptions get tested against real usage.

## Strategy: open-core

jfrdoc (the MCP tools + skills in this repo) stays free and MIT-licensed,
growing adoption among its target user — platform/SRE teams running JVM
services who today have to read a `.jfr` file and write a report like this
by hand. A paid layer, **jfrdoc Cloud**, will eventually monetize what the
free tool structurally can't do alone: history over time and a fleet-wide
view. jfrdoc's local-only architecture (it needs a JVM on the same machine
as Claude Code) is not something Phase 1 tries to remove — Phase 2's backend
is designed as a standalone service the plugin is one interface into, not a
rearchitecture of the existing tools.

## Phase 0 — today

Nine local analysis tools (`jfr_summary`, `jfr_top_methods`, `jfr_gc_stats`,
`jfr_allocation`, `jfr_memory`, `jfr_lock_contention`, `jfr_exceptions`,
`jfr_io`, `jfr_native_methods`) plus the `analyze-jfr` skill that turns them
into a structured report. Free, OSS, distributed via the Claude Code plugin
marketplace.

## Phase 1 — baseline-diff + CI regression gate (free, OSS, shipped)

**What**: a tenth tool, `jfr_baseline_diff`, compares a baseline recording
against a current one on allocation rate, GC pause overhead, GC p99 pause,
total memory footprint, and container-fit verdict, returning a PASS/FAIL
verdict. It's reachable two ways:

- Inside Claude Code, via the new `compare-jfr` skill.
- Headlessly, via `java -jar jfrdoc-mcp.jar diff --baseline ... --current
  ...` (no MCP, no Claude), and as a composite GitHub Action
  (`uses: roz-labs/jfrdoctor-plugin@vX`) wrapping that CLI mode.

**Why this shape**: no new infrastructure. Baselines are whatever `.jfr`
files the caller hands it (a git-committed recording, a prior CI run's
artifact) — jfrdoc doesn't store or capture anything itself yet. This is the
CodSpeed/Bencher pattern applied to JVM profiling data instead of benchmark
numbers, and it's small enough to build and ship solo.

**Status**: implemented on this branch — see `src/main/java/jfrdoc/tools/JfrBaselineDiffTool.java`,
the `diff` subcommand in `src/main/java/jfrdoc/mcp/McpServer.java`, and
`action.yml`.

**Known limitation carried into this phase**: no CPU-hotspot diffing yet
(matching the top method between two recordings reliably, across a code
change that might rename or move it, is unsolved here) — documented in the
README's Honest Limitations section rather than silently scoped out.

## Phase 2 — jfrdoc Cloud: hosted trend + fleet dashboard (paid)

**What**: a hosted backend that stores Phase 1's comparison results over
time per service — trend history, a fleet-wide view across many
services/pods, alerting integration. The bridge from Phase 1: a one-line
opt-in "publish to jfrdoc Cloud" flag on the CI Action, so Phase 1 stays
default-local/offline (no forced signup) while doubling as the on-ramp into
Phase 2.

**Pricing**: per-monitored-service/repo, tiered — not per-seat. Seat pricing
discourages the org-wide adoption that makes a regression gate useful in the
first place; per-service pricing scales with what actually costs money to
store and serve, and is easy for a platform team to justify to a budget
owner without every engineer needing a paid seat.

**Hosting model**: SaaS-lite. Only the already-aggregated, already-redacted
JSON metrics jfrdoc's tools already produce ever leave the customer's
environment — never the raw `.jfr` file, which can carry secrets (see the
README's Data Egress and "Recordings contain more than your application"
sections). This keeps a solo founder running one service instead of
supporting N self-hosted deployments, and turns jfrdoc's existing redaction
work into a sellable trust signal rather than just a defensive README
section.

**LLM dependency**: none, at the core. Regression detection, trends, and
fleet view are deterministic and useful with zero LLM involved — the same
philosophy as Phase 0/1's tools. Claude-driven narration stays an optional
enhancement layer, not a requirement, so revenue isn't coupled to Anthropic's
plugin ecosystem or API pricing.

**Branding**: one brand — `jfrdoc` / `jfrdoc Cloud` — not a separate product
identity. Splitting brands costs marketing effort a solo founder doesn't
have, and the whole point of the open-core strategy is that OSS adoption
feeds the paid product's funnel.

**Status**: not started. Depends on Phase 1 usage/signal before committing
engineering time.

## Phase 3+ — later bets (not committed, priority order if forced to rank)

1. **Redaction-as-a-service** — small lift, reinforces the trust
   positioning, sellable even to non-paying OSS users. Genuine white space:
   no dominant commercial player automates this specifically for JFR today.
2. **Self-hosted/enterprise tier** — build only when a specific paying
   customer asks, not speculatively; unlocks larger/more security-sensitive
   customers at the cost of solo-founder support burden.
3. **Auto-capture triggers** (on OOM, on latency SLO breach, on deploy) —
   bigger infrastructure lift (an always-on agent in customer clusters),
   higher value, more competitive with existing APM vendors.
4. **Multi-language expansion beyond JVM** — deprioritized or possibly
   never. Going polyglot means competing directly with Datadog/Pyroscope's
   core TAM instead of owning the JVM-depth niche that's the current
   differentiator.

## Pros / cons of the overall direction

**Pros**: solo-buildable (Phase 1 needed zero new infrastructure); the free
tier keeps the OSS trust story and existing plugin-marketplace distribution
intact; Phase 2 pricing avoids seat-friction so whole teams adopt it;
positions against the closest real precedent (Tier1App/yCrash's
freemium-per-upload trajectory) rather than picking a fight with
Datadog/Dynatrace on their own turf; the security-conscious redaction work
already in this repo becomes a sellable trust signal, not just a defensive
README section.

**Cons / risks worth naming**: Phase 1 alone generates no revenue — it's a
bet that free-tool adoption actually converts to Phase 2 paying customers,
which is unproven. Solo-founder means Phase 2's hosting/support burden is a
real bottleneck the moment more than a few teams opt in. yCrash already
ships a commercial LLM-synthesis-over-JFR product, so "AI-narrated JFR
report" alone isn't a moat — the moat has to be the CI-gate/trend/fleet
layer, not the narration. Staying JVM-only caps total addressable market
versus polyglot competitors, which was accepted deliberately as a focus
trade-off, not an oversight.

## Market context (for reference)

jfrdoc sits in the JVM/APM profiling and diagnostics market, which splits
into continuous/fleet SaaS profilers (Datadog Continuous Profiler, Grafana
Pyroscope, Parca), full APM suites with profiling bundled (Dynatrace, New
Relic, AppDynamics), JVM-specific commercial desktop tools (JProfiler,
YourKit, Azul Mission Control/Intelligence Cloud), and open-source point
tools (async-profiler, jfr-report-tool). The closest structural and
business-model analog is **Tier1App's suite** (GCeasy/fastThread/
HeapHero/yCrash) — narrow single-artifact analyzers built by essentially one
engineer that went from free to freemium SaaS priced per upload, later
adding an LLM synthesis layer (yCrash's "Clever AI Analysis") on the same
JFR-adjacent problem space. Business-model precedents for the open-core →
hosted-SaaS path: Grafana Labs (Pyroscope acquisition folded into Grafana
Cloud). Acquisition is also a live precedent in this exact niche: jClarity →
Microsoft (2019).
