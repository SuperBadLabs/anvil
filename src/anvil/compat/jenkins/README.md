# anvil.compat.jenkins

The Jenkins-compatibility layer that lets anvil consume real Jenkinsfiles.

## Clean-room rule

Implementers MUST work from public Jenkins documentation and observed
behavior of a running Jenkins instance ONLY. Do not read Jenkins source
code (jenkinsci/jenkins, jenkinsci/pipeline-model-definition-plugin, or any
other jenkinsci/* repo) while writing or reviewing code in this directory.

Why: Jenkins is MIT-licensed and *Google LLC v. Oracle America* (SCOTUS
2021) protects API reimplementation, but clean-room hygiene is cheap
insurance and good engineering discipline regardless. If a behavior is
unclear from public docs, write a test against a real Jenkins instance
and infer from outputs.

If you have read Jenkins source code recently, do not contribute to this
directory for at least 30 days.

## Trademark

"Jenkins" is a registered trademark of LF Charities Inc. (Continuous
Delivery Foundation, Linux Foundation). anvil uses the name descriptively
under nominative fair use. anvil does not use the butler logo.

## Layout

```
anvil/src/anvil/compat/jenkins/
├── README.md           ← this file
├── groovy.clj          ← Groovy JSR-223 engine + AST walk helpers       (TX3)
├── ir.clj              ← Jenkins IR shape: predicates + constructors    (TX3)
├── translator.clj      ← Jenkinsfile source → Jenkins IR                (TX3)
├── runtime.clj         ← Pipeline DSL globals + script {} execution     (TX4)
├── dispatcher.clj      ← StepDispatcher impl for :jenkins/* step types  (TX4)
└── steps/              ← Per-step adapters (sh, dir, withCredentials, …) (TX4)
```

In scope (TX3): parsing only. Output is a Jenkins IR that the runtime
(TX4) consumes.

Out of scope here: execution. See [`../../../../../docs/jenkins-compat/`](../../../../../docs/jenkins-compat/)
for the full program.
