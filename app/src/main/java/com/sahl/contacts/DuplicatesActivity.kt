package com.sahl.contacts

import android.os.Bundle
import android.view.Gravity
import android.widget.*

class DuplicatesActivity : SahlActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.duplicates), true))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        load()
    }

    private fun load() {
        body.removeAllViews()
        body.addView(text(getString(R.string.checking), 15f, col(R.color.sub)).apply { setPadding(0, px(24), 0, 0); gravity = Gravity.CENTER })
        Thread {
            val hidden = Vault.hidden(this)
            val groups = try { Dupes.find(Repo.list(this).filter { it.lookup !in hidden }) } catch (e: Exception) { emptyList() }
            runOnUiThread { show(groups) }
        }.start()
    }

    private fun show(groups: List<List<Person>>) {
        body.removeAllViews()
        if (groups.isEmpty()) {
            body.addView(text(getString(R.string.no_duplicates), 16f, col(R.color.sub)).apply { setPadding(0, px(32), 0, 0); gravity = Gravity.CENTER })
            return
        }
        for (g in groups) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = round(col(R.color.card), 18f)
                setPadding(px(16), px(12), px(16), px(12))
            }
            for (p in g) {
                card.addView(text(p.name, 16f, col(R.color.text), true))
                if (p.numbers.isNotEmpty()) card.addView(text(p.numbers.joinToString("  ·  "), 13f, col(R.color.sub)).apply {
                    textDirection = android.view.View.TEXT_DIRECTION_LTR; setPadding(0, 0, 0, px(6))
                })
            }
            card.addView(Button(this).apply {
                text = getString(R.string.merge); typeface = font
                setOnClickListener {
                    isEnabled = false
                    Thread {
                        val ok = Dupes.merge(this@DuplicatesActivity, g)
                        runOnUiThread { toast(if (ok) R.string.merged else R.string.merge_failed); load() }
                    }.start()
                }
            })
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        }
    }
}
