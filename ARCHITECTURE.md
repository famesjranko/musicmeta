# ARCHITECTURE

Why the modules are split where they are, what flows between them, and what a change costs. This is
the map. The pipeline's step-by-step behaviour is `docs/how-it-works.md`, each provider's data
semantics is `docs/providers.md`, and what a green check run proves is `VERIFICATION.md`.

## The bar designs are judged by

musicmeta delivers music metadata from independent upstreams that change without notice. Every
structural choice below is judged by one question: **what does it cost the eleventh provider?** A
seam that makes the next provider cheaper to add, test, and trust earns its complexity; one that
only serves the providers already here does not.

## Module map

```
musicmeta-core        pure JVM: the engine, the provider implementations, the contracts
├── engine/           pipeline: identity, fan-out, gating, merging, synthesis — internal but for
│                     ResultMerger and CompositeSynthesizer, the two extension-point interfaces
├── provider/<name>/  one dir per upstream: *Api, *Models, *Mapper (internal) + at least one
│                     public *Provider — deezer ships a second for similar albums
├── cache/            CacheMode and the in-memory EnrichmentCache
├── http/             the HttpClient seam, rate limiter, budgeted retry, circuit breaker
└── (root package)    the published surface: request/result/profile types, EnrichmentEngine.Builder

musicmeta-okhttp      one class: OkHttpEnrichmentClient, adapting OkHttp to core's HttpClient
musicmeta-android     Room-backed EnrichmentCache (schema + migrations), Hilt wiring, WorkManager
demo-cli, demo-web,   separate composite builds consuming the published shape the way an external
docs-samples[-android]  consumer does — the in-tree stand-ins for consumers we cannot see
```

```mermaid
%%{init: {"theme":"base","themeVariables":{
  "lineColor":"#8b95a5","primaryTextColor":"#10141a",
  "clusterBkg":"#eef2f8","clusterBorder":"#b9c4d4",
  "edgeLabelBackground":"#ffffff","tertiaryTextColor":"#10141a"}}}%%
flowchart TD
    subgraph core["musicmeta-core &nbsp;·&nbsp; pure JVM"]
        published["root package<br/><i>the published surface</i>"]
        engine["engine/"]
        provider["provider/ · one dir per upstream"]
        cache["cache/"]
        http["http/<br/><i>HttpClient seam</i>"]
        published --> engine
        published --> provider
        published --> cache
        published --> http
        engine --> provider
        engine --> cache
        engine --> http
        provider --> http
    end
    okhttp["musicmeta-okhttp<br/><i>OkHttpEnrichmentClient</i>"] -->|api| core
    android["musicmeta-android<br/><i>Room cache · Hilt · WorkManager</i>"] --> core
    demos["demo-cli · demo-web<br/>docs-samples · docs-samples-android"] -.->|"consume as an outsider"| core
    demos -.-> okhttp
    demos -.-> android

    classDef surface fill:#dbe7fb,stroke:#5b87d6,stroke-width:1.5px,color:#10141a
    classDef inner fill:#f3f6fb,stroke:#aab7cb,color:#10141a
    classDef seam fill:#d8f0e6,stroke:#4aa886,stroke-width:1.5px,color:#10141a
    classDef adapter fill:#fdeed6,stroke:#d9a441,color:#10141a
    classDef outside fill:#f1eefb,stroke:#9d8ed4,stroke-dasharray:4 3,color:#10141a
    class published surface
    class engine,provider,cache inner
    class http seam
    class okhttp,android adapter
    class demos outside
```

The edges that matter are the ones that are absent: **core depends on neither adapter.** It names a
wire library nowhere and an Android artifact nowhere, which is what lets a server or desktop
consumer take the engine without either.

## Why the boundaries sit where they do

**core is dependency-minimal JVM** — coroutines, `org.json`, and kotlinx-serialization, nothing
else. Serialization is declared `api(...)`, so it is on a consumer's compile classpath and its
version is part of the published contract; the other two are `implementation`.

**`http/`'s `HttpClient` interface is the seam that keeps it that way.** Core owns transport
*semantics* — what is transient, how a retry budget composes with the enrich deadline, when a
breaker opens — without owning a wire library. Those semantics are policy, not plumbing, and
they are why the interface is core's rather than an adapter's (`docs/pitfalls.md` §11).

**musicmeta-okhttp exists so core does not force a client.** It is deliberately one adapter class,
and it delegates to core's `BudgetedTransientRetry` rather than installing a retrying interceptor —
an interceptor cannot see the enrich deadline, and retry that happens where core cannot count it is
retry no budget bounds. It declares core as `api`, so taking the adapter takes the library.

**musicmeta-android is persistence, DI, and scheduling.** The Room cache implements the same
`EnrichmentCache` contract the in-memory one does, and both are proven by one shared
`EnrichmentCacheContract` in core's `testFixtures` rather than by parallel suites. `EnrichmentWorker`
holds real orchestration — batching, cancellation, progress — but it drives the engine from outside;
nothing here forks engine *behaviour* by platform, and anything that would is a defect.

**Demos and doc-samples are consumer canaries, not examples.** They compile against the published
shape in separate builds, so a break that `apiCheck`'s erased JVM descriptors cannot see still fails
a build that consumes the library from outside (`docs/pitfalls.md` §1).

## One `enrich()` call

