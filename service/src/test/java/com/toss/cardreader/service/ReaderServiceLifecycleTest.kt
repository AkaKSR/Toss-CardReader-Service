package com.toss.cardreader.service

import android.content.Intent
import com.toss.cardreader.CardReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderServiceLifecycleTest {
    private val bind = Intent(CardReader.ACTION_BIND)

    private fun closedCount() =
        ShadowLog.getLogsForTag("ReaderService").count { it.msg == "no clients: reader closed" }

    // Android calls onUnbind again after a rebind only if the previous onUnbind returned true (ActiveServices doRebind).
    @Test
    fun everyLastUnbindClosesTheReaderAfterARebind() {
        ShadowLog.clear()
        val controller = Robolectric.buildService(ReaderService::class.java, bind).create().bind()
        val service = controller.get()

        assertTrue(service.onUnbind(bind))
        assertEquals(1, closedCount())

        controller.rebind()
        assertTrue(service.onUnbind(bind))
        assertEquals(2, closedCount())
    }
}
