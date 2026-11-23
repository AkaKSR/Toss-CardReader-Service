# Toss Front reader link (our own notes)

The Toss Front 1.5 IC slot and magnetic stripe reader sit behind a separate secure processor (SP). The app talks
to it over the UART `/dev/ttyS3`. This document describes the link as we implemented it in `hw/sp/`. It is our own
description of observed behaviour; it contains no vendor code.

## Line settings

- `/dev/ttyS3` (`/dev/ttyMT1` is a symlink to it), opened `O_RDWR | O_NOCTTY | O_NONBLOCK`.
- 921600 baud, 8 data bits, no parity, 1 stop bit, raw mode, no flow control, `CLOCAL | CREAD`.
- The speed never changes during card use. No sysfs node and no reset line are touched.
- `O_NOCTTY` matters: without it a shell that opens the port can make it its controlling terminal, and reads from
  another process group stop on `SIGTTIN`.

## Link check

Send the single byte `51` (`Q`). A ready processor answers `41` (`A`). The app does this once when it opens the port.

## Frame

Both directions use the same frame:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | Marker `DE` |
| 1 | 1 | Sequence number, incremented per request |
| 2 | 1 | Group (`12` for card commands) |
| 3 | 1 | Command |
| 4 | 2 | Payload length, little endian |
| 6 | n | Payload |
| 6+n | 1 | XOR of every preceding byte |

Largest frame: 2048 bytes. A reply repeats the request's first four bytes (marker, sequence, group, command)
unchanged. The app rejects a reply whose header differs, whose length does not add up, or whose XOR is wrong, and
then closes the link.

## Payload

Request payload: an argument count byte, then each argument as a little-endian u16 length followed by its bytes.
Text arguments carry a trailing zero byte (`SCC0\0` is 5 bytes). Integers are 4-byte little endian.

Reply payload: a state byte, an argument count byte, then arguments in the same length-prefixed form. The first
argument is a 4-byte signed result. `-12001` means no card. The state byte is `00` on the first reply after the
processor (re)started and `01` afterwards; it is informational, not an error. On the device the same no-card reply
was seen both ways: `... 08 00 00 01 04 00 1F D1 FF FF 05` and `... 08 00 01 01 04 00 1F D1 FF FF 04`.

## Commands the app sends (allowlist)

All in group `12`. The `SpCommand` enum is the only thing `SpProtocol.frame` accepts, so no other command can be built.

| Cmd | Purpose | Arguments | Reply after the result |
|---|---|---|---|
| `0A` | IC slot status | `SCC0` | none. Result `>= 0` = card present, `-12001` = empty |
| `04` | IC power on, read ATR | `SCC0`, flags `0`, buffer size `256` | `[ATR length][ATR]` |
| `06` | IC APDU exchange | `SCC0`, control `32`, count `1`, APDU record | `[len u16][response][SW1][SW2]` |
| `07` | IC power off | `SCC0` | none |
| `01` | Magnetic stripe read | track sizes `79`, `40`, `107` | length + data for tracks 1, 2, 3. Result `> 0` = swipe |

APDU record: `CLA INS P1 P2`, Lc as u16 LE, data, Le as u16 LE. Only short APDUs are accepted. The EMV reader on
top only sends SELECT, READ RECORD, GET RESPONSE and GET PROCESSING OPTIONS.

Example status request (sequence 0): `DE 00 12 0A 08 00 01 05 00 53 43 43 30 00 A9`.
Reply with no card: `DE 00 12 0A 08 00 00 01 04 00 1F D1 FF FF 05`.

## Never sent (denylist)

The processor also supports commands this app must never send. They are not representable in `SpCommand`, and a
unit test asserts the guard rejects them:

- Firmware: bootloader handshake, firmware download, upgrade and baud switching for download.
- Keys and certificates (group `14`): key storage, export, destruction, certificate and private key write/delete.
- Device identity (group `40`): identity key and certificate storage.
- Applets (group `17`): install, run, remove.
- System settings (group `11`): parameter write, power management, time set, low-level control.
- Card group extras: `08` (low-level slot control) and `09` (encrypted track mode).
- Reset/wake lines and any `sp_*` sysfs write.

## Concurrency

`SpLink` is the only owner of the port. Each request and its reply run under one lock, so the screen and the
background watcher can never interleave bytes. The reader is reference counted; the port closes when the last
user disconnects. The existing hand-over (the watcher cancels before a screen arms) keeps at most one card wait
running.

## Fallback

`PayHardware` binds the stock payment service first. Only when that service is not installed does it use the
direct link. Both paths feed the same `CardEvents` callbacks, so the rest of the app is unchanged.
