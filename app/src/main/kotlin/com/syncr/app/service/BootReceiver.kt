package com.syncr.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts [SyncService] immediately on device boot.
 *
 * Direct-boot note: this receiver is NOT registered with
 * directBootAware="true", so it fires only after the first user unlock
 * (credential-encrypted storage is unavailable before unlock, which is
 * required by CredentialManager to read the SMB password from KeyStore).
 *
 * Samsung One UI delivers BOOT_COMPLETED reliably after the user unlocks.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "BOOT_COMPLETED — starting SyncService")
        val serviceIntent = Intent(context, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
