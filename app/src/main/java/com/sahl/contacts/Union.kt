package com.sahl.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/** What Sahl Contacts asks from the other Sahl apps. */
object Union {
    data class CallRow(val number: String, val type: Int, val date: Long, val duration: Long)

    private fun installed(c: Context, pkg: String) =
        try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    fun hasPhone(c: Context) = installed(c, "com.sahl.phone")

    /** Call through Sahl Phone (direct call, SIM choice) — or the normal dialer if it isn't installed. */
    fun call(a: Activity, number: String) {
        val i = if (hasPhone(a)) Intent("com.sahl.phone.action.CALL").setPackage("com.sahl.phone").putExtra("number", number)
        else Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        try { a.startActivity(i) } catch (e: Exception) {
            try { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) } catch (x: Exception) { }
        }
    }

    fun sms(a: Activity, number: String) {
        try { a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))) } catch (e: Exception) { }
    }

    /** Numbers you called or that called you, newest first (from Sahl Phone). */
    fun recentNumbers(c: Context, limit: Int = 30): List<String> {
        val out = ArrayList<String>()
        try {
            c.contentResolver.query(Uri.parse(Sahl.PHONE_UNION + "/recent?limit=$limit"), null, null, null, null)?.use {
                while (it.moveToNext()) it.getString(0)?.let { n -> out.add(n) }
            }
        } catch (e: Exception) { }
        return out
    }

    data class MsgRow(val number: String, val incoming: Boolean, val date: Long, val body: String)

    fun hasMessages(c: Context) = installed(c, "com.sahl.messages")

    /** Text messages with one person (from Sahl Messages). */
    fun messages(c: Context, numbers: List<String>, limit: Int = 30): List<MsgRow> {
        val out = ArrayList<MsgRow>()
        for (n in numbers) try {
            c.contentResolver.query(Uri.parse("content://com.sahl.messages.union/history/" + Uri.encode(n) + "?limit=$limit"), null, null, null, null)?.use {
                while (it.moveToNext()) out.add(MsgRow(n, it.getInt(1) == 1, it.getLong(0), it.getString(2) ?: ""))
            }
        } catch (e: Exception) { }
        return out.distinctBy { it.date }.sortedByDescending { it.date }.take(limit)
    }

    /** Call history with one person (any of their numbers). */
    fun history(c: Context, numbers: List<String>, limit: Int = 20): List<CallRow> {
        val out = ArrayList<CallRow>()
        for (n in numbers) try {
            c.contentResolver.query(Uri.parse(Sahl.PHONE_UNION + "/history/" + Uri.encode(n) + "?limit=$limit"), null, null, null, null)?.use {
                while (it.moveToNext()) out.add(CallRow(it.getString(0) ?: n, it.getInt(1), it.getLong(2), it.getLong(3)))
            }
        } catch (e: Exception) { }
        return out.distinctBy { it.date }.sortedByDescending { it.date }.take(limit)
    }
}
