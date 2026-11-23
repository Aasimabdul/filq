<p align="center"><img src="docs/icon.svg" width="96" alt="Fliq app icon"></p>

# Fliq — Free, Open-Source File Converter for Android

**Fliq** is a lightweight Android file converter for images, PDF pages, and supported Office documents. Select one file or a batch of files, choose a common output format, and convert them locally when the format is supported. It also includes PDF merging for multiple images and file renaming.

[Project website](https://aasimabdul.github.io/filq/) · [Source code](https://github.com/Aasimabdul/filq) · [Releases](https://github.com/Aasimabdul/filq/releases) · [Report an issue](https://github.com/Aasimabdul/filq/issues)

> **Status:** Work in progress. A successful CI build is not a substitute for real-device testing. Check the latest Actions run and release notes before installing.

## Features

| Input | Available output formats |
|---|---|
| JPG/JPEG/JPE/JFIF, PNG, WebP, BMP, GIF, HEIC/HEIF, AVIF | JPG, PNG, WebP, BMP, PDF (decoder support varies by Android version and file; GIF is converted from its first frame) |
| SVG | JPG, PNG, WebP, BMP, PDF |
| SVG | JPG, PNG, WebP, BMP, PDF |
| PDF | PNG, JPG, WebP (one image per page), Word/DOCX (selectable text only) |
| Word DOCX | PDF (offline, text-first) |
| PowerPoint PPTX | PDF (offline, text-first) |
| Legacy Word/PowerPoint and spreadsheets | Server-based PDF conversion when a compatible server is configured |
| Any file | Rename: Clean, snake_case, Date + name, or custom name |

### Batch conversion

1. Open Files or another Android file manager.
2. Select multiple images or supported files.
3. Tap **Share** or **Open with**, then choose **Fliq**.
4. Choose one output format that is available for every selected file.
5. Fliq processes files one at a time. A failed file is reported without automatically stopping the rest.

For a selection containing multiple images/SVGs, **Merge PDF** creates one PDF with all selected images. The regular **PDF** option creates one PDF per image. Mixed selections are limited to formats common to all selected file types, usually JPG, PNG, or WebP.

### Offline processing and limitations

- Supported image conversions and PDF page rendering run on the device.
- PDF → DOCX extracts selectable text only. Scanned/image-only PDFs need OCR, which is not included.
- DOCX → PDF and PPTX → PDF are text-first conversions, not full Office rendering. Original layout, images, charts, tables, fonts, and animations may be missing or changed.
- Legacy `.doc` / `.ppt` and spreadsheet conversions require a separately configured LibreOffice-compatible conversion server. A server receives the file content sent to it.
- Unsupported, damaged, password-protected, or unusually large files may fail. Always inspect important outputs before use.

## Privacy and security

Fliq is designed to avoid uploading files for its listed on-device conversions. See [Privacy notes](PRIVACY.md) and [Security policy](SECURITY.md). If using an optional remote conversion server, choose a server you trust and prefer HTTPS. Do not put secrets or signing keys in source control.

## Device compatibility and performance

- Minimum Android: Android 10 (API 29).
- Uses native Android views and processes files/pages sequentially to reduce peak memory use.
- The UI uses density-independent dimensions and scrollable content, but compatibility across every phone, tablet, display size, OEM file manager, and landscape configuration is **not guaranteed until tested**. Please report reproducible layout or rotation bugs with device model, Android version, orientation, and sanitized steps.
- CI checks builds and lint; these checks cannot replace physical-device testing.

## Build from source

Requirements: JDK 17, Android SDK, and network access for Gradle dependencies on the first build.

```sh
git clone https://github.com/Aasimabdul/filq.git
cd filq
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

GitHub Actions runs the same core checks on pull requests and the default branch. The debug APK is published as a short-lived workflow artifact, not as a signed production release.

## Release signing

The tag-based release workflow expects a maintainer-owned signing key. Never upload the keystore itself to the repository. Back it up securely; losing it may prevent updates to an installed app.

1. Generate a key locally and back it up:
   ```sh
   keytool -genkeypair -v -keystore fliq-release.jks -alias fliq -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 fliq-release.jks
   ```
2. In **Settings → Secrets and variables → Actions**, configure `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.
3. After the changes are reviewed, CI passes, and device testing is complete, create and push a version tag:
   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

Do not create a release tag until the version number, changelog, signing secrets, build, and installation/update behavior have been verified.

## Open source

Fliq is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE).

- [Contributing guide](CONTRIBUTING.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Privacy notes](PRIVACY.md)
- [Security policy](SECURITY.md)
- [Issue tracker](https://github.com/Aasimabdul/filq/issues)

## Find Fliq online

The project landing page is in `docs/index.html`. To publish it, open the repository's **Settings → Pages**, select deployment from the `main` branch and the `/docs` folder, then save. The public page is expected at `https://aasimabdul.github.io/filq/` once GitHub Pages is enabled and successfully deployed.

For Google Search discovery, ensure the page is publicly accessible, then add the exact deployed URL in Google Search Console and request indexing. The sitemap at `docs/sitemap.xml` is a discovery hint, not a guarantee: Google decides whether and when to crawl, index, and display the page. See Google's [technical requirements](https://developers.google.com/search/docs/essentials/technical) and [SEO starter guide](https://developers.google.com/search/docs/fundamentals/seo-starter-guide).
