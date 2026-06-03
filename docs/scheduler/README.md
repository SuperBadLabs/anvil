# Scheduled triggers — cron (v0.3 — T5)

Status: **stub** (T0.5 placeholder). Real docs land at T5.7 / T8.5.

## What this feature ships

A Jenkinsfile with `triggers { cron('@daily') }` runs the job nightly
without manual trigger. The job page surfaces "Next run: …" live.

## Feature flag

```clojure
;; anvil.edn
{:anvil.features/scheduler true}
```

## Bounding decisions

- **AV3-8**: Cron expressions only at v0.3.0 (no schedule-UI
  builder). Jenkins-compat syntax + `H` (hash-based) slots for
  load-spreading.
- **R5**: Use `java.time.ZonedDateTime` consistently to avoid clock
  drift in long-running instances; default timezone UTC, override via
  `:anvil.scheduler/timezone`.

## Files (when T5 lands)

- `src/anvil/scheduler/cron_parser.clj` — `@daily`/`@hourly`/standard
  cron + Jenkins's `H H * * *` hash-spread syntax
- `src/anvil/scheduler/engine.clj` — single scheduler thread
- Config: `:anvil.scheduler/timezone`
- Jenkinsfile compat: `triggers { cron(…) }` → routes to T5.1/T5.2

## SSE event

`:schedule-fired` on `[:job <name>]` topic. Reserved in T0.4
(`anvil.events.topics/evt-schedule-fired`).

## Owner

T5 tranche owner.
