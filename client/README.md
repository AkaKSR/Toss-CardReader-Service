# Toss Card Reader client library

`toss-cardreader-client-1.0.0.aar` lets an app use the Toss Card Reader Service (`com.toss.cardreader.service`):
wait for an IC insert or a magnetic swipe, then read the card. Interface details: [docs/reader_service_api.md](../docs/reader_service_api.md).

## Requirements

- The service is installed on the device.
- Your app is signed with a key on the service's allow list. Other apps can bind but every call fails with
  `NOT_ALLOWED`.
- minSdk 26. The AAR uses `kotlinx.parcelize` classes and needs the Kotlin standard library (any Kotlin Android app has it).

## Add it

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/toss-cardreader-client-1.0.0.aar"))
}
```

The library's manifest adds the `<queries>` entry for the service package; nothing else is needed in yours.

## Use it

Reader calls block on IPC: make them on a worker thread. Callbacks arrive on a binder thread.

```kotlin
val reader = CardReaderClient(context)
val worker = Executors.newSingleThreadExecutor()

if (!reader.connect(object : CardReaderApi.ConnectionListener {
        override fun onConnected() = worker.execute { waitForCard() }
        override fun onDisconnected() { /* service restarted; onConnected follows */ }
    })) {
    // service not installed
}

fun waitForCard() {
    try {
        reader.waitForCard(CardTypes.IC or CardTypes.MSR, 30, object : CardReaderApi.CardListener {
            override fun onCardDetected(type: Int) = worker.execute {
                try {
                    if (type == CardTypes.IC) {
                        val ic = reader.readIc()      // ATR, AID, label, PAN, expiry, holder, EMV fields
                        show(ic)
                    } else {
                        val msr = reader.readMsr()    // tracks 1-3
                        val pan = Tracks.track2(msr.track2)?.pan ?: Tracks.track1(msr.track1)?.pan
                        show(pan)
                    }
                } catch (e: CardReaderException) {
                    // e.code: NO_CARD, NOT_READY, ...
                }
            }

            override fun onError(code: Int, message: String?) {
                // TIMEOUT, CANCELLED, BUSY, READER_ERROR, ...
            }
        })
    } catch (e: CardReaderException) {
        if (e.code == CardReaderError.BUSY) { /* another app is using the reader; try again later */ }
    }
}

// When done (e.g. onDestroy): reader.cancel() if waiting, then reader.disconnect().
```

After an IC read the card is powered off. To wait until it is pulled out, poll `reader.status().icCard` until it is
`ServiceStatus.CARD_ABSENT`.

## Card data

Do not log card values. The data classes print only which fields are present, so logging the objects themselves
is safe; their properties are not.
