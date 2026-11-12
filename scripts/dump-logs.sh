#!/usr/bin/env bash
# Copies the monthly CSV logs and the yearly daily summary files from the MicroSD card over USB serial.
# Usage: ./scripts/dump-logs.sh [port]
#        ./scripts/dump-logs.sh --config [port]   prints the active configuration (CONFIG command)
# The board answers on USB power only, while it is awake. Press KEY to wake it first.
set -euo pipefail
umask 077

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${REPO_ROOT}/sd_logs"
BAUD=115200
TIMEOUT_S=20

MODE=dump
if [[ "${1:-}" == "--config" ]]; then
  MODE=config
  shift
fi
PORT="${1:-}"
if [[ -z "${PORT}" ]]; then
  for candidate in /dev/cu.usbmodem* /dev/cu.usbserial* /dev/cu.wchusbserial*; do
    if [[ -e "${candidate}" ]]; then
      PORT="${candidate}"
      break
    fi
  done
fi
if [[ -z "${PORT}" ]]; then
  echo "Error: No ESP32-S3 USB serial port found. Connect the board or pass the port as argument 1." >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"
chmod 0700 "${OUT_DIR}"

exec python3 -I - "${PORT}" "${BAUD}" "${OUT_DIR}" "${TIMEOUT_S}" "${MODE}" <<'PY'
import os, re, subprocess, sys, time, zlib

port, baud, out_dir, timeout_s = sys.argv[1], int(sys.argv[2]), sys.argv[3], float(sys.argv[4])
mode = sys.argv[5]
NAME = re.compile(r"^(?:climate_v[23]_20\d\d(?:0[1-9]|1[0-2])|daily_v1_20\d\d)\.csv$")
FILE_LINE = re.compile(r"^FILE ((?:climate_v[23]_20\d\d(?:0[1-9]|1[0-2])|daily_v1_20\d\d)\.csv) (\d{1,9})$")
BEGIN_LINE = re.compile(r"^BEGIN ((?:climate_v[23]_20\d\d(?:0[1-9]|1[0-2])|daily_v1_20\d\d)\.csv) (\d{1,9})$")
MAX_FILE_BYTES = 16 * 1024 * 1024

subprocess.run(["stty", "-f" if sys.platform == "darwin" else "-F", port, str(baud), "raw", "-echo"], check=True)
fd = os.open(port, os.O_RDWR | os.O_NOCTTY)
buf = bytearray()

def fill(deadline):
    import select
    left = deadline - time.time()
    if left <= 0:
        raise SystemExit("Error: timeout. Check USB power and press KEY to wake the board.")
    if select.select([fd], [], [], left)[0]:
        buf.extend(os.read(fd, 4096))

def line(deadline):
    while b"\n" not in buf:
        fill(deadline)
    i = buf.index(b"\n")
    text = bytes(buf[:i]).decode("ascii", "replace").strip()
    del buf[: i + 1]
    return text

def command(text):
    os.write(fd, text.encode("ascii") + b"\n")

def reply_line(deadline):
    # Skip boot log lines: only protocol lines count.
    while True:
        text = line(deadline)
        if FILE_LINE.match(text) or BEGIN_LINE.match(text) or text.startswith(("END LIST", "ERR ")):
            return text

def drain():
    import select
    while select.select([fd], [], [], 0.2)[0]:
        os.read(fd, 4096)
    buf.clear()

drain()
if mode == "config":
    # CONFIG answers from the RTC copy: key=value lines, "SOURCE SD|DEFAULT", then the CRC32 line.
    CONFIG_LINE = re.compile(r"^(?:[a-z0-9_]+=-?\d+(?:\.\d+)?|SOURCE (?:SD|DEFAULT))$")
    command("CONFIG")
    deadline = time.time() + timeout_s
    body = bytearray()
    while True:
        text = line(deadline)
        if text.startswith("ERR "):
            raise SystemExit("Error: " + text)
        if text.startswith("CRC32 "):
            break
        if CONFIG_LINE.match(text):
            body.extend(text.encode("ascii") + b"\n")
        else:
            print(f"Warning: ignored line ({text!r})", file=sys.stderr)
    expected = f"CRC32 {zlib.crc32(bytes(body)) & 0xFFFFFFFF:08x}"
    if text != expected:
        raise SystemExit(f"Error: CRC mismatch ({text} / {expected})")
    sys.stdout.write(body.decode("ascii"))
    sys.exit(0)
command("LIST")
files = []
skipped = 0
deadline = time.time() + timeout_s
while True:
    text = reply_line(deadline)
    if text.startswith("ERR "):
        raise SystemExit("Error: " + text)
    if text.startswith("END LIST"):
        break
    match = FILE_LINE.match(text)
    if match and int(match.group(2)) <= MAX_FILE_BYTES:
        files.append((match.group(1), int(match.group(2))))
    elif match:
        print(f"{match.group(1)}: skipped, larger than {MAX_FILE_BYTES} bytes", file=sys.stderr)
        skipped += 1

if not files:
    print("No log files on the card.")
    sys.exit(1 if skipped else 0)

failed = skipped
for name, listed_size in files:
    command("DUMP " + name)
    deadline = time.time() + timeout_s
    head = reply_line(deadline)
    if not head.startswith("BEGIN "):
        print(f"{name}: {head}", file=sys.stderr)
        failed += 1
        continue
    match = BEGIN_LINE.match(head)
    if not match or match.group(1) != name or int(match.group(2)) > MAX_FILE_BYTES:
        print(f"{name}: bad header ({head})", file=sys.stderr)
        drain()
        failed += 1
        continue
    got_name, size = match.group(1), int(match.group(2))
    if size != listed_size:
        print(f"{name}: warning, size changed from {listed_size} to {size} after LIST", file=sys.stderr)
    deadline = time.time() + timeout_s
    while len(buf) < size:
        fill(deadline)
    data = bytes(buf[:size])
    del buf[:size]
    tail = line(time.time() + timeout_s)
    expected = f"CRC32 {zlib.crc32(data) & 0xFFFFFFFF:08x}"
    if got_name != name or tail != expected:
        print(f"{name}: CRC mismatch or transfer error ({tail} / {expected})", file=sys.stderr)
        drain()
        failed += 1
        continue
    target = os.path.join(out_dir, name)
    with open(target + ".part", "wb") as handle:
        handle.write(data)
    os.replace(target + ".part", target)
    print(f"{name}: {size} bytes, {expected}")

sys.exit(1 if failed else 0)
PY
