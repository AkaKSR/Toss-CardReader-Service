package com.toss.cardreader.service

import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallerCheckTest {
    // Stand-in certificates; their SHA-256 values are computed, not real keys.
    private val good = "allowed certificate".toByteArray()
    private val other = "other certificate".toByteArray()
    private val check = CallerCheck(setOf(CallerCheck.sha256(good))) { uid ->
        when (uid) {
            1001 -> listOf(listOf(good))
            1002 -> listOf(listOf(other))
            1003 -> listOf(listOf(good, other)) // two signers, one unknown
            1004 -> listOf(listOf(good), listOf(other)) // shared uid with one foreign package
            1005 -> listOf(emptyList())
            else -> null
        }
    }

    @Test
    fun onlyAllowedSignersPass() {
        assertTrue(check.allowed(1001))
        assertFalse(check.allowed(1002))
        assertFalse(check.allowed(1003))
        assertFalse(check.allowed(1004))
        assertFalse(check.allowed(1005))
        assertFalse(check.allowed(1999))
    }

    @Test
    fun allowListHoldsTheReleaseKeyAndTheCardAppKey() {
        assertEquals(2, CallerCheck.ALLOWED_SIGNERS.size)
        assertTrue(CallerCheck.ALLOWED_SIGNERS.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun readsSignersFromThePackageManager() {
        val app = RuntimeEnvironment.getApplication()
        val spm = shadowOf(app.packageManager)
        fun install(pkg: String, uid: Int, cert: ByteArray) {
            spm.installPackage(PackageInfo().apply {
                packageName = pkg
                @Suppress("DEPRECATION")
                signatures = arrayOf(Signature(cert))
            })
            spm.setPackagesForUid(uid, pkg)
        }
        install("com.example.friend", 20_001, good)
        install("com.example.stranger", 20_002, other)
        val real = CallerCheck.of(app, setOf(CallerCheck.sha256(good)))
        assertTrue(real.allowed(20_001))
        assertFalse(real.allowed(20_002))
        assertFalse(real.allowed(20_003))
    }
}
