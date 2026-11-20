package com.sahl.contacts

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast

/** Small shared contact actions used by several screens. */
object Actions {
    fun whatsapp(a: Activity, n: String) {
        val d = Norm.international(n, Sahl.countryCode(a))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            try { a.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
        }
        if (pkg == null) { Toast.makeText(a, R.string.no_whatsapp, Toast.LENGTH_SHORT).show(); return }
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$d")).setPackage(pkg)) } catch (e: Exception) { }
    }

    fun share(a: Activity, lookup: String) {
        if (lookup.isEmpty()) return
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup)
        val i = Intent(Intent.ACTION_SEND).setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { a.startActivity(Intent.createChooser(i, a.getString(R.string.share))) } catch (e: Exception) { }
    }

    fun copy(a: Activity, s: String) {
        a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("contact", s))
        Toast.makeText(a, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    fun email(a: Activity, e: String) {
        try { a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$e"))) } catch (x: Exception) { }
    }
}
