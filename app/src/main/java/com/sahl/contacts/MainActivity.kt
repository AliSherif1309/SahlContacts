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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : SahlActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var all: List<Person> = emptyList()
    private var shown: List<Person> = emptyList()
    private var recent: List<Person> = emptyList()
    private var groups: Map<String, Set<Long>> = emptyMap()
    private var members: Map<Long, Set<Long>> = emptyMap()
    private var groupFilter: String? = null
    private var bdayIds: Set<Long> = emptySet()
    private var talkedKeys: Set<String> = emptySet()
    private var query = ""

    private lateinit var list: ListView
    private lateinit var adapter: PeopleAdapter
    private lateinit var index: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var count: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var lockBtn: View
    private lateinit var bubble: TextView

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(reload); main.postDelayed(reload, 400) }
    }
    private val reload = Runnable { load() }

    private fun granted() = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this).apply { setBackgroundColor(col(R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))

        val head = header(getString(R.string.contacts_title), false, listOf(
            R.drawable.ic_lock to { toggleVault() },
            R.drawable.ic_tune to { startActivity(Intent(this, SettingsActivity::class.java)) }
        ))
        lockBtn = head.getChildAt(1)
        root.addView(head)

        val (search, _) = searchBox(getString(R.string.search)) { query = it; filter() }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(6)) })

        chips = LinearLayout(this).apply { setPadding(px(12), px(4), px(12), px(4)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(px(32), px(48), px(32), px(32)); visibility = View.GONE
            addView(icon(R.drawable.ic_people, col(R.color.emerald), 56))
            addView(text(getString(R.string.need_permission), 16f).apply { gravity = Gravity.CENTER; setPadding(0, px(16), 0, px(16)) })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.allow); typeface = font; setTextColor(0xFFFFFFFF.toInt())
                background = round(col(R.color.emerald), 24f); setPadding(px(28), 0, px(28), 0)
                setOnClickListener { askPermissions() }
            })
        }
        root.addView(permBox)

        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(0)
            setPadding(0, 0, 0, px(96)); isVerticalScrollBarEnabled = false
        }
        top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addHeaderView(top, null, false)
        count = text("", 12f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(14), 0, px(14)) }
        list.addFooterView(count, null, false)
        adapter = PeopleAdapter()
        list.adapter = adapter
        body.addView(list, FrameLayout.LayoutParams(-1, -1))

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            addView(icon(R.drawable.ic_search, col(R.color.line), 64))
        }
        emptyText = text("", 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) }
        empty.addView(emptyText)
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))

        index = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        body.addView(index, FrameLayout.LayoutParams(px(26), -1, Gravity.END).apply { topMargin = px(8); bottomMargin = px(96) })
        bubble = text("", 34f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER; background = oval(col(R.color.emerald)); visibility = View.GONE; elevation = 6f * dp
        }
        body.addView(bubble, FrameLayout.LayoutParams(px(76), px(76), Gravity.CENTER))
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, state: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                // The letter bar only appears once the Favorites/Recent rows have scrolled away
                val headerOnScreen = first == 0 && top.height > px(40) && top.childCount > 0 && top.getChildAt(0).visibility == View.VISIBLE
                index.visibility = if (headerOnScreen) View.INVISIBLE else View.VISIBLE
            }
        })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        frame.addView(fab(R.drawable.ic_add) { startActivity(Intent(this, EditActivity::class.java)) },
            FrameLayout.LayoutParams(px(58), px(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(22), px(26)) })
        setContentView(frame)

        Updater.dailyCheck(this)
        Birthdays.schedule(this)
        if (crashOpen) return
        if (granted()) start() else askPermissions()
    }

    override fun onCrashClosed() { if (granted()) start() else askPermissions() }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (granted()) start() else permBox.visibility = View.VISIBLE
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
        lockBtn.alpha = if (Vault.unlocked()) 1f else 0.6f
        if (crashOpen) return
        if (granted()) start()
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
            // People you talked to lately (from Sahl Phone through Sahl Union)
            val byKey = HashMap<String, Person>()
            for (x in p) for (n in x.numbers) byKey[Norm.phoneKey(n)] = x
            val recentNums = Union.recentNumbers(this, 400)
            val r = recentNums.take(40).mapNotNull { byKey[Norm.phoneKey(it)] }.distinctBy { it.id }.take(12)
            val bd = try { Repo.birthdaysThisMonth(this) } catch (e: Exception) { emptySet() }
            main.post {
                all = p; groups = g; members = m; recent = r; bdayIds = bd
                talkedKeys = recentNums.map { Norm.phoneKey(it) }.toSet()
                val gf = groupFilter
                if (gf != null && !gf.startsWith("*") && !groups.containsKey(gf)) groupFilter = null
                Thumbs.clear()
                buildChips(); buildTop(); filter()
                FavWidget.refresh(this)
            }
        }.start()
    }

    private fun buildChips() {
        chips.removeAllViews()
        val scroller = chips.parent as View
        scroller.visibility = View.VISIBLE
        fun chip(label: String, key: String?) {
            val on = groupFilter == key
            chips.addView(text(label, 13f, if (on) 0xFFFFFFFF.toInt() else col(R.color.text), on).apply {
                background = round(if (on) col(R.color.emerald) else col(R.color.tonal), 18f)
                setPadding(px(16), px(8), px(16), px(8))
                setOnClickListener { groupFilter = key; buildChips(); filter() }
            }.press(18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
        }
        chip(getString(R.string.all), null)
        for (t in groups.keys) chip(t, t)
        // Smart groups — fill themselves, to help keep contacts tidy
        for ((key, label) in smartGroups()) if (all.any { smartMatch(key, it) }) chip("✦ " + getString(label), key)
    }

    private fun smartGroups() = listOf(
        "*bday" to R.string.sg_birthdays, "*intl" to R.string.sg_international, "*quiet" to R.string.sg_quiet,
        "*nophoto" to R.string.sg_no_photo, "*nonumber" to R.string.sg_no_number)

    private fun smartMatch(key: String, p: Person): Boolean = when (key) {
        "*bday" -> p.id in bdayIds
        "*intl" -> p.numbers.any { Countries.of(it, Sahl.countryCode(this), false) != null }
        "*quiet" -> talkedKeys.isNotEmpty() && p.numbers.isNotEmpty() && p.numbers.none { Norm.phoneKey(it) in talkedKeys } && p.starred
        "*nophoto" -> p.photo == null
        "*nonumber" -> p.numbers.isEmpty()
        else -> false
    }

    private fun visible(p: Person, hidden: Set<String>) = Vault.unlocked() || p.lookup !in hidden

    /** Favorites and Recent rows at the top of the list. */
    private fun buildTop() {
        top.removeAllViews()
        val hidden = Vault.hidden(this)
        fun strip(title: Int, people: List<Person>) {
            if (people.isEmpty()) return
            top.addView(label(getString(title)).apply { setPaddingRelative(px(20), px(10), 0, px(6)) })
            val row = LinearLayout(this).apply { setPadding(px(12), 0, px(12), px(6)) }
            for (p in people) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(px(6), px(6), px(6), px(6))
                    setOnClickListener { open(p) }
                    setOnLongClickListener { actions(p); true }
                }.press(18f)
                val (f, iv, letter) = avatar(58)
                f.background = oval(avatarColor(p.name)); letter.text = initial(p)
                Thumbs.load(this, p.photo, iv, letter)
                cell.addView(f)
                cell.addView(text(p.name.substringBefore(' '), 12f).apply {
                    maxLines = 1; gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(px(70), -2))
                row.addView(cell)
            }
            top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        strip(R.string.favorites, all.filter { it.starred && visible(it, hidden) })
        strip(R.string.recent, recent.filter { visible(it, hidden) })
    }

    private fun filter() {
        val words = Norm.text(query).split(' ').filter { it.isNotBlank() }
        val dq = Norm.digits(query)
        val gfKey = groupFilter
        val smart = gfKey != null && gfKey.startsWith("*")
        val inGroup = if (smart) null else groupFilter?.let { t -> groups[t]?.flatMap { members[it] ?: emptySet() }?.toSet() ?: emptySet() }
        val hidden = Vault.hidden(this)
        shown = all.filter { p ->
            visible(p, hidden) && (inGroup == null || p.id in inGroup) && (!smart || smartMatch(gfKey!!, p)) &&
                (words.isEmpty() || words.all { w -> p.key.contains(w) } || (dq.length >= 3 && p.digits.contains(dq)))
        }
        // Keep the header in place (never replace its LayoutParams); hide its contents instead
        val showTop = query.isBlank() && groupFilter == null
        for (i in 0 until top.childCount) top.getChildAt(i).visibility = if (showTop) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        emptyText.text = getString(if (all.isEmpty()) R.string.no_contacts else R.string.no_results)
        empty.visibility = if (shown.isEmpty() && started) View.VISIBLE else View.GONE
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
        // Show at most ~24 letters so they never get squeezed; touch still reaches every section
        val step = Math.ceil(keys.size / 24.0).toInt().coerceAtLeast(1)
        val shownKeys = keys.filterIndexed { i, _ -> i % step == 0 }
        for (k in shownKeys) index.addView(text(k, 11f, col(R.color.sub), true).apply {
            gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        index.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                val i = ((e.y / v.height) * keys.size).toInt().coerceIn(0, keys.size - 1)
                list.setSelection(starts[keys[i]]!! + list.headerViewsCount)
                bubble.text = keys[i]; bubble.visibility = View.VISIBLE
            } else bubble.visibility = View.GONE
            true
        }
    }

    private fun toggleVault() {
        if (Vault.unlocked()) { Vault.lock(); lockBtn.alpha = 0.6f; buildTop(); filter() }
        else Vault.unlock(this) { lockBtn.alpha = 1f; buildTop(); filter() }
    }

    private fun initial(p: Person) = p.section.let { if (it == "#") p.name.take(1) else it }

    private fun open(p: Person) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("id", p.id))
    }

    /** Long-press quick actions. */
    private fun actions(p: Person) {
        val n = p.numbers.firstOrNull()
        val items = ArrayList<Triple<Int, String, () -> Unit>>()
        if (n != null) {
            items.add(Triple(R.drawable.ic_call, getString(R.string.call)) { Union.call(this, n) })
            items.add(Triple(R.drawable.ic_chat, getString(R.string.message)) { Union.sms(this, n) })
            items.add(Triple(R.drawable.ic_whatsapp, getString(R.string.whatsapp)) {
                if (p.numbers.size == 1) Actions.whatsapp(this, n)
                else sheet(getString(R.string.whatsapp), p.numbers.map { num ->
                    Triple(R.drawable.ic_whatsapp, num) { Actions.whatsapp(this, num) }
                })
            })
        }
        items.add(Triple(if (p.starred) R.drawable.ic_star else R.drawable.ic_star_border,
            getString(if (p.starred) R.string.unfavorite else R.string.favorite)) {
            Thread { Repo.setStar(this, p.id, !p.starred) }.start()
        })
        items.add(Triple(R.drawable.ic_edit, getString(R.string.edit_contact)) {
            startActivity(Intent(this, EditActivity::class.java).putExtra("id", p.id))
        })
        items.add(Triple(R.drawable.ic_share, getString(R.string.share)) { Actions.share(this, p.lookup) })
        sheet(p.name, items)
    }

    private inner class PeopleAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id

        inner class Holder(val head: TextView, val swipe: SwipeRow, val av: FrameLayout, val iv: ImageView, val letter: TextView,
                           val name: TextView, val sub: TextView, val star: ImageView, val lock: ImageView)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = shown[position]
            val outer: LinearLayout
            val h: Holder
            val reuse = convertView?.tag as? Holder
            if (reuse == null) {
                outer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val head = text("", 13f, col(R.color.emerald), true).apply { setPaddingRelative(px(22), px(14), px(22), px(4)) }
                val row = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(px(18), px(9), px(34), px(9))
                }
                val (f, iv, letter) = avatar(46)
                row.addView(f)
                val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, 0, 0) }
                val name = text("", 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                val sub = text("", 13f, col(R.color.sub)).apply { maxLines = 1; textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
                texts.addView(name); texts.addView(sub)
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                val lock = icon(R.drawable.ic_lock, col(R.color.sub), 16)
                val star = icon(R.drawable.ic_star, col(R.color.gold), 18)
                row.addView(lock); row.addView(star)
                lateinit var swipe: SwipeRow
                swipe = SwipeRow(this@MainActivity, row.press(0f), col(R.color.bg),
                    R.drawable.ic_call, col(R.color.ok), { (swipe.tag as? Person)?.numbers?.firstOrNull()?.let { Union.call(this@MainActivity, it) } },
                    R.drawable.ic_chat, col(R.color.emerald), { (swipe.tag as? Person)?.numbers?.firstOrNull()?.let { Union.sms(this@MainActivity, it) } })
                outer.addView(head); outer.addView(swipe)
                h = Holder(head, swipe, f, iv, letter, name, sub, star, lock)
                outer.tag = h
            } else { outer = convertView as LinearLayout; h = reuse }

            h.swipe.tag = p
            h.swipe.content.setOnClickListener { open(p) }
            h.swipe.content.setOnLongClickListener { actions(p); true }
            val showHead = query.isBlank() && (position == 0 || shown[position - 1].section != p.section)
            h.head.visibility = if (showHead) View.VISIBLE else View.GONE
            h.head.text = p.section
            h.av.background = oval(avatarColor(p.name))
            h.letter.text = initial(p)
            h.name.text = p.name
            h.sub.text = p.numbers.firstOrNull() ?: ""
            h.sub.visibility = if (p.numbers.isEmpty()) View.GONE else View.VISIBLE
            h.star.visibility = if (p.starred) View.VISIBLE else View.GONE
            h.lock.visibility = if (Vault.isHidden(this@MainActivity, p.lookup)) View.VISIBLE else View.GONE
            Thumbs.load(this@MainActivity, p.photo, h.iv, h.letter)
            return outer
        }
    }
}
