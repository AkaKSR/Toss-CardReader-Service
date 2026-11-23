package com.toss.cardreader.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.toss.cardreader.CardReaderApi
import com.toss.cardreader.CardReaderClient
import com.toss.cardreader.CardReaderError
import com.toss.cardreader.CardReaderException
import com.toss.cardreader.CardTypes
import com.toss.cardreader.ServiceStatus
import com.toss.cardreader.Tracks
import java.util.concurrent.Executors

/**
 * Small client of the Toss Card Reader Service, used to try the interface on the device: connect, status,
 * one card wait, read. It shows only masked values and logs only events and codes, never card data.
 * adb: am start -n <package>/com.toss.cardreader.sample.SampleActivity --ez wait true  (connects, then waits)
 */
class SampleActivity : Activity(), CardReaderApi.ConnectionListener {
    private lateinit var out: TextView
    private val reader by lazy { CardReaderClient(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var waitOnConnect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 16f; setPadding(32, 32, 32, 32) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("상태") { worker.execute { status() } })
            addView(button("카드 대기") { worker.execute { waitForCard() } })
            addView(button("취소") { worker.execute { call("cancel") { reader.cancel() } } })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(ScrollView(context).apply { addView(out) })
        })
        waitOnConnect = intent.getBooleanExtra("wait", false)
        show("bind=${reader.connect(this)}")
    }

    override fun onDestroy() {
        worker.execute { runCatching { reader.cancel() }; reader.disconnect() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onConnected() {
        show("connected")
        worker.execute {
            call("version") { show("version=${reader.version()}") }
            status()
            if (waitOnConnect) waitForCard()
        }
    }

    override fun onDisconnected() = show("disconnected")

    private fun status() = call("status") {
        val s = reader.status()
        show("status backend=${s.backend} connected=${s.connected} session=${s.session} busy=${s.busy} icCard=${s.icCard}")
    }

    private fun waitForCard() = call("waitForCard") {
        reader.waitForCard(CardTypes.IC or CardTypes.MSR, 30, object : CardReaderApi.CardListener {
            override fun onCardDetected(type: Int) {
                show("detected ${if (type == CardTypes.IC) "IC" else "MSR"}")
                worker.execute { if (type == CardTypes.IC) readIc() else readMsr() }
            }

            override fun onError(code: Int, message: String?) = show("wait ended ${CardReaderError.name(code)}")
        })
        show("waiting 30 s for IC insert or MSR swipe")
    }

    private fun readIc() = call("readIc") {
        val ic = reader.readIc()
        show(
            "IC read: atr=${ic.atr?.length?.div(2) ?: 0} bytes, aid=${ic.aid ?: "-"}, label=${ic.appLabel ?: "-"}, " +
                "pan=${mask(ic.pan)}, fields=${ic.fields.size}${ic.note?.let { ", note=$it" } ?: ""}",
            log = "IC read: atr=${ic.atr != null} pan=${ic.pan != null} fields=${ic.fields.size} note=${ic.note != null}",
        )
    }

    private fun readMsr() = call("readMsr") {
        val t = reader.readMsr()
        val pan = Tracks.track2(t.track2)?.pan ?: Tracks.track1(t.track1)?.pan
        val tracks = listOf(t.track1, t.track2, t.track3).map { it != null }
        show("MSR read: tracks=$tracks, pan=${mask(pan)}", log = "MSR read: tracks=$tracks pan=${pan != null}")
    }

    private fun call(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CardReaderException) {
            show("$what failed: ${CardReaderError.name(e.code)}")
        }
    }

    // Card values appear masked on screen only; [log] is what logcat gets and carries none.
    private fun show(line: String, log: String = line) {
        Log.i(TAG, log)
        main.post { out.append(line + "\n") }
    }

    private fun mask(pan: String?): String = if (pan == null || pan.length < 4) "-" else "****" + pan.takeLast(4)

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "CardReaderSample"
    }
}
