# Specifications

This directory contains the normative contracts shared by the Swift, Kotlin, recorder, replay, and evaluator implementations.

## Active specification

- [Core specification v1](v1/core.md) — terminology, coordinate frames, signal semantics, time, lifecycle, errors, capabilities, and compatibility
- [Wire format v1](v1/wire-format.md) — streaming container, record schemas, finalization, validation, and privacy tiers
- [JSON Schemas](v1/schema/) — JSON Schema 2020-12 contracts for every v1 record and the logical trace envelope

The wire-format schemas implement the semantic decisions in the core specification rather than redefining them.

## Versioning

The core specification and wire schema have separate versions:

- `coreSpecVersion` identifies semantic behavior.
- `schemaVersion` identifies a serialized trace format.

Until the first v0.1 release, documents may carry a `draft` prerelease suffix. A merged draft is normative for downstream v0.1 work unless a later specification change explicitly supersedes it.
