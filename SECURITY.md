# Security Policy

## Supported Versions

Security fixes are handled on the latest released minor line.
Daily monitoring scans main and the latest stable release's published runtime SBOM. See
[dependency security checks](docs/SECURITY-CHECKS.md#daily-monitoring-and-incident-ownership)
for maintainer ownership, triage targets, temporary exceptions and missed-scan detection.

## Reporting A Vulnerability

Please report security issues privately to Pletor Co., Ltd. before opening a public issue.

Include:

- affected version or commit
- impact and reproduction steps
- whether config file write access, local filesystem access, or container privileges are required
- any suggested mitigation

Do not include secrets, production data, or customer-specific identifiers in reports.

## Security Posture

`node-metrics-agent` is a telemetry module. It must fail open: failures in metric collection,
configuration reload, or exporter integration must not stop the host JVM process.

Configuration is parsed with SnakeYAML safe construction. Runtime config reload keeps the previous
working config if a new config is invalid.
