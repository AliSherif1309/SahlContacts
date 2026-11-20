package com.sahl.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : SahlActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var all: List<Person> = emptyList()
    private var shown: List<Person> = emptyList()
    private var groups: Map<String, Set<Long>> = emptyMap()
    private var members: Map<Long, Set<Long>> = emptyMap()
    private var groupFilter: String? = null
    private var query = ""

    private lateinit var list: ListView
    private lateinit var adapter: PeopleAdapter
    private lateinit var index: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var favRow: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var empty: TextView
    private lateinit var count: TextView
    private lateinit var permBox: LinearLayout

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(reload); main.postDelayed(reload, 400) }
    }
    private val reload = Runnable { load() }

    private fun granted() = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.app_name), false, listOf(
            "＋" to { startActivity(Intent(this, EditActivity::class.java)) },
            "⚙" to { startActivity(Intent(this, SettingsActivity::class.java)) }
        )))

        // Search
        val search = EditText(this).apply {
            hint = getString(R.string.search); textSize = 16f; typeface = font; isSingleLine = true
            setTextColor(col(R.color.text)); setHintTextColor(col(R.color.sub))
            background = round(col(R.color.card), 24f, col(R.color.sub) and 0x33FFFFFF)
            setPadding(px(18), px(12), px(18), px(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; filter() }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(12), px(12), px(12), px(4)) })

        // Group chips
        chips = LinearLayout(this).apply { setPadding(px(8), px(4), px(8), px(4)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // Permission prompt
        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(px(32), px(48), px(32), px(32))
            addView(text(getString(R.string.need_permission), 16f).apply { gravity = Gravity.CENTER })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.allow); typeface = font
                setOnClickListener { askPermissions() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(16) })
        }
        root.addView(permBox)

        // List + A–Z / أ–ي index
        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; isFastScrollEnabled = false; clipToPadding = false
            setPadding(0, 0, px(28), px(24))
        }
        favBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(4), px(12), px(4))
            addView(text(getString(R.string.favorites), 13f, col(R.color.gold), true).apply { setPadding(px(4), 0, 0, px(6)) })
            favRow = LinearLayout(this@MainActivity)
            addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(favRow) })
        }
        list.addHeaderView(favBox, null, false)
        count = text("", 12f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, px(12)) }
        list.addFooterView(count, null, false)
        adapter = PeopleAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val i = pos - list.headerViewsCount
            if (i in shown.indices) open(shown[i])
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = text("", 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        index = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        body.addView(index, FrameLayout.LayoutParams(px(26), -1, Gravity.END))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        Updater.dailyCheck(this)
        if (granted()) start() else askPermissions()
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (granted()) start()
    }

    private var started = false
    private fun start() {
        permBox.visibility = View.GONE
        if (!started) {
            started = true
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (granted()) start() else permBox.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        if (started) contentResolver.unregisterContentObserver(observer)
    }

    private fun load() {
        Thread {
            val p = try { Repo.list(this) } catch (e: Exception) { emptyList() }
            val g = try { Repo.groups(this) } catch (e: Exception) { emptyMap() }
            val m = try { Repo.members(this) } catch (e: Exception) { emptyMap() }
            main.post {
                all = p; groups = g; members = m
                val gf = groupFilter
                if (gf != null && !groups.containsKey(gf)) groupFilter = null
                Thumbs.clear()
                buildChips(); buildFavorites(); filter()
            }
        }.start()
    }

    private fun buildChips() {
        chips.removeAllViews()
        if (groups.isEmpty()) { (chips.parent as View).visibility = View.GONE; return }
        (chips.parent as View).visibility = View.VISIBLE
        fun chip(label: String, key: String?) {
            val on = groupFilter == key
            chips.addView(text(label, 13f, if (on) 0xFFFFFFFF.toInt() else col(R.color.text)).apply {
                background = round(if (on) col(R.color.emerald) else col(R.color.card), 16f, if (on) 0 else (col(R.color.sub) and 0x33FFFFFF))
                setPadding(px(14), px(6), px(14), px(6))
                setOnClickListener { groupFilter = key; buildChips(); filter() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
        }
        chip(getString(R.string.all), null)
        for (t in groups.keys) chip(t, t)
    }

    private fun buildFavorites() {
        favRow.removeAllViews()
        val favs = all.filter { it.starred }
        for (p in favs) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(6), 0, px(6), 0)
                setOnClickListener { open(p) }
            }
            val (f, iv, letter) = avatar(54)
            f.background = oval(avatarColor(p.name)); letter.text = p.section.take(1)
            Thumbs.load(this, p.photo, iv, letter)
            cell.addView(f)
            cell.addView(text(p.name.substringBefore(' '), 12f).apply {
                maxLines = 1; gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0)
            }, LinearLayout.LayoutParams(px(66), -2))
            favRow.addView(cell)
        }
    }

    private fun filter() {
        val words = Norm.text(query).split(' ').filter { it.isNotBlank() }
        val dq = Norm.digits(query)
        val inGroup = groupFilter?.let { t -> groups[t]?.flatMap { members[it] ?: emptySet() }?.toSet() ?: emptySet() }
        shown = all.filter { p ->
            (inGroup == null || p.id in inGroup) &&
                (words.isEmpty() || words.all { w -> p.key.contains(w) } || (dq.length >= 3 && p.digits.contains(dq)))
        }
        favBox.visibility = if (query.isBlank() && groupFilter == null && all.any { it.starred }) View.VISIBLE else View.GONE
        // header views stay in the list; hide its content height when not needed
        favBox.layoutParams = AbsListView.LayoutParams(-1, if (favBox.visibility == View.VISIBLE) -2 else 1)
        adapter.notifyDataSetChanged()
        empty.text = getString(if (all.isEmpty()) R.string.no_contacts else R.string.no_results)
        empty.visibility = if (shown.isEmpty() && granted()) View.VISIBLE else View.GONE
        count.text = getString(R.string.contacts_count, shown.size)
        buildIndex()
    }

    private fun buildIndex() {
        index.removeAllViews()
        if (query.isNotBlank()) return
        val starts = LinkedHashMap<String, Int>()
        shown.forEachIndexed { i, p -> if (p.section !in starts) starts[p.section] = i }
        if (starts.size < 2) return
        val keys = starts.keys.toList()
        for (k in keys) index.addView(text(k, 10f, col(R.color.emerald), true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, 0, 1f))
        index.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                val i = ((e.y / v.height) * keys.size).toInt().coerceIn(0, keys.size - 1)
                list.setSelection(starts[keys[i]]!! + list.headerViewsCount)
            }
            true
        }
    }

    private fun open(p: Person) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("id", p.id))
    }

    private inner class PeopleAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id

        inner class Holder(val head: TextView, val av: FrameLayout, val iv: ImageView, val letter: TextView,
                           val name: TextView, val sub: TextView)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v: LinearLayout
            val h: Holder
            if (convertView == null) {
                v = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val head = text("", 13f, col(R.color.gold), true).apply { setPadding(px(20), px(10), px(20), px(2)) }
                val row = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(8), px(16), px(8))
                }
                val (f, iv, letter) = avatar(44)
                row.addView(f)
                val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0) }
                val name = text("", 16f).apply { maxLines = 1 }
                val sub = text("", 13f, col(R.color.sub)).apply { maxLines = 1; textDirection = View.TEXT_DIRECTION_LTR }
                texts.addView(name); texts.addView(sub)
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                v.addView(head); v.addView(row)
                h = Holder(head, f, iv, letter, name, sub)
                v.tag = h
            } else { v = convertView as LinearLayout; h = v.tag as Holder }

            val p = shown[position]
            val showHead = query.isBlank() && (position == 0 || shown[position - 1].section != p.section)
            h.head.visibility = if (showHead) View.VISIBLE else View.GONE
            h.head.text = p.section
            h.av.background = oval(avatarColor(p.name))
            h.letter.text = p.section.take(1).let { if (it == "#") p.name.take(1) else it }
            h.name.text = if (p.starred) "${p.name} ★" else p.name
            h.sub.text = p.numbers.firstOrNull() ?: ""
            h.sub.visibility = if (p.numbers.isEmpty()) View.GONE else View.VISIBLE
            Thumbs.load(this@MainActivity, p.photo, h.iv, h.letter)
            return v
        }
    }
}
