import { useEffect, useRef, useState, type ChangeEvent } from "react";
import type { IScannerControls } from "@zxing/browser";
import { Camera, Upload, CheckCircle2, Square } from "lucide-react";
import { api, type Event, type CheckInResult } from "./api";

export function CheckInDesk({
  event,
  onCheckedIn,
}: {
  event: Event;
  onCheckedIn: () => void;
}) {
  const [payload, setPayload] = useState(""),
    [error, setError] = useState(""),
    [result, setResult] = useState<CheckInResult | null>(null),
    [busy, setBusy] = useState(false),
    [camera, setCamera] = useState(false);
  const video = useRef<HTMLVideoElement>(null),
    controls = useRef<IScannerControls | null>(null),
    generation = useRef(0);
  function stop() {
    generation.current++;
    controls.current?.stop();
    controls.current = null;
    setCamera(false);
  }
  useEffect(
    () => () => {
      generation.current++;
      controls.current?.stop();
    },
    [],
  );
  function accept(text: string) {
    setResult(null);
    setError("");
    if (!/^seatlock:[a-f0-9]{64}$/.test(text)) {
      setPayload("");
      setError("This QR is not a SeatLock pass.");
      return;
    }
    setPayload(text);
  }
  async function scan() {
    setError("");
    setResult(null);
    setCamera(true);
    const current = ++generation.current;
    try {
      const { BrowserQRCodeReader } = await import("@zxing/browser");
      if (current !== generation.current) return;
      const reader = new BrowserQRCodeReader();
      const control = await reader.decodeFromVideoDevice(
        undefined,
        video.current!,
        (decoded, _, scanner) => {
          if (decoded && current === generation.current) {
            scanner.stop();
            stop();
            accept(decoded.getText());
          }
        },
      );
      if (current !== generation.current) control.stop();
      else controls.current = control;
    } catch {
      if (current === generation.current) {
        setError(
          "Camera is unavailable or permission was denied. Upload a QR image or paste the pass code.",
        );
        stop();
      }
    }
  }
  async function readFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    stop();
    setError("");
    setResult(null);
    setPayload("");
    if (file.size > 5 * 1024 * 1024) {
      setError("Choose a QR image smaller than 5 MB.");
      return;
    }
    setBusy(true);
    const url = URL.createObjectURL(file);
    try {
      const { BrowserQRCodeReader } = await import("@zxing/browser");
      accept(
        (await new BrowserQRCodeReader().decodeFromImageUrl(url)).getText(),
      );
    } catch {
      setError(
        "No readable QR code was found. Try a clear image or paste the pass code.",
      );
    } finally {
      URL.revokeObjectURL(url);
      setBusy(false);
    }
  }
  async function checkIn() {
    setBusy(true);
    setError("");
    setResult(null);
    try {
      const value = await api<CheckInResult>(
        `/admin/events/${event.id}/check-in`,
        "POST",
        { qrPayload: payload.trim() },
        true,
      );
      setResult(value);
      setPayload("");
      onCheckedIn();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="checkin-desk">
      <div className="panel-heading">
        <div>
          <h2>Check-in desk</h2>
          <p>Scan a pass, then confirm check-in for {event.title}.</p>
        </div>
      </div>
      <p className="muted">
        Admission opens 2 hours before the start and closes 12 hours after.
        Compare the returned student details with their college ID.
      </p>
      <div className="scanner-actions">
        <button
          className="button secondary"
          disabled={busy}
          onClick={camera ? stop : scan}
        >
          {camera ? <Square size={18} /> : <Camera size={18} />}{" "}
          {camera ? "Stop camera" : "Scan with camera"}
        </button>
        <label className="button secondary upload-button">
          <Upload size={18} />
          Read QR image
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp"
            disabled={busy || camera}
            onChange={readFile}
            aria-label="Upload QR image"
          />
        </label>
      </div>
      <video
        ref={video}
        className={`scanner-video ${camera ? "active" : ""}`}
        muted
        playsInline
      />
      <label className="pass-input">
        Pass code
        <input
          value={payload}
          onChange={(e) => {
            setPayload(e.target.value);
            setResult(null);
          }}
          placeholder="seatlock:…"
          autoComplete="off"
          spellCheck={false}
        />
      </label>
      <button
        className="button primary"
        disabled={busy || !payload.trim()}
        onClick={checkIn}
      >
        {busy ? "Checking…" : "Confirm check-in"}
      </button>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {result && (
        <div className="success-message" role="status">
          <CheckCircle2 size={22} />
          <div>
            <strong>
              {result.alreadyCheckedIn
                ? "Already checked in — no duplicate recorded"
                : "Check-in confirmed"}
            </strong>
            <p>
              {result.registration.studentName} ·{" "}
              {result.registration.studentNumber}
              {result.registration.seatCode &&
                ` · Seat ${result.registration.seatCode}`}
            </p>
          </div>
        </div>
      )}
    </section>
  );
}
