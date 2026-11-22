package com.sahl.contacts

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DetailActivity : SahlActivity() {
    private var id = 0L
    private var msgs: List<Union.MsgRow> = emptyList()
    private var showAll = false
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra("id", 0L).takeIf { it != 0L } ?: (intent.getStringExtra("id")?.toLongOrNull() ?: 0L)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        Thread {
            val f = try { Repo.full(this, id, false) } catch (e: Exception) { null }
            val photo = try {
                ContactsContract.Contacts.openContactPhotoInputStream(contentResolver,
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), true)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            val calls = if (f == null) emptyList() else Union.history(this, f.phones.map { it.number }, 30)
            val texts = if (f == null) emptyList() else Union.messages(this, f.phones.map { it.number }, 30)
            val groupNames = try {
                if (f == null || f.groupIds.isEmpty()) emptyList()
                else Repo.groups(this).filter { (_, ids) -> ids.any { it in f.groupIds } }.keys.toList()
            } catch (e: Exception) { emptyList() }
            runOnUiThread { if (f == null) finish() else { msgs = texts; build(f, photo, calls, groupNames) } }
        }.start()
    }

    private fun build(f: Full, photo: Bitmap?, calls: List<Union.CallRow>, groupNames: List<String>) {
        root.removeAllViews()
        root.addView(header("", true, listOf(
            (if (f.starred) R.drawable.ic_star else R.drawable.ic_star_border) to {
                Thread { Repo.setStar(this, f.id, !f.starred); runOnUiThread { FavWidget.refresh(this); load() } }.start()
            },
            R.drawable.ic_edit to { startActivity(Intent(this, EditActivity::class.java).putExtra("id", f.id)) },
            R.drawable.ic_more to { menu(f) }
        )))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(4), px(16), px(40)) }
        root.addView(ScrollView(this).apply { addView(body); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f))

        // Hero
        val (av, iv, letter) = avatar(116)
        av.background = oval(avatarColor(f.name))
        letter.text = Norm.section(f.name).let { if (it == "#") f.name.take(1) else it }
        if (photo != null) { iv.setImageBitmap(photo); letter.visibility = View.GONE }
        body.addView(av, LinearLayout.LayoutParams(px(116), px(116)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(text(f.name, 26f, col(R.color.text), true).apply { gravity = Gravity.CENTER; setPadding(0, px(14), 0, 0) })
        val sub = listOfNotNull(f.company?.takeIf { it.isNotBlank() },
            if (Vault.isHidden(this, f.lookup)) getString(R.string.hidden_short) else null).joinToString(" · ")
        if (sub.isNotEmpty()) body.addView(text(sub, 14f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(2), 0, 0) })

        // Smart summary: how often you talk
        if (calls.isNotEmpty()) {
            body.addView(text(getString(R.string.talk_summary, calls.size, relDay(calls.first().date)), 13f, col(R.color.emerald)).apply {
                gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0)
            })
        }

        // Quick actions
        val first = f.phones.firstOrNull()?.number
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(20), 0, px(8)) }
        fun action(ic: Int, label: Int, enabled: Boolean, go: () -> Unit) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; alpha = if (enabled) 1f else 0.4f }
            b.addView(iconButton(ic, col(R.color.tonal), col(R.color.emerald), 56, 24) { if (enabled) go() })
            b.addView(text(getString(label), 12f, col(R.color.text)).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) })
            actions.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        action(R.drawable.ic_call, R.string.call, first != null) { Union.call(this, first!!) }
        action(R.drawable.ic_chat, R.string.message, first != null) { Union.sms(this, first!!) }
        action(R.drawable.ic_whatsapp, R.string.whatsapp, first != null) {
            if (f.phones.size == 1) Actions.whatsapp(this, first!!)
            else sheet(getString(R.string.whatsapp), f.phones.map { p ->
                Triple(R.drawable.ic_whatsapp, "${p.number} · ${typeLabel(p.type)}") { Actions.whatsapp(this, p.number) }
            })
        }
        if (f.emails.isNotEmpty()) action(R.drawable.ic_mail, R.string.email, true) { Actions.email(this, f.emails[0]) }
        else action(R.drawable.ic_share, R.string.share, true) { Actions.share(this, f.lookup) }
        body.addView(actions)

        // Numbers
        if (f.phones.isNotEmpty()) {
            val card = section(body)
            for ((i, p) in f.phones.withIndex()) {
                if (i > 0) card.addView(divider())
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(px(4), px(10), px(4), px(10))
                    setOnClickListener { Union.call(this@DetailActivity, p.number) }
                    setOnLongClickListener { Actions.copy(this@DetailActivity, p.number); true }
                }.press(14f)
                row.addView(icon(R.drawable.ic_call, col(R.color.sub), 20))
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0) }
                texts.addView(text(p.number, 17f).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
                texts.addView(text(typeLabel(p.type) + countryOf(p.number), 12f, col(R.color.sub)))
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(iconButton(R.drawable.ic_chat, 0, col(R.color.emerald), 40, 20) { Union.sms(this, p.number) })
                row.addView(iconButton(R.drawable.ic_whatsapp, 0, col(R.color.emerald), 40, 20) { Actions.whatsapp(this, p.number) })
                card.addView(row)
            }
        }
        if (f.emails.isNotEmpty()) {
            val card = section(body)
            for (e in f.emails) card.addView(infoRow(R.drawable.ic_mail, e, getString(R.string.email)) { Actions.email(this, e) }
                .apply { setOnLongClickListener { Actions.copy(this@DetailActivity, e); true } })
        }

        // About
        val info = ArrayList<View>()
        val bday = f.birthday
        if (!bday.isNullOrBlank()) info.add(infoRow(R.drawable.ic_cake, prettyDate(bday), getString(R.string.birthday) + untilBirthday(bday), null))
        val company = f.company
        if (!company.isNullOrBlank()) info.add(infoRow(R.drawable.ic_work, company, getString(R.string.company), null))
        if (groupNames.isNotEmpty()) info.add(infoRow(R.drawable.ic_label, groupNames.joinToString(" · "), getString(R.string.groups), null))
        val note = f.note
        if (!note.isNullOrBlank()) info.add(infoRow(R.drawable.ic_note, note, getString(R.string.note), null))
        if (info.isNotEmpty()) {
            val card = section(body)
            info.forEachIndexed { i, v -> if (i > 0) card.addView(divider()); card.addView(v) }
        }

        // People timeline: calls (Sahl Phone) and messages (Sahl Messages) together
        data class Ev(val date: Long, val icon: Int, val color: Int, val title: String, val detail: String, val go: (() -> Unit)?)
        val events = ArrayList<Ev>()
        for (c in calls) {
            val (ic, color, lbl) = when (c.type) {
                CallLog.Calls.OUTGOING_TYPE -> Triple(R.drawable.ic_arrow_out, col(R.color.emerald), R.string.t_out_call)
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Triple(R.drawable.ic_call_missed, col(R.color.danger), R.string.t_missed_call)
                else -> Triple(R.drawable.ic_arrow_in, col(R.color.ok), R.string.t_in_call)
            }
            events.add(Ev(c.date, ic, color, getString(lbl), if (c.duration > 0) dur(c.duration) else "", null))
        }
        for (m in msgs) events.add(Ev(m.date, R.drawable.ic_chat, if (m.incoming) col(R.color.sub) else col(R.color.emerald),
            getString(if (m.incoming) R.string.t_in_msg else R.string.t_out_msg), m.body.replace('\n', ' ')) { Union.sms(this, m.number) })
        events.sortByDescending { it.date }
        if (events.isNotEmpty()) {
            val card = section(body, getString(R.string.timeline))
            var lastDay = ""
            for (e in events.take(if (showAll) 60 else 8)) {
                val day = relDay(e.date)
                if (day != lastDay) { lastDay = day; card.addView(text(day, 12f, col(R.color.sub), true).apply { setPaddingRelative(0, px(8), 0, px(2)) }) }
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(4), px(7), px(4), px(7)) }
                if (e.go != null) { row.setOnClickListener { e.go.invoke() }; row.press(12f) }
                row.addView(icon(e.icon, e.color, 18))
                val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, px(8), 0) }
                t.addView(text(e.title, 14f))
                if (e.detail.isNotEmpty() && e.go != null) t.addView(text(e.detail, 13f, col(R.color.sub)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text(listOf(if (e.go == null) e.detail else "", Instant.ofEpochMilli(e.date).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm", loc()))).filter { it.isNotEmpty() }.joinToString("  ·  "), 12f, col(R.color.sub)))
                card.addView(row)
            }
            if (events.size > 8) card.addView(text(getString(if (showAll) R.string.show_less else R.string.show_all), 14f, col(R.color.emerald), true).apply {
                gravity = Gravity.CENTER; setPadding(0, px(10), 0, px(4)); setOnClickListener { showAll = !showAll; load() }
            }.press(12f))
        } else if (!Union.hasPhone(this) && f.phones.isNotEmpty()) {
            body.addView(text(getString(R.string.get_phone_hint), 12f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(18), 0, 0) })
        }
    }

    private fun section(parent: LinearLayout, title: String? = null): LinearLayout {
        if (title != null) parent.addView(label(title).apply { setPadding(px(8), px(18), 0, px(2)) })
        val c = card()
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        return c
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(col(R.color.line))
        layoutParams = LinearLayout.LayoutParams(-1, 1).apply { marginStart = px(40) }
    }

    private fun infoRow(ic: Int, value: String, caption: String, go: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(4), px(10), px(4), px(10)) }
        if (go != null) { row.setOnClickListener { go() }; row.press(14f) }
        row.addView(icon(ic, col(R.color.sub), 20))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0) }
        t.addView(text(value, 16f))
        t.addView(text(caption, 12f, col(R.color.sub)))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun typeLabel(t: Int) = getString(when (t) {
        Phone.TYPE_MOBILE -> R.string.mobile
        Phone.TYPE_HOME -> R.string.home
        Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> R.string.work
        else -> R.string.other
    })

    private fun countryOf(n: String): String = Countries.of(n, Sahl.countryCode(this), rtl)?.let { " · $it" } ?: ""

    private fun loc() = resources.configuration.locales[0]

    private fun parseBirthday(s: String): LocalDate? = try {
        LocalDate.parse(if (s.startsWith("--")) "2000" + s.drop(1) else s)
    } catch (e: Exception) { null }

    private fun prettyDate(s: String): String {
        val d = parseBirthday(s) ?: return s
        return d.format(DateTimeFormatter.ofPattern(if (s.startsWith("--")) "d MMMM" else "d MMMM yyyy", loc()))
    }

    /** " · in 12 days" or " · today" */
    private fun untilBirthday(s: String): String {
        val d = parseBirthday(s) ?: return ""
        val today = LocalDate.now()
        var next = try { d.withYear(today.year) } catch (e: Exception) { return "" }
        if (next.isBefore(today)) next = next.plusYears(1)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, next)
        return " · " + if (days == 0L) getString(R.string.today) else getString(R.string.in_days, days.toInt())
    }

    private fun relDay(t: Long): String {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (d) {
            today -> getString(R.string.today)
            today.minusDays(1) -> getString(R.string.yesterday)
            else -> d.format(DateTimeFormatter.ofPattern("d MMM", loc()))
        }
    }

    private fun stamp(t: Long): String {
        val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        return relDay(t) + "  " + z.format(DateTimeFormatter.ofPattern("HH:mm", loc()))
    }

    private fun dur(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    private fun menu(f: Full) {
        val hidden = Vault.isHidden(this, f.lookup)
        sheet(f.name, listOf(
            Triple(R.drawable.ic_share, getString(R.string.share)) { Actions.share(this, f.lookup) },
            Triple(R.drawable.ic_qr, getString(R.string.qr_code)) { showQr(f) },
            Triple(R.drawable.ic_visibility_off, getString(if (hidden) R.string.unhide else R.string.hide)) {
                Vault.set(this, f.lookup, !hidden)
                if (!hidden) Toast.makeText(this, R.string.hidden_note, Toast.LENGTH_LONG).show()
                FavWidget.refresh(this); load()
            },
            Triple(R.drawable.ic_delete, getString(R.string.delete)) { confirmDelete(f) }
        ))
    }

    private fun showQr(f: Full) {
        val size = px(260)
        val bmp = try { Qr.bitmap(Qr.mecard(f), size) } catch (e: Exception) { return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(px(16), px(16), px(16), px(8))
            addView(ImageView(this@DetailActivity).apply {
                setImageBitmap(bmp); background = round(0xFFFFFFFF.toInt(), 18f); setPadding(px(12), px(12), px(12), px(12))
            }, LinearLayout.LayoutParams(size + px(24), size + px(24)))
            addView(text(getString(R.string.qr_hint), 13f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) })
        }
        AlertDialog.Builder(this).setTitle(f.name).setView(box).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun confirmDelete(f: Full) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, f.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Repo.delete(this, f.id, f.lookup)
                Sahl.announce(this, Sahl.EVENT_CONTACTS_CHANGED)
                finish()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
