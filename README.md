<p align="center"><img src="docs/icon.svg" width="96" alt="Fliq icon"></p>

# Fliq

Share any file to **Fliq** and convert or rename it in one tap. No home screen, no clutter.
The result is saved **in the same folder as the original**.

Built to stay small and light: plain Android views (no Compose, no AndroidX), one background thread,
and every file or PDF page is processed one at a time and freed straight away.

## What it does

| You share | Fliq offers |
|---|---|
| JPG, PNG, WebP, BMP, GIF, HEIC | convert to JPG / PNG / WebP / BMP / PDF (several images can be merged into one PDF) |
| SVG | PNG / JPG / WebP / BMP / PDF |
| PDF | pages to PNG / JPG / WebP |
| PDF | also: PDF to Word (text only) |
| Word (docx), Excel (xlsx), PowerPoint (pptx), CSV, TXT | convert to PDF / PNG / JPG / WebP |
| anything | rename: Clean, snake_case, Date + name, or your own name |

**Fully offline.** Fliq has no Internet permission and no server: every conversion runs on the phone.
Office files are drawn by Fliq's own small renderer on top of Android's text engine (so Tamil and other scripts
shape correctly), and PDF to Word uses Fliq's own PDF text reader and .docx writer. That keeps the APK tiny, but it is
a light renderer, not Microsoft Office or LibreOffice:

- Word: headings, bold/italic/underline, sizes, colours, alignment, bullets, tables, pictures. No headers/footers, text boxes, real list numbering or exact page layout.
- Excel: every visible sheet as a table (stored results, dates converted); no charts, merged cells or cell colours. Up to 5000 rows and 40 columns per sheet.
- PowerPoint: text, pictures, filled shapes and simple tables at their slide positions. No theme backgrounds, charts, SmartArt, animations.
- PDF to Word: text only, in reading order, paragraphs rebuilt. No images, tables or columns; scanned PDFs (no text) and password-protected PDFs are refused with a message.
- Old binary formats (.doc, .xls, .ppt), ODF and RTF are not supported.
- Output is limited to 200 pages per file.

**Several files at once:** select many files, share them to Fliq and pick one format. Mixed selections work too:
images, PDFs and Office files together can all be converted to PNG, JPG or WebP. A file that fails is reported
and the rest still finish. With several images you also get **Merge PDF** (one PDF) next to **PDF** (one each).

Converting and renaming both save a **new copy** next to the original; the original is never touched.

## Same-folder saving

Android only lets an app create files in some folders. Fliq finds the original folder from the shared file and writes there when Android allows it:

- `Download/…`: any file type
- `Pictures/…` and `DCIM/…`: image results
- `Documents/…`: non-media results (e.g. PDF)

If the original folder can't be found or isn't allowed (for example a PDF made from a photo in `Pictures`), Fliq saves to `Download/Fliq` and tells you.

## Roadmap

- Release signing + GitHub releases
- Cast receiver (Chrome + DLNA) and the GameCube emulator are separate apps

## Releasing

Pushing a tag builds a signed APK and publishes it on the Releases page. One-time setup:

1. Make your own signing key and **back it up** (if you lose it you can't update the app):
   ```
   keytool -genkeypair -v -keystore fliq-release.jks -alias fliq -keyalg RSA -keysize 4096 -validity 10000
   ```
2. In the repo go to Settings, Secrets and variables, Actions, and add four secrets:
   - `KEYSTORE_B64`: output of `base64 -w0 fliq-release.jks`
   - `KEYSTORE_PASSWORD`: the keystore password
   - `KEY_ALIAS`: `fliq`
   - `KEY_PASSWORD`: the key password (same as the keystore password if you pressed Enter)
3. Release: `git tag v0.3.0 && git push origin v0.3.0`

## Build

GitHub Actions builds an APK on every push (see the **Actions** tab, artifact `Fliq-debug-apk`).

Locally, with the Android SDK installed:

```
./gradlew :app:assembleDebug
```

Min Android 10 (API 29).
