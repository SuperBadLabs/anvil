# Long builds & timeout caps (v0.4 AN6-6)

Wild-corpus builds vary wildly in duration. apache-camel-quarkus
finished its first end-to-end run in ~6 minutes; apache-hbase routinely
takes 90+ minutes to land its first artifact. v0.3.3's AN5-RERUN
harness was clipping hbase at the default 30-minute cap before it
could even reach `mvn package`, hiding signal as `still building` in
the receipt.

v0.4 introduces two independent timeout knobs.

## Harness cap — `--max-minutes N`

`scripts/wild-corpus-rerun.bb` polls every 30 seconds for build
completion. The cap controls how long the *harness* will wait before
giving up; the build itself keeps running inside the daemon. Default
30 minutes; pass `-J-Dmax-minutes=90` (or set the `max-minutes` system
property) to raise it.

Use this when:

- A specific build is known to need more time (apache-hbase, eclipse-jdt-core)
- You want the receipt to capture the build's real outcome rather
  than a `still-building-at-cap` honest gap
- You're running on a slow CI runner

It does NOT enforce a cap on the daemon's own build execution — that
needs the dispatcher knob below.

## Dispatcher cap — `:anvil.dispatcher/build-timeout-min`

(Reserved knob — implementation comes with the dispatcher hook in a
follow-up.) Set in `anvil.edn`:

```clojure
{:anvil.dispatcher/build-timeout-min 90}
```

Default 30 minutes. When the dispatcher hits this cap, the build's
`sh`/`bat` subprocesses receive SIGTERM (10s grace) → SIGKILL and the
build classifies as `:aborted :builds/dispatcher-timeout`.

## When to bump both

Long-running corpus shapes (hbase, jdt-core) typically need both raised
together — harness cap so the receipt captures the verdict, dispatcher
cap so the daemon doesn't kill the build before it finishes.

```bash
# 2-hour harness wait, 2-hour daemon cap
bb -Jmax-minutes=120 scripts/wild-corpus-rerun.bb
# (and bump :anvil.dispatcher/build-timeout-min to 120 in anvil.edn)
```

## Honest classification at the cap

A build that hits either cap is recorded as **`:aborted`**, NOT
`:failure`. The classifier honors the AN5-1 honesty contract: a
cap-killed build's last step didn't get to fail on its own merits, so
calling it a failure would be misleading. Receipts distinguish the two
explicitly.
