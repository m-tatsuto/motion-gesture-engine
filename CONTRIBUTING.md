# Contributing

Thank you for helping build a reproducible mobile motion gesture foundation.

## Current scope

Contributions for the v0.1 milestone should map to specification, legacy baseline, recorder, replay, evaluator, or their tests and documentation. New detector algorithms, calibration, device-specific profiles, and ML models are intentionally deferred.

Please discuss work in the corresponding issue before starting a large change.

## Data safety

Do not commit raw motion recordings collected from users or production applications.

Fixtures must be one of:

- Synthetic data generated from documented parameters
- Recordings created by the contributor specifically for public use
- Sanitized data that has completed an explicit review and publication process

Fixtures must not contain account identifiers, advertising identifiers, device serial numbers, location, audio, video, or unrelated application logs.

## Pull requests

- Keep changes focused on one roadmap issue.
- State the normative behavior being changed.
- Add or update deterministic tests.
- Describe trace/schema compatibility impact.
- Report Swift and Kotlin parity impact where applicable.
- Do not silently change `LegacyGravityThresholdV1` defaults.

## Generated and recorded files

Large trace files should not be added until the schema, provenance, and review status are documented. Prefer compact synthetic fixtures for unit and CI coverage.
