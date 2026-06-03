#!/usr/bin/env bash
# Stub for ci.jenkins.io's Launchable test-impact-analysis CLI.
# Real launchable is at https://launchableinc.com/ — this stub fakes
# the surface anvil's Tier-3 receipt needs so the Jenkinsfile runs
# without an actual launchable account configured.
case "$1 $2" in
  "verify"*)  echo "launchable-stub: verify ok"; exit 0 ;;
  "record build"*) echo "launchable-stub: build recorded"; exit 0 ;;
  "record session"*) echo "session_token_stub_$$"; exit 0 ;;
  "record commit"*) echo "launchable-stub: commit recorded"; exit 0 ;;
  "subset"*) echo "# launchable-stub: no exclusions" >&2; exit 0 ;;
  "record tests"*) echo "launchable-stub: tests recorded"; exit 0 ;;
  *) echo "launchable-stub: $@" ; exit 0 ;;
esac
