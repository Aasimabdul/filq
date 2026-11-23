# Fliq optional converter server

This optional LibreOffice server is for file types that Fliq does not convert fully offline, especially legacy binary Word/PowerPoint files and spreadsheet formats that the server supports. The app's DOCX → PDF, PPTX → PDF, PDF → DOCX and image/PDF-page conversions use local code instead.

- `POST /convert?to=pdf|docx` accepts the raw file body with `X-Filename` and `X-Token` headers.
- `GET /check` checks the token; Fliq's separate server settings screen can test connectivity.
- Files are processed in a temporary directory and cleaned up after the response where the server implementation permits.
- The server enforces an upload limit (default 40 MB via `MAX_MB`) and processes one conversion at a time.

Set `FLIQ_TOKEN` to a long, random secret. Never commit it to source control. Prefer HTTPS for public deployments; a server receives the contents of every file sent for conversion.

## Option 1: Google Cloud Run

Requires a Google Cloud project and billing configuration. Check current pricing and configure budget alerts before deploying. Example:

```sh
cd server
gcloud run deploy fliq-convert --source . --region us-central1 \
  --memory 2Gi --cpu 1 --concurrency 1 --max-instances 1 --timeout 300 \
  --allow-unauthenticated --set-env-vars FLIQ_TOKEN=PUT-A-LONG-RANDOM-STRING-HERE
```

The deployment returns an HTTPS URL. The first request after an idle period may be slower due to cold start.

## Option 2: Run on your own PC

```sh
docker build -t fliq-convert server
docker run --rm -p 8080:8080 -e FLIQ_TOKEN=PUT-A-LONG-RANDOM-STRING-HERE fliq-convert
```

For remote access, configure a trusted HTTPS reverse proxy or tunnel. The PC must remain on while you use the server. Do not expose a public server without understanding its access controls and costs.

## Configure Fliq

1. Open Fliq.
2. Tap **Optional server settings**.
3. Enter the HTTPS server URL and the same token configured on the server.
4. Tap **Test connection**, then **Save settings**.

The server settings are on a separate screen; the home screen does not show the URL or token fields.

## Notes

- DOCX/PPTX local conversions are text-first and may not preserve layout or embedded media.
- Legacy Office and spreadsheet support depends on what LibreOffice can read and export. Some input/output combinations may fail.
- Never send sensitive documents to a server unless you trust its operator and data-handling practices.
