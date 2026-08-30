# Specifications

This directory contains the normative contracts shared by the Swift, Kotlin, recorder, replay, and evaluator implementations.

## Active specification

- [Core specification v1](v1/core.md) — terminology, coordinate frames, signal semantics, time, lifecycle, errors, capabilities, and compatibility

The wire-format JSON Schemas are tracked separately in [issue #3](https://github.com/m-tatsuto/motion-gesture-engine/issues/3). They must implement the semantic decisions in the core specification rather than redefine them.

## Versioning

The core specification and wire schema have separate versions:

- `coreSpecVersion` identifies semantic behavior.
- `schemaVersion` identifies a serialized trace format.

Until the first v0.1 release, documents may carry a `draft` prerelease suffix. A merged draft is normative for downstream v0.1 work unless a later specification change explicitly supersedes it.
