# anvil — ARCHIVED

> This repository has been **archived** as of 2026-06-08.
>
> Active development of anvil continues in the private monorepo
> **`SuperBadLabs/chengis`** at the `anvil/` subdirectory.
>
> ## Why
>
> The v0.5–v0.6 development window made the empirical case for
> consolidation: cross-component changes (anvil ↔ chengis-core)
> were paying ~30% PR overhead in the 3-repo split for dep bumps,
> CI workflow updates, and version-skew bugs the per-repo test
> suites couldn't catch. The strategic shift toward "fix real
> bugs, not flags" required a development surface where protocol
> changes and their consumer fixes could land in a single
> atomic commit.
>
> ## What still works here
>
> - All commits, tags, branches, releases, PRs, and issues remain
>   accessible for historical reference
> - Release binaries (v0.5.0, v0.6.0, v0.6.1, v0.6.2) remain
>   downloadable from the [releases page](https://github.com/SuperBadLabs/anvil/releases)
> - License terms (Apache 2.0) are preserved unchanged on the
>   archived code
>
> ## What doesn't
>
> - No new PRs, issues, or commits accepted on this archive
> - The private monorepo successor is invite-only; not all
>   contributors will have access
>
> ## Anvil's role in the monorepo
>
> `anvil/` remains a Jenkinsfile-compatible single-team CI server.
> Its `project.clj`, version numbering, and CHANGELOG continue
> independently from the other monorepo components.

---

Original README content preserved in commit history; see `README.md`
at any pre-archive commit for the v0.5/v0.6-era product description.
