package com.toss.cardreader.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.security.MessageDigest

/**
 * Who may use the reader: an app whose signing certificates all have an allowed SHA-256 ([ALLOWED_SIGNERS]).
 * Checked on every call from Binder.getCallingUid(), since Android cannot tell the service who bound it.
 * Every package sharing the calling uid must pass.
 */
class CallerCheck(
    private val allowed: Set<String>,
    /** Signing certificates (DER) of each package running as [uid]; null or empty when the uid is unknown. */
    private val signers: (uid: Int) -> List<List<ByteArray>>?,
) {
    fun allowed(uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        val packages = signers(uid)
        if (packages.isNullOrEmpty()) return false
        return packages.all { certs -> certs.isNotEmpty() && certs.all { sha256(it) in allowed } }
    }

    companion object {
        /** SHA-256 of the signing certificates allowed to call (lowercase hex). */
        val ALLOWED_SIGNERS = setOf(
            "1ce1ddcfc412cdea3bb154532306caa33f53afa2312232af164072528217635c", // Toss Card Reader release key (service, sample clients)
            "6540effc71b5ce52092682f5468714400e8b1eb21195f4e5ef8ffbad45d3a710", // Toss CardReader app (com.toss.cardapp) key since 1.0.0
        )

        fun sha256(cert: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }

        fun of(context: Context, allowed: Set<String> = ALLOWED_SIGNERS): CallerCheck {
            val pm = context.packageManager
            return CallerCheck(allowed) { uid ->
                pm.getPackagesForUid(uid)?.map { pkg ->
                    @Suppress("DEPRECATION") // GET_SIGNATURES lists the current signers on every API level we run on
                    val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    info.signatures.orEmpty().map { it.toByteArray() }
                }
            }
        }
    }
}
