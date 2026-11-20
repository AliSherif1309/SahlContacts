package com.sahl.contacts

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.Gravity
import android.view.View
import android.widget.*

class DetailActivity : SahlActivity() {
    private var id = 0L
    private var full: Full? = null
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra("id", 0L)
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
            runOnUiThread { if (f == null) finish() else { full = f; build(f, photo) } }
        }.start()
    }

    private fun build(f: Full, photo: android.graphics.Bitmap?) {
        root.removeAllViews()
        root.addView(header("", true, listOf(
            (if (f.starred) "★" else "☆") to { Repo.setStar(this, f.id, !f.starred); load() },
            "✎" to { startActivity(Intent(this, EditActivity::class.java).putExtra("id", f.id)) },
            "🗑" to { confirmDelete(f) }
        )))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(20), px(16), px(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // Avatar + name
        val (av, iv, letter) = avatar(104)
        av.background = oval(avatarColor(f.name))
        letter.text = Norm.section(f.name).let { if (it == "#") f.name.take(1) else it }
        if (photo != null) { iv.setImageBitmap(photo); letter.visibility = View.GONE }
        body.addView(av, LinearLayout.LayoutParams(px(104), px(104)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(text(f.name, 24f, col(R.color.text), true).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) })
        if (!f.company.isNullOrBlank()) body.addView(text(f.company, 14f, col(R.color.sub)).apply { gravity = Gravity.CENTER })

        // Quick actions
        val first = f.phones.firstOrNull()?.number
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(18), 0, px(8)) }
        fun action(icon: String, label: Int, enabled: Boolean, go: () -> Unit) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = round(col(R.color.card), 18f); setPadding(px(8), px(10), px(8), px(10))
                alpha = if (enabled) 1f else 0.4f
                if (enabled) setOnClickListener { go() }
            }
            b.addView(text(icon, 20f, col(R.color.emerald)).apply { gravity = Gravity.CENTER })
            b.addView(text(getString(label), 12f, col(R.color.text)).apply { gravity = Gravity.CENTER })
            actions.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(px(4), 0, px(4), 0) })
        }
        action("📞", R.string.call, first != null) { dial(first!!) }
        action("💬", R.string.message, first != null) { sms(first!!) }
        action("🟢", R.string.whatsapp, first != null) { whatsapp(first!!) }
        action("↗", R.string.share, true) { share(f) }
        body.addView(actions)

        // Phones
        if (f.phones.isNotEmpty()) {
            val card = card(body, R.string.phone)
            for (p in f.phones) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, px(8)) }
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(text(p.number, 17f).apply { textDirection = View.TEXT_DIRECTION_LTR })
                texts.addView(text(typeLabel(p.type), 12f, col(R.color.sub)))
                texts.setOnClickListener { dial(p.number) }
                texts.setOnLongClickListener { copy(p.number); true }
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("💬", 20f).apply { setPadding(px(12), 0, px(12), 0); setOnClickListener { sms(p.number) } })
                row.addView(text("🟢", 18f).apply { setPadding(px(12), 0, px(4), 0); setOnClickListener { whatsapp(p.number) } })
                card.addView(row)
            }
        }
        if (f.emails.isNotEmpty()) {
            val card = card(body, R.string.email)
            for (e in f.emails) card.addView(text(e, 16f).apply {
                setPadding(0, px(8), 0, px(8))
                setOnClickListener { go(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$e"))) }
                setOnLongClickListener { copy(e); true }
            })
        }
        if (!f.birthday.isNullOrBlank()) card(body, R.string.birthday).addView(text(prettyDate(f.birthday), 16f).apply { setPadding(0, px(6), 0, px(6)) })
        if (f.groupIds.isNotEmpty()) {
            val names = Repo.groups(this).filter { (_, ids) -> ids.any { it in f.groupIds } }.keys
            if (names.isNotEmpty()) card(body, R.string.groups).addView(text(names.joinToString(" · "), 16f).apply { setPadding(0, px(6), 0, px(6)) })
        }
        if (!f.note.isNullOrBlank()) card(body, R.string.note).addView(text(f.note, 15f).apply { setPadding(0, px(6), 0, px(6)) })
    }

    private fun card(parent: LinearLayout, title: Int): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(col(R.color.card), 18f)
            setPadding(px(16), px(12), px(16), px(10))
        }
        c.addView(text(getString(title), 13f, col(R.color.gold), true))
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        return c
    }

    private fun typeLabel(t: Int) = getString(when (t) {
        Phone.TYPE_MOBILE -> R.string.mobile
        Phone.TYPE_HOME -> R.string.home
        Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> R.string.work
        else -> R.string.other
    })

    private fun prettyDate(s: String): String = try {
        val d = java.time.LocalDate.parse(if (s.startsWith("--")) "2000" + s.drop(1) else s)
        val fmt = if (s.startsWith("--")) "d MMMM" else "d MMMM yyyy"
        d.format(java.time.format.DateTimeFormatter.ofPattern(fmt, resources.configuration.locales[0]))
    } catch (e: Exception) { s }

    private fun go(i: Intent) = try { startActivity(i) } catch (e: Exception) { }
    private fun dial(n: String) = go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
    private fun sms(n: String) = go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))))

    private fun whatsapp(n: String) {
        val d = Norm.international(n, Sahl.countryCode(this))
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$d"))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            try { packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
        }
        if (pkg == null) { toast(R.string.no_whatsapp); return }
        go(i.setPackage(pkg))
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("contact", s))
        toast(R.string.copied)
    }

    private fun share(f: Full) {
        if (f.lookup.isEmpty()) return
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, f.lookup)
        val i = Intent(Intent.ACTION_SEND).setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        go(Intent.createChooser(i, getString(R.string.share)))
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
