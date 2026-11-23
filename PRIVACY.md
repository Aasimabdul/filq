# Privacy

Fliq is designed to process supported image conversions and the listed text-first PDF/Office conversions on the device.

## On-device conversions

The following conversion paths are implemented locally:
- Supported image/SVG formats to JPG, PNG, WebP, BMP, or PDF.
- PDF pages to PNG, JPG, or WebP.
- PDF to DOCX by extracting selectable text.
- DOCX to PDF by extracting document text.
- PPTX to PDF by extracting slide text.

These conversions are text-first where noted and do not preserve all original formatting or embedded media. Scanned PDFs require OCR, which is not included.

## Optional conversion server

Legacy Office formats and spreadsheet conversion may use a separately configured conversion server. When used, Fliq uploads the selected file to that server and receives the converted output. Only configure a server you trust, prefer HTTPS, and do not send confidential files unless you understand the server's data-handling practices.

## Data and permissions

Fliq does not advertise analytics or ad tracking as a feature. Android file access is initiated by the user through file sharing/open-with flows. Temporary conversion files may be written to the app's cache and are deleted after processing where implemented; Android or the operating system may retain files the user explicitly saves.

This document describes intended behavior, not a guarantee that every build or third-party server behaves identically. Review the source code and server configuration before using Fliq with sensitive documents.