```mermaid
%%{init: {"theme":"base","themeVariables":{
  "lineColor":"#8b95a5","primaryTextColor":"#10141a",
  "clusterBkg":"#eef2f8","clusterBorder":"#b9c4d4",
  "edgeLabelBackground":"#ffffff","tertiaryTextColor":"#10141a"}}}%%
flowchart TD
    req["EnrichmentRequest"] --> force{"forceRefresh?"}
    force -->|"yes — both reads skipped"| ident
    force -->|no| cacheread["cache.get, then cache.getNegative<br/>per requested type"]
    cacheread --> anyleft{"any type still<br/>uncached?"}
    anyleft -->|no| results
    anyleft -->|yes| ident{"identity resolution<br/>enabled and needed?"}
    ident -->|no| fanout
    ident -->|yes| resolve["resolveIdentity — canonical ids and names,<br/>provenance stamped; its payload<br/>may answer some types outright"]
    resolve --> fanout

    subgraph fanout["fan-out over the uncached types, under one enrich deadline"]
        direction TB
        regular["regular + composite sub-types<br/>concurrently, one chain each"]
        mergeable["mergeable types<br/>every provider, then merged"]
        composite["composite types<br/>synthesized from the resolved map"]
        regular --> mergeable --> composite
    end

    fanout --> deadline{"deadline held?"}
    deadline -->|"no"| timedout["every unresolved type becomes<br/>Error(TIMEOUT); nothing is cached"]
    deadline -->|yes| writeback["writeBack — positive or negative per type,<br/>keyed with canonical-name aliasing"]
    timedout --> stale
    writeback --> stale["STALE_IF_ERROR only: an expired entry<br/>may stand in for an Error, marked stale"]
    stale --> results["EnrichmentResults"]

    classDef entry fill:#dbe7fb,stroke:#5b87d6,stroke-width:1.5px,color:#10141a
    classDef decision fill:#fdeed6,stroke:#d9a441,color:#10141a
    classDef work fill:#f3f6fb,stroke:#aab7cb,color:#10141a
    classDef store fill:#d8f0e6,stroke:#4aa886,color:#10141a
    classDef failure fill:#fbe0e0,stroke:#cf7b7b,color:#10141a
    class req,results entry
    class force,anyleft,ident,deadline decision
    class resolve,regular,mergeable,composite work
    class cacheread,writeback,stale store
    class timedout failure
```

Every result is gated as it is produced — `filterByConfidence`, then `demoteUnanswered` — inside
whichever stage produced it, rather than in one pass over the finished set. Confidence runs first
because it scores the identification, not the payload (`docs/pitfalls.md` §8): a perfect identity
match can still carry a payload that answers nothing.

Three things this ordering is load-bearing about. **The cache is read before identity resolution**,
so a fully cached call never touches an upstream — unless `forceRefresh` skips both reads, which is
the only way to make one. **Composites are last because they read a map the earlier stages fill** —
they depend on resolved types, so the stages are ordered, not merely parallel. And **a timed-out run
returns what it has but persists none of it**, because the deadline can fire part-way through a step
that rewrites entries, so what survives is a mix of finished and unfinished work.

Two engine-level invariants constrain every provider rather than any one of them, which is what
makes them architectural: **confidence and provenance may understate the evidence, never overstate
it**, and **an absence must never be reported where a failure occurred**. Both are one-directional
on purpose, because consumers branch on the distinction. What each cost to learn is
`docs/pitfalls.md` §4 and §8.

## What the eleventh provider costs

A new provider is a directory under `provider/`, laid out as `CLAUDE.md` prescribes. What that buys
is the point here: it inherits, rather than re-implements,

- transport resilience — rate limiter, budgeted retry, circuit breaker — from `http/`, with one
  breaker per provider id shared across every chain it appears in;
- gating, merging, synthesis, caching, and provenance stamping from `engine/`;
- the shared contract suites and the `scripts/checks/` gates, which apply to it by construction
  rather than by anyone remembering;
- registration through `Builder.addProvider`, which rejects a duplicate id and a reserved one —
  including any id ending `_merger`, the suffix mergers stamp on their own output. Synthesizers
  stamp `_synthesizer`, which is **not** reserved.

What it must supply is the judgement the engine cannot: parsing that survives the upstream's actual
payloads, search acceptance that checks artist and title rather than trusting hit 0
(`docs/pitfalls.md` §7), and a `confidence` that reflects the evidence.

The cost that is not abstracted away is fixtures. A provider test asserts against a fixture copied
from a real response, because that is what pins a field name against upstream drift
(`docs/pitfalls.md` §3). Nothing mechanises this — it is a convention review enforces, and a pool
whose chain back to a live capture is unverified says so in its own `scenario.md`.

## Where a change lands

| Changing | Reaches | Watch for |
|---|---|---|
| The published surface (root-package types, `Builder`) | every consumer, `api/*.api`, `CHANGELOG.md` | erased descriptors hide suspend and nullability breaks — read the `.kt`, not the dump (§1) |
| Engine behaviour (`engine/`) | every provider at once | the two one-directional invariants above |
| One provider (`provider/<name>/`) | its own directory | its fixtures must predate the change, or they prove only that the code agrees with itself |
| Transport policy (`http/`) | every call every provider makes | budgets compose with the enrich deadline (§11); breaker state is shared per provider id, and a provider's own memo is state no consumer can flush (§12) |
| Android persistence | devices that already installed a schema | a migration cannot be undone by reverting the code that shipped it |
