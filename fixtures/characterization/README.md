# Detector characterization fixtures

Files in this directory are synthetic, language-neutral detector scripts. They contain no observations or identifiers from a person or physical device.

`legacy-gravity-threshold-v1.csv` uses these columns:

| Column | Meaning |
| --- | --- |
| `step` | Strictly increasing script step. |
| `operation` | `start`, `sample`, `stop`, or `reset`. |
| `timestamp_ns` | Source monotonic timestamp for a sample operation. |
| `sequence` | Source sequence for a sample operation. |
| `gravity_z_g` | Canonical gesture-frame gravity z value in standard-gravity units. |
| `expected_gesture` | Empty, `tiltForward`, or `tiltBackward`. |

These scripts characterize a detector directly. They are not Motion Trace v1 interchange containers and must not be given a `.mge.jsonl` extension.
