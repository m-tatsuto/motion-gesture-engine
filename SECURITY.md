# Security policy

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability or for a motion trace that may contain private data.

Use the repository's private security advisory reporting flow under **Security -> Advisories -> Report a vulnerability**. Include only the minimum information needed to reproduce the issue, and do not attach production user recordings unless a maintainer explicitly requests a secure transfer.

## Data exposure

The open-source library is designed to operate without a network transport. Integrations that upload traces must implement their own consent, authentication, authorization, size limits, retention, deletion, and abuse controls.
