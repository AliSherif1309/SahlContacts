package com.sahl.contacts

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.view.View
import android.widget.RemoteViews
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.time.LocalDate
import java.time.ZonedDateTime

/** Hidden contacts: only shown in Sahl Contacts after fingerprint / screen-lock check. */
object Vault {
    private var unlockedUntil = 0L

    fun hidden(c: Context): Set<String> = Sahl.prefs(c).getStringSet("hidden", null)?.toSet() ?: emptySet()
    fun isHidden(c: Context, lookup: String) = lookup.isNotEmpty() && lookup in hidden(c)
    fun set(c: Context, lookup: String, on: Boolean) {
        if (lookup.isEmpty()) return
        val s = hidden(c).toMutableSet()
        if (on) s.add(lookup) else s.remove(lookup)
        Sahl.prefs(c).edit().putStringSet("hidden", s).apply()
    }

    fun unlocked() = System.currentTimeMillis() < unlockedUntil
    fun lock() { unlockedUntil = 0L }

    fun unlock(a: Activity, ok: () -> Unit) {
        val grant = { unlockedUntil = System.currentTimeMillis() + 5 * 60_000L; ok() }
        if (Build.VERSION.SDK_INT < 28) { grant(); return }
        val b = BiometricPrompt.Builder(a).setTitle(a.getString(R.string.unlock_hidden))
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else if (Build.VERSION.SDK_INT == 29) {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        } else {
            b.setNegativeButton(a.getString(R.string.cancel), a.mainExecutor) { _, _ -> }
        }
        try {
            b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { grant() }
            })
        } catch (e: Exception) { }
    }
}

/** Daily birthday check at about 9 AM. */
object Birthdays {
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 7, Intent(c, BirthdayReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        var t = ZonedDateTime.now().withHour(9).withMinute(0).withSecond(0)
        if (t.isBefore(ZonedDateTime.now())) t = t.plusDays(1)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, t.toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, pi)
    }

    fun enabled(c: Context) = Sahl.prefs(c).getBoolean("bday", true)

    /** contactId -> name for today's birthdays. */
    fun today(c: Context): Map<Long, String> {
        val now = LocalDate.now()
        val out = LinkedHashMap<Long, String>()
        c.contentResolver.query(Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME, Event.START_DATE),
            "${Data.MIMETYPE}=? AND ${Event.TYPE}=?", arrayOf(Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY.toString()), null)?.use {
            while (it.moveToNext()) {
                val s = it.getString(2) ?: continue
                val md = s.trim().let { d -> if (d.startsWith("--")) d.drop(2) else d.drop(5) }   // "MM-dd"
                if (md.take(5) == "%02d-%02d".format(now.monthValue, now.dayOfMonth))
                    out[it.getLong(0)] = it.getString(1) ?: ""
            }
        }
        return out
    }
}

class BirthdayReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Birthdays.schedule(ctx); FavWidget.refresh(ctx); return
        }
        Updater.dailyCheck(ctx)
        if (!Birthdays.enabled(ctx)) return
        val pending = goAsync()
        Thread {
            try {
                val c = Sahl.wrap(ctx)
                val list = try { Birthdays.today(ctx) } catch (e: Exception) { emptyMap() }
                if (list.isNotEmpty()) {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(Sahl.chimeChannel(ctx, "birthdays_chime", c.getString(R.string.birthdays), NotificationManager.IMPORTANCE_DEFAULT))
                    for ((id, name) in list) {
                        val open = PendingIntent.getActivity(ctx, id.toInt(),
                            Intent(ctx, DetailActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        nm.notify(10_000 + id.toInt(), SahlNotif.apply(ctx, Notification.Builder(ctx, "birthdays_chime"))
                            .setContentTitle(c.getString(R.string.birthday_today, name))
                            .setContentText(c.getString(R.string.birthday_wish))
                            .setContentIntent(open).setAutoCancel(true).build())
                    }
                }
            } finally { pending.finish() }
        }.start()
    }
}

