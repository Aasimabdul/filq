"""Fliq converter server: Word / Excel / PowerPoint -> PDF and PDF -> Word, using LibreOffice.

POST /convert?to=pdf|docx   raw file in the body, headers X-Filename (url-encoded) and X-Token
GET  /check                 header X-Token, answers 200 when the token is right
"""
import asyncio
import os
import secrets
import shutil
import subprocess
import tempfile
from urllib.parse import unquote_plus

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, PlainTextResponse
from starlette.background import BackgroundTask

TOKEN = os.environ.get("FLIQ_TOKEN", "")
MAX_BYTES = int(os.environ.get("MAX_MB", "40")) * 1024 * 1024
TIMEOUT = int(os.environ.get("CONVERT_TIMEOUT", "150"))

OFFICE_EXT = {"doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp"}
OUT_MIME = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}

app = FastAPI()
lock = asyncio.Lock()  # LibreOffice handles one conversion at a time


def check_token(x_token: str) -> None:
    if not TOKEN:
        raise HTTPException(503, "Server has no FLIQ_TOKEN set")
    if not secrets.compare_digest(x_token.encode(), TOKEN.encode()):
        raise HTTPException(401, "Wrong token")


def run_soffice(src: str, outdir: str, to: str) -> None:
    cmd = [
        "soffice", "--headless", "--norestore", "--nolockcheck",
        "-env:UserInstallation=file:///tmp/lo_profile",
    ]
    if to == "docx":
        cmd += ["--infilter=writer_pdf_import", "--convert-to", "docx:MS Word 2007 XML"]
    else:
        cmd += ["--convert-to", "pdf"]
    cmd += ["--outdir", outdir, src]
    try:
        subprocess.run(cmd, check=True, timeout=TIMEOUT, capture_output=True)
    except subprocess.TimeoutExpired:
        subprocess.run(["pkill", "-f", "soffice.bin"], check=False)
        raise HTTPException(504, "Conversion took too long")
    except subprocess.CalledProcessError:
        raise HTTPException(422, "Could not convert this file")


@app.get("/")
def health():
    return PlainTextResponse("Fliq converter OK")


@app.get("/check")
def check(x_token: str = Header(default="")):
    check_token(x_token)
    return PlainTextResponse("ok")


@app.post("/convert")
async def convert(
    request: Request,
    to: str,
    x_filename: str = Header(default="file"),
    x_token: str = Header(default=""),
):
    check_token(x_token)
    if to not in OUT_MIME:
        raise HTTPException(400, "Unsupported target")
    name = os.path.basename(unquote_plus(x_filename))
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if to == "docx" and ext != "pdf":
        raise HTTPException(400, "Only PDF can be converted to Word")
    if to == "pdf" and ext not in OFFICE_EXT:
        raise HTTPException(400, "Unsupported file type")

    work = tempfile.mkdtemp(prefix="fliq_")
    try:
        src = os.path.join(work, "in." + ext)  # fixed name: nothing from the client reaches the shell
        size = 0
        with open(src, "wb") as f:
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_BYTES:
                    raise HTTPException(413, "File too big")
                f.write(chunk)
        if size == 0:
            raise HTTPException(400, "Empty file")

        async with lock:
            await asyncio.to_thread(run_soffice, src, work, to)

        out = os.path.join(work, "in." + to)
        if not os.path.exists(out):
            raise HTTPException(422, "Could not convert this file")
        return FileResponse(out, media_type=OUT_MIME[to], background=BackgroundTask(shutil.rmtree, work, True))
    except BaseException:
        shutil.rmtree(work, ignore_errors=True)
        raise
