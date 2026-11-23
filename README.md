<p align="center"><img src="docs/icon.svg" width="96" alt="Fliq icon"></p>

# Fliq

Share one file or several files to **Fliq** to convert or rename them. The original files are never changed; results are saved next to the originals when Android allows it.

Built to stay small and light: plain Android views (no Compose, no AndroidX), one background thread, and files/pages are processed one at a time to reduce memory use.

## Conversion features

| Input | Available output formats |
|---|---|
| JPG/JPEG, PNG, WebP, BMP, GIF, HEIC | JPG, PNG, WebP, BMP, PDF (decoder support may vary by Android version and file) |
| SVG | JPG, PNG, WebP, BMP, PDF |
| PDF | PNG, JPG, WebP (one image per page), Word/DOCX (selectable text only) |
| Word DOCX | PDF (offline, text-first) |
| PowerPoint PPTX | PDF (offline, text-first) |
| Legacy Word/PowerPoint, spreadsheets and other supported Office formats | PDF, and PDF pages to JPG/PNG/WebP when the conversion server is configured |
| Any file | Rename: Clean, snake_case, Date + name, or a custom name |

### Batch conversion

Select multiple files in your file manager, then use Share/Open with → Fliq. Choose one output format and Fliq processes the files one by one into that same format. Mixed selections are supported when they share a common output option (typically JPG, PNG or WebP). If one file fails, Fliq reports it and continues with the others.

When selecting multiple images or SVGs, **Merge PDF** creates one PDF containing all selected images. The regular **PDF** option makes one PDF per image.

## Offline and fidelity notes

- Image conversions and PDF page rendering run locally.
- PDF → Word (DOCX), DOCX → PDF and PPTX → PDF are implemented as lightweight text-first offline conversions.
- PDF → Word only extracts selectable text. Scanned PDFs need OCR, which is not included.
- DOCX/PPTX → PDF does not fully render the original Office layout, embedded images, charts, tables, or animations.
- Legacy `.doc` / `.ppt` and spreadsheet conversions require a configured LibreOffice conversion server and are not fully offline.
- The home screen has usage instructions only; no API key, token, or server URL entry is shown there.

## Same-folder saving

Android only lets an app create files in some folders. Fliq tries to save next to the original when allowed:

- `Download/…`: any file type
- `Pictures/…` and `DCIM/…`: image results
- `Documents/…`: non-media results (e.g. PDF)

If the original folder can't be found or isn't allowed, Fliq saves to `Download/Fliq` and tells you.

## Releasing

Pushing a tag builds a signed APK and publishes it on the Releases page. One-time setup:

1. Make your own signing key and **back it up**:
   ```
   keytool -genkeypair -v -keystore fliq-release.jks -alias fliq -keyalg RSA -keysize 4096 -validity 10000
   ```
2. In the repository, go to Settings → Secrets and variables → Actions, and add:
   - `KEYSTORE_B64`: output of `base64 -w0 fliq-release.jks`
   - `KEYSTORE_PASSWORD`: the keystore password
   - `KEY_ALIAS`: `fliq`
   - `KEY_PASSWORD`: the key password
3. Create and push a tag, for example: `git tag v0.2.0 && git push origin v0.2.0`

## Build

GitHub Actions builds an APK on pushes (see the **Actions** tab and the `Fliq-debug-apk` artifact).

Locally, with the Android SDK installed:

```
./gradlew :app:assembleDebug
```

Minimum Android version: Android 10 (API 29).