/** Home-screen widget: up to 4 favorites, tap to call. */
class FavWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(ctx) }

    companion object {
        private val slots = intArrayOf(R.id.f0, R.id.f1, R.id.f2, R.id.f3)
        private val circles = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3)
        private val names = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3)

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FavWidget::class.java))
            if (ids.isEmpty()) return
            Thread {
                val hidden = Vault.hidden(ctx)
                val favs = try { Repo.list(ctx).filter { it.starred && it.lookup !in hidden && it.numbers.isNotEmpty() }.take(4) }
                catch (e: Exception) { emptyList() }
                val rv = RemoteViews(ctx.packageName, R.layout.widget_fav)
                for (i in 0 until 4) {
                    val p = favs.getOrNull(i)
                    if (p == null) { rv.setViewVisibility(slots[i], View.INVISIBLE); continue }
                    rv.setViewVisibility(slots[i], View.VISIBLE)
                    rv.setTextViewText(circles[i], p.section.let { s -> if (s == "#") p.name.take(1) else s })
                    rv.setTextViewText(names[i], p.name.substringBefore(' '))
                    val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.numbers[0])))
                    rv.setOnClickPendingIntent(slots[i], PendingIntent.getActivity(ctx, 300 + i, dial,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                rv.setViewVisibility(R.id.empty, if (favs.isEmpty()) View.VISIBLE else View.GONE)
                rv.setOnClickPendingIntent(R.id.empty, PendingIntent.getActivity(ctx, 299,
                    Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                mgr.updateAppWidget(ids, rv)
            }.start()
        }
    }
}

/** Finds and links duplicate contacts (Android's own non-destructive "link" merge). */
object Dupes {
    fun find(people: List<Person>): List<List<Person>> {
        val parent = IntArray(people.size) { it }
        fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun join(a: Int, b: Int) { parent[root(a)] = root(b) }
        val byKey = HashMap<String, Int>()
        people.forEachIndexed { i, p ->
            val keys = p.numbers.map { "t" + Norm.phoneKey(it) }.filter { it.length >= 8 } +
                (if (p.key.any { ch -> ch.isLetter() }) listOf("n" + p.key.trim()) else emptyList())
            for (k in keys) { val j = byKey[k]; if (j == null) byKey[k] = i else join(i, j) }
        }
        return people.indices.groupBy { root(it) }.values.filter { it.size > 1 }.map { g -> g.map { people[it] } }
    }

    private fun rawIds(c: Context, contactId: Long): List<Long> {
        val out = ArrayList<Long>()
        c.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()), null)?.use {
            while (it.moveToNext()) out.add(it.getLong(0))
        }
        return out
    }

    fun merge(c: Context, group: List<Person>): Boolean {
        val raws = group.flatMap { rawIds(c, it.id) }
        if (raws.size < 2) return false
        val ops = ArrayList<ContentProviderOperation>()
        for (r in raws.drop(1)) ops.add(ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
            .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_TOGETHER)
            .withValue(AggregationExceptions.RAW_CONTACT_ID1, raws[0])
            .withValue(AggregationExceptions.RAW_CONTACT_ID2, r).build())
        return try {
            c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Sahl.announce(c, Sahl.EVENT_CONTACTS_CHANGED); true
        } catch (e: Exception) { false }
    }
}

/** QR code of a contact (MECARD) — any phone camera can scan it to add the contact. */
object Qr {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(":", "\\:").replace(",", "\\,")

    fun mecard(f: Full): String = buildString {
        append("MECARD:N:").append(esc(f.name)).append(';')
        for (p in f.phones) append("TEL:").append(esc(p.number)).append(';')
        for (e in f.emails) append("EMAIL:").append(esc(e)).append(';')
        if (!f.company.isNullOrBlank()) append("ORG:").append(esc(f.company)).append(';')
        append(';')
    }

    fun bitmap(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) 0xFF0A231D.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
