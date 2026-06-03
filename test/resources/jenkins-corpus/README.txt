Jenkins declarative-pipeline corpus
===================================

This directory holds real-world declarative Jenkinsfiles collected from
permissively-licensed OSS projects on GitHub. The corpus exists to drive
parser-conformance tests for `chengis.compat.jenkins` (the Jenkins-
compatibility layer that parses Jenkinsfiles and executes them on the
Chengis executor). It is NOT a runtime fixture — these files are never
executed; they only need to parse cleanly and match the structural
expectations the parser produces.

Curated by:  claude (general-purpose agent for T0.3)
Fetched on:  2026-05-27

Acceptance rules (apply strictly when adding new entries):

  1. The file MUST contain a top-level `pipeline { ... }` block.
     Files that are pure scripted (`node { ... }`) or that are just a
     shared-library call (e.g. `buildPlugin(...)`) are OUT OF SCOPE.
     A `script { ... }` block nested inside a declarative pipeline is fine.

  2. License must be one of: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause.
     Verify against the repository's LICENSE file or the GitHub API's
     `.license.spdx_id`. REJECT: GPL/AGPL/LGPL/EPL/MPL/unspecified/
     "all rights reserved".

  3. Source must be a real, active OSS project — Apache Software
     Foundation, jenkinsci org, spring-projects, Netflix OSS, etc.
     No tutorial repos, no personal "my-first-jenkins" repos.

  4. Filename pattern: <org>__<repo>__<ref>__<path>.Jenkinsfile
     Slashes in the path become underscores. `ref` is the branch or
     (preferably) the commit SHA the file was fetched at.

Adding a new entry:

  - Pick a candidate Jenkinsfile from an eligible repo.
  - Verify rules 1-3 above. The cheapest correctness check is:
      rg -lE "^pipeline\s*\{" <file>      # must match
      gh api repos/<org>/<repo> --jq .license.spdx_id
  - Save it under this directory using the filename pattern.
  - Add an entry to MANIFEST.edn including :patterns set and :line-count.
  - Update :pattern-coverage at the bottom of MANIFEST.edn.

Pattern tags used:

  Agent shape:        :agent-any :agent-label :agent-docker :agent-dockerfile
                      :agent-none :stage-level-agent
  Parallelism:        :parallel :fail-fast :sequential-stages :matrix
  Step types:         :sh :bat :dir :with-env :with-credentials
                      :timeout :retry :script-block
  Reporting/artifacts :archive-artifacts :junit :stash :unstash :publish-html
  Top-level blocks:   :when :parameters :triggers :options :tools
                      :environment-block
  Post sections:      :post-always :post-success :post-failure
                      :post-changed :post-cleanup
  Library imports:    :library-import :shared-library

If you find a useful pattern not on the list above, add a new keyword —
just keep it kebab-case and include it in :pattern-coverage too.
