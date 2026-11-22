# Fliq converter server

Tiny LibreOffice server that the Fliq app calls for **Word / Excel / PowerPoint → PDF** and **PDF → Word**.
Images, SVG and PDF → image conversions never use it; those stay offline on the phone.

- `POST /convert?to=pdf|docx` raw file in the body, headers `X-Filename` and `X-Token`
- `GET /check` verifies the token (the app's "Test" button)
- Files are processed in a temp folder and deleted right after the response.
- Max upload 40 MB (`MAX_MB`), one conversion at a time.

You must set `FLIQ_TOKEN` to a long random string. The server refuses to work without it.

## Option 1: Google Cloud Run (free tier, scales to zero)

Needs a Google Cloud project with a billing account (card). At the time of writing the free tier is
2 million requests, 180,000 vCPU-seconds and 360,000 GiB-seconds per month
([pricing](https://cloud.google.com/run/pricing)). Add a budget alert to be safe.

```
cd server
gcloud run deploy fliq-convert --source . --region us-central1 \
  --memory 2Gi --cpu 1 --concurrency 1 --max-instances 1 --timeout 300 \
  --allow-unauthenticated --set-env-vars FLIQ_TOKEN=PUT-A-LONG-RANDOM-STRING-HERE
```

It prints a `https://….run.app` URL. After a quiet period the first request takes a few seconds longer (cold start).

## Option 2: your own PC (free, no card)

```
docker build -t fliq-convert server
docker run -p 8080:8080 -e FLIQ_TOKEN=PUT-A-LONG-RANDOM-STRING-HERE fliq-convert
cloudflared tunnel --url http://localhost:8080
```

The tunnel prints an `https://….trycloudflare.com` URL (it changes every run). The PC must stay on.

## In the app

Open Fliq from the launcher, paste the URL and the token, tap **Test**, then **Save**.

## Notes

- PDF → Word keeps text, but complex layouts and tables can come out messy; that is a LibreOffice limit.
- Excel and PowerPoint → Word is not possible directly, so only PDF is offered for them.
