# Komunikasi Group — Android V1 PoC-1

Native Kotlin Android proof-of-concept for the agreed Android V1 architecture.

## PoC-1 scope

- Native Android application shell.
- Foreground Service for communication lifecycle.
- Runtime microphone and notification permission handling.
- Online/offline lifecycle proof.
- PTT state proof in the UI.
- Domain contracts separated from transport implementation.
- Recording Contract V3 represented as a `RecordingPort` boundary.
- Transport remains an adapter behind `CommunicationPort`; backend protocol details are not embedded in the UI/service layer.

## Architecture boundary

`UI → Domain Contracts → Communication Adapter → Transport`

`Foreground Service` owns the long-lived Android communication lifecycle.

This PoC intentionally does **not** alter or depend on `main`, `komunikasi-group-v2`, or `komunikasi-group-v3`.

## Next PoC gate

The next implementation step is to replace the PoC transport adapter with the real Web V2 / Backend A1.5 transport contract and then validate end-to-end audio/PTT behavior without collapsing the agreed separation of concerns.
