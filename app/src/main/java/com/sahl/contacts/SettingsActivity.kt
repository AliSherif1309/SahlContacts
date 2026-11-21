package com.sahl.contacts

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.*

class SettingsActivity : SahlActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.settings), true))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val p = Sahl.prefs(this)

        choice(R.string.language, listOf("system" to getString(R.string.system), "ar" to "العربية", "en" to "English"), "lang")
        choice(R.string.theme, listOf("system" to getString(R.string.system), "light" to getString(R.string.light), "dark" to getString(R.string.dark)), "theme")

        val cc = card(R.string.country_code)
        cc.addView(text(getString(R.string.country_code_hint), 12f, col(R.color.sub)))
        cc.addView(EditText(this).apply {
            setText(Sahl.countryCode(this@SettingsActivity)); inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f; typeface = font; setTextColor(col(R.color.text))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = Norm.digits(s?.toString() ?: "")
                    if (v.isNotEmpty()) p.edit().putString("cc", v).apply()
                }
            })
        })

        val tools = card(R.string.tools)
        tools.addView(Switch(this).apply {
            text = getString(R.string.birthday_reminders); typeface = font; setTextColor(col(R.color.text))
            isChecked = Birthdays.enabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("bday", on).apply() }
        })
        tools.addView(Button(this).apply {
            text = getString(R.string.number_cleanup); typeface = font
            setOnClickListener { numberCleanup() }
        })
        tools.addView(Button(this).apply {
            text = getString(R.string.duplicates); typeface = font
            setOnClickListener { startActivity(Intent(this@SettingsActivity, DuplicatesActivity::class.java)) }
        })

        val bk = card(R.string.backup)
        bk.addView(text(getString(R.string.backup_hint), 12f, col(R.color.sub)))
        bk.addView(Button(this).apply {
            text = getString(R.string.export_vcf); typeface = font
            setOnClickListener {
                val name = "SahlContacts-" + java.time.LocalDate.now() + ".vcf"
                try {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/x-vcard").putExtra(Intent.EXTRA_TITLE, name), 30)
                } catch (e: Exception) { }
            }
        })
        bk.addView(Button(this).apply {
            text = getString(R.string.import_vcf); typeface = font
            setOnClickListener {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 31)
                } catch (e: Exception) { }
            }
        })

        val up = card(R.string.check_updates)
        up.addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 14f, col(R.color.sub)))
        up.addView(Button(this).apply {
            text = getString(R.string.check_updates); typeface = font
            setOnClickListener { checkUpdates(this) }
        })

        val un = card(R.string.union)
        un.addView(text(getString(R.string.union_desc), 14f, col(R.color.sub)))
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        if (req == 31) {
            // Hand the file to Android's own vCard importer
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/x-vcard")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) { toast(R.string.save_failed) }
            return
        }
        if (req != 30) return
        Thread {
            var n = 0
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    for (p in Repo.list(this)) {
                        if (p.lookup.isEmpty()) continue
                        try {
                            contentResolver.openInputStream(android.net.Uri.withAppendedPath(
                                android.provider.ContactsContract.Contacts.CONTENT_VCARD_URI, p.lookup))?.use { it.copyTo(out) }
                            n++
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) { }
            val count = n
            runOnUiThread { Toast.makeText(this, getString(R.string.exported, count), Toast.LENGTH_LONG).show() }
        }.start()
    }

    /** One tap: make all numbers the same clean form (+974…). Shows a preview first. */
    private fun numberCleanup() {
        Thread {
            val fixes = try { Repo.numberFixes(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (fixes.isEmpty()) { toast(R.string.numbers_clean); return@runOnUiThread }
                val preview = fixes.take(8).joinToString("\n") { "${it.name}:  ${it.from}  →  ${it.to}" } +
                    if (fixes.size > 8) "\n…" else ""
                AlertDialog.Builder(this).setTitle(getString(R.string.fix_n_numbers, fixes.size)).setMessage(preview)
                    .setPositiveButton(R.string.fix_all) { _, _ ->
                        Thread {
                            val n = Repo.applyFixes(this, fixes)
                            runOnUiThread { Toast.makeText(this, getString(R.string.fixed_n, n), Toast.LENGTH_LONG).show() }
                        }.start()
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }.start()
    }

    private fun card(title: Int): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(col(R.color.card), 22f)
            setPadding(px(16), px(12), px(16), px(12))
        }
        c.addView(text(getString(title), 13f, col(R.color.emerald), true))
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        return c
    }

    private fun choice(title: Int, options: List<Pair<String, String>>, key: String) {
        val c = card(title)
        val p = Sahl.prefs(this)
        val cur = p.getString(key, "system")
        val group = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i; text = label; typeface = font; setTextColor(col(R.color.text))
                isChecked = value == cur
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val v = options[checkedId - 1000].first
            if (v == p.getString(key, "system")) return@setOnCheckedChangeListener
            p.edit().putString(key, v).commit()
            // Restart so the new language / theme applies everywhere
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
        c.addView(group)
    }

    private fun checkUpdates(btn: Button) {
        btn.isEnabled = false; btn.text = getString(R.string.checking)
        Thread {
            val r = Updater.fetchNewer()
            runOnUiThread {
                btn.isEnabled = true; btn.text = getString(R.string.check_updates)
                if (r == null) { toast(R.string.up_to_date); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.update_available, r.name))
                    .setMessage(r.notes)
                    .setPositiveButton(R.string.install) { _, _ -> Updater.downloadAndInstall(this, r) }
                    .setNegativeButton(R.string.later, null).show()
            }
        }.start()
    }
}
