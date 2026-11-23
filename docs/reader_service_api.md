# Toss Card Reader Service: interface v1

`com.toss.cardreader.service` is a headless driver app that talks to the Toss Front card reader and lets other apps
use it over AIDL. It is a separate app from the card reader app (`com.toss.cardapp`).

The card reader app (1.3.0 and later) picks its reader in this order:

1. The reader service, when `com.toss.cardreader.service` is installed.
2. Otherwise the stock Sunmi payment service (`com.sunmi.pay.hardware_v3`), when installed.
3. Otherwise its own direct link over `/dev/ttyS3`.

Steps 2 and 3 are the same driver code the service uses (module `:reader`).

## Modules

| Module | Output | Purpose |
|---|---|---|
| `:service` | `toss-cardreader-service-1.0.0.apk` | The service app. No screen; a minimal-priority notification while it runs. |
| `:client` | `toss-cardreader-client-1.0.0.aar` | AIDL, data classes, `CardReaderClient` Kotlin wrapper. See [client/README.md](../client/README.md). |
| `:reader` | (inside the service and the card app) | Driver: Sunmi service first, else direct link; EMV read; one-session rules |
| `:app` | `toss-front-card-app-1.3.0.apk` | Card reader app, signed with its own key as before |
| `:testclient` | `toss-cardreader-sample-*.apk` | Small sample client: `trusted` and `second` (release key, two packages), `foreign` (unrelated key). |

## Binding and who may call

- Intent action `com.toss.cardreader.action.BIND`, package `com.toss.cardreader.service`. The client library's
  manifest adds the `<queries>` entry Android 11+ needs to see the service.
- Access is decided per call from `Binder.getCallingUid()`: every package of that uid must be signed only by
  certificates whose SHA-256 is in the service's allow list. Anything else gets `SecurityException`
  (`CardReaderError.NOT_ALLOWED` in the wrapper). Binding itself succeeds, but no call works.
- Allow list (`CallerCheck.ALLOWED_SIGNERS`): the Toss Card Reader release key (service, sample clients) and the
  card reader app's key (in use since 1.0.0, so the app updates in place and keeps its data). Adding a client
  signed with another key means adding its certificate SHA-256 there and releasing the service again.
- There is no Android permission for this: a signature permission would only admit apps signed with the service's
  own key, and the card reader app has to keep its older key.

## Reader link

- Stock Sunmi payment service (`com.sunmi.pay.hardware_v3`) first when it is installed; otherwise the direct link over
  `/dev/ttyS3` described in [sp_protocol.md](sp_protocol.md). Same rule and same command allowlist as the card app
  (IC status, power on, APDU exchange, power off, stripe read). No firmware, key, certificate, PIN or settings commands.
- The link opens on the first allowed `getStatus` or `waitForCard` and closes when the last client unbinds. The reader is polled only
  while a client has a card wait running; a bound client with no wait causes no reader traffic.
- Before using the port the service listens for 300 ms. Traffic on a line nobody asked anything means another
  process (for example the card app with its own direct link) has the port open; the service then does not touch it
  and answers `BUSY`. It tries again on the next `waitForCard`.

**Do not run a card app with its own direct link and the service at the same time.** Card app 1.3.0 uses the service
whenever the service is installed, so this concerns 1.2.0 and older (and any other program that opens the port).
While such an app is waiting for cards (its screen is open or its auto-launch watcher is on), service clients get
`BUSY`. If the card app
opens the port while the service holds it, both sides see broken replies; the service closes its link, ends the wait
with `READER_ERROR`, and reports `BUSY` on the next try. With the Sunmi service installed both apps go through it,
and it reports a second app's card wait as `BUSY`.

## Interface (`com.toss.cardreader.ICardReaderService`)

| Call | Result |
|---|---|
| `int getVersion()` | `1` |
| `ServiceStatus getStatus()` | backend (none / Sunmi / direct), connected, session state, busy, IC slot (absent / present / unknown) |
| `int waitForCard(int types, int timeoutSec, ICardReaderCallback cb)` | 0 = started the one card wait, else an error code. `types` = `CardTypes.IC` (2) and/or `CardTypes.MSR` (1); timeout 1..120 s |
| `IcCardData readIc()` | After `onCardDetected(IC)`: ATR, AID, app label, PAN, expiry, holder, service code, public EMV fields (tag, name, value), note. Powers the card off. |
| `MsrCardData readMsr()` | After `onCardDetected(MSR)`: tracks 1-3 of that swipe, once |
| `void cancel()` | Ends the caller's own session |

Callback (`oneway`): `onCardDetected(int type)`, `onError(int code, String message)`.

Errors are codes: `waitForCard` returns one (0 = started), `IcCardData.error` / `MsrCardData.error` carry one
(0 = read), and `onError` gets one. The wrapper turns each into `CardReaderException(code)`:

| Code | Name | Meaning |
|---|---|---|
| 1 | BUSY | Another app has the session, another process holds `/dev/ttyS3`, or the Sunmi service is busy with another app |
| 2 | NOT_READY | No reader link (no Sunmi service and the port cannot be opened) or the service is gone |
| 3 | NO_CARD | `readIc`/`readMsr` without a detected card of that kind in your session |
| 4 | TIMEOUT | The wait ended without a card |
| 5 | CANCELLED | The session was cancelled, replaced by your own new wait, or the reader link went away |
| 6 | INVALID_ARGUMENT | Bad `types` or `timeoutSec` |
| 7 | UNSUPPORTED_CARD | Contactless or other card kind |
| 8 | READER_ERROR | The reader reported an error (message has its code) |
| 9 | NOT_ALLOWED | The calling app's signing certificate is not on the allow list (wrapper only; every service call throws `SecurityException`) |

## Sessions

- One session on the device. It belongs to the calling **process**: another process gets `BUSY`; a new
  `waitForCard` from the same process replaces the old one (the old callback gets `CANCELLED`).
- The session ends on `readIc`/`readMsr`, `cancel`, timeout, error, or when the client process dies.
- A detected card that its owner never reads stops blocking other apps after 30 s.
- The service has no card-removal event. To wait for removal, poll `getStatus().icCard` (one status command per call).

## Card data

- The service keeps card data in memory only for the session and hands it to the session owner. Nothing is stored.
- Logcat gets session events, error codes and field counts only. The data classes' `toString()` prints only which
  fields are present.

## Lifecycle

- Starts on boot and after an update (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) as a foreground service. Until a
  first explicit start or bind after install, Android keeps a freshly installed app in the stopped state and does
  not deliver the boot broadcast; the first bind from a client also starts it.
- Signing: the service and the samples use the release key named in `keystore.properties`; the card app uses its own
  key from `keystore-cardapp.properties`. Neither file nor any key is committed.
