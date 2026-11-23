# Security Policy

## Supported versions

Security fixes are currently intended for the latest code on the default branch and the latest published release, if one is available.

## Reporting a vulnerability

Please do not publish exploit details, access tokens, private server URLs, or sample documents containing personal information in a public issue.

Open a private GitHub Security Advisory for this repository if private vulnerability reporting is enabled. Otherwise, contact the repository maintainer through the GitHub profile and ask for a private channel before sharing technical details.

Include the affected version/commit, Android version and device class, steps to reproduce, expected behavior, actual behavior, and relevant sanitized logs. Do not attach private documents or credentials.

## Security expectations

- Never commit signing keystores, passwords, API tokens, private URLs, or production secrets.
- Treat conversion-server URLs and tokens as sensitive.
- Use HTTPS for remote conversion servers whenever possible. A remote conversion server receives the contents of files sent for conversion.
- Keep dependencies and GitHub Actions up to date, and review dependency/security alerts.
- Fliq is provided without warranty. Do not use it as the only copy of important documents; verify converted files before relying on them.
