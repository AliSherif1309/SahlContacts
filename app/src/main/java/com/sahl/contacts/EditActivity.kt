package com.sahl.contacts

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class EditActivity : SahlActivity() {
    private var contactId = 0L
    private var rawId: Long? = null
    private var account = Account(null, null)
    private var accounts: List<Account> = emptyList()
    private var birthday: String? = null
    private var groupIds = mutableSetOf<Long>()
    private var photo: ByteArray? = null

    private lateinit var body: LinearLayout
    private lateinit var nameEt: EditText
    private lateinit var companyEt: EditText
    private lateinit var noteEt: EditText
    private lateinit var phonesBox: LinearLayout
    private lateinit var emailsBox: LinearLayout
    private lateinit var bdayBtn: TextView
    private lateinit var groupsBtn: TextView
    private lateinit var photoView: ImageView
    private lateinit var accountBox: LinearLayout

    private val phoneTypes = listOf(Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK, Phone.TYPE_OTHER)
    private val phoneLabels by lazy { listOf(R.string.mobile, R.string.home, R.string.work, R.string.other).map { getString(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = intent.getLongExtra("id", 0L)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(if (contactId > 0) R.string.edit_contact else R.string.new_contact), true,
            listOf(R.drawable.ic_check to { save() })))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(16), px(16), px(40)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // Photo
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = oval(col(R.color.tonal)); imageTintList = android.content.res.ColorStateList.valueOf(col(R.color.emerald))
            clipToOutline = true; setImageResource(R.drawable.ic_person); setPadding(px(20), px(20), px(20), px(20))
            setOnClickListener { pickPhoto() }
        }
        body.addView(photoView, LinearLayout.LayoutParams(px(96), px(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(text(getString(R.string.change_photo), 13f, col(R.color.emerald)).apply {
            gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(8)); setOnClickListener { pickPhoto() }
        })

        accountBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(accountBox)

        nameEt = field(R.string.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        label(R.string.phone)
        phonesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(phonesBox)
        body.addView(link(R.string.add_phone) { addPhone("", Phone.TYPE_MOBILE) })
        label(R.string.email)
        emailsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(emailsBox)
        body.addView(link(R.string.add_email) { addEmail("") })
        companyEt = field(R.string.company, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        label(R.string.birthday)
        bdayBtn = pill("") { pickBirthday() }
        label(R.string.groups)
        groupsBtn = pill("") { pickGroups() }
        noteEt = field(R.string.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

        Thread {
            val acc = Repo.accounts(this)
            val f = if (contactId > 0) Repo.full(this, contactId, true) else null
            runOnUiThread { fill(acc, f) }
        }.start()
    }

    private fun fill(acc: List<Account>, f: Full?) {
        accounts = acc
        if (f != null) {
            rawId = f.rawId; account = f.account
            nameEt.setText(f.name); companyEt.setText(f.company ?: ""); noteEt.setText(f.note ?: "")
            for (p in f.phones) addPhone(p.number, p.type)
            for (e in f.emails) addEmail(e)
            birthday = f.birthday; groupIds = f.groupIds.toMutableSet()
            ContactsContract.Contacts.openContactPhotoInputStream(contentResolver,
                android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, f.id), true)
                ?.use { BitmapFactory.decodeStream(it) }?.let { showPhoto(it) }
        } else {
            account = acc.first()
            // Opened from another app: "Add to contacts"
            intent.getStringExtra(ContactsContract.Intents.Insert.NAME)?.let { nameEt.setText(it) }
            addPhone(intent.getStringExtra(ContactsContract.Intents.Insert.PHONE) ?: "", Phone.TYPE_MOBILE)
            intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL)?.let { addEmail(it) }
            buildAccountPicker()
        }
        refreshPills()
    }

    private fun buildAccountPicker() {
        accountBox.removeAllViews()
        if (accounts.size < 2) return
        label(R.string.save_to, accountBox)
        val names = accounts.map { it.name ?: getString(R.string.phone_only) }
        accountBox.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@EditActivity, android.R.layout.simple_spinner_dropdown_item, names)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (accounts[pos] != account) { account = accounts[pos]; groupIds.clear(); refreshPills() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }

    private fun label(id: Int, parent: LinearLayout = body) =
        parent.addView(text(getString(id), 13f, col(R.color.emerald), true).apply { setPadding(px(4), px(14), 0, px(4)) })

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 16f; typeface = font
        setTextColor(col(R.color.text)); setHintTextColor(col(R.color.sub))
        background = round(col(R.color.tonal), 16f); setPadding(px(14), px(12), px(14), px(12))
    }

    private fun field(hintId: Int, type: Int): EditText {
        label(hintId)
        return input(getString(hintId), type).also { body.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun link(id: Int, go: () -> Unit) = text(getString(id), 14f, col(R.color.emerald), true).apply {
        setPadding(px(4), px(8), px(4), px(4)); setOnClickListener { go() }
    }

    private fun pill(s: String, go: () -> Unit) = text(s, 15f).apply {
        background = round(col(R.color.tonal), 16f); setPadding(px(14), px(12), px(14), px(12))
        setOnClickListener { go() }
        body.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addPhone(number: String, type: Int) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, px(6)) }
        val et = input(getString(R.string.phone), InputType.TYPE_CLASS_PHONE).apply {
            setText(number); textDirection = View.TEXT_DIRECTION_LTR
        }
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditActivity, android.R.layout.simple_spinner_dropdown_item, phoneLabels)
            setSelection(phoneTypes.indexOf(type).let { if (it < 0) 3 else it })
        }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(sp)
        row.addView(iconButton(R.drawable.ic_close, 0, col(R.color.sub), 40, 18) { phonesBox.removeView(row) })
        row.tag = Pair(et, sp)
        phonesBox.addView(row)
    }

    private fun addEmail(value: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, px(6)) }
        val et = input(getString(R.string.email), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            .apply { setText(value) }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(iconButton(R.drawable.ic_close, 0, col(R.color.sub), 40, 18) { emailsBox.removeView(row) })
        row.tag = et
        emailsBox.addView(row)
    }

    private fun refreshPills() {
        bdayBtn.text = birthday?.let { b ->
            try { LocalDate.parse(if (b.startsWith("--")) "2000" + b.drop(1) else b).toString() } catch (e: Exception) { b }
        } ?: getString(R.string.set_birthday)
        Thread {
            val gs = Repo.groupsFor(this, account)
            runOnUiThread {
                val names = gs.filter { it.id in groupIds }.map { it.title }
                groupsBtn.text = if (names.isEmpty()) getString(R.string.choose_groups) else names.joinToString(" · ")
            }
        }.start()
    }

    private fun pickBirthday() {
        val parsed: LocalDate? = try { birthday?.let { LocalDate.parse(it) } } catch (e: Exception) { null }
        val d = parsed ?: LocalDate.of(1990, 1, 1)
        DatePickerDialog(this, { _, y, m, day -> birthday = LocalDate.of(y, m + 1, day).toString(); refreshPills() },
            d.year, d.monthValue - 1, d.dayOfMonth).apply {
            setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.clear)) { _, _ -> birthday = null; refreshPills() }
        }.show()
    }

    private fun pickGroups() {
        Thread {
            val gs = Repo.groupsFor(this, account)
            runOnUiThread {
                val checked = BooleanArray(gs.size) { gs[it].id in groupIds }
                AlertDialog.Builder(this).setTitle(R.string.choose_groups)
                    .setMultiChoiceItems(gs.map { it.title }.toTypedArray(), checked) { _, i, on ->
                        if (on) groupIds.add(gs[i].id) else groupIds.remove(gs[i].id)
                    }
                    .setPositiveButton(R.string.save) { _, _ -> refreshPills() }
                    .setNeutralButton(R.string.new_group) { _, _ -> newGroup() }
                    .show()
            }
        }.start()
    }

    private fun newGroup() {
        val et = input(getString(R.string.group_name), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        AlertDialog.Builder(this).setTitle(R.string.new_group).setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) Repo.createGroup(this, account, t)?.let { groupIds.add(it) }
                refreshPills()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun pickPhoto() {
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 20) } catch (e: Exception) { }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != 20 || res != RESULT_OK) return
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 1440) sample *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return
            val side = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(sq, 720.coerceAtMost(side), 720.coerceAtMost(side), true)
            photo = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            showPhoto(small)
        } catch (e: Exception) { }
    }

    private fun showPhoto(b: Bitmap) { photoView.setPadding(0, 0, 0, 0); photoView.imageTintList = null; photoView.setImageBitmap(b) }

    private fun save() {
        val name = nameEt.text.toString()
        val phones = (0 until phonesBox.childCount).mapNotNull {
            val pair = phonesBox.getChildAt(it).tag as Pair<*, *>
            val et = pair.first as EditText
            val sp = pair.second as Spinner
            val n = et.text.toString().trim()
            if (n.isEmpty()) null else PhoneNum(n, phoneTypes[sp.selectedItemPosition.coerceIn(0, 3)])
        }
        val emails = (0 until emailsBox.childCount).map { (emailsBox.getChildAt(it).tag as EditText).text.toString().trim() }
            .filter { it.isNotEmpty() }
        if (name.isBlank() && phones.isEmpty()) { toast(R.string.name_required); return }
        val acc = account; val raw = rawId; val bd = birthday; val gids = groupIds.toSet(); val ph = photo
        val company = companyEt.text.toString(); val note = noteEt.text.toString()
        Thread {
            val id = Repo.save(this, raw, acc, name, phones, emails, bd, company, note, gids, ph)
            runOnUiThread {
                if (id == null) { toast(R.string.save_failed); return@runOnUiThread }
                toast(R.string.saved)
                if (contactId == 0L) startActivity(Intent(this, DetailActivity::class.java).putExtra("id", id))
                finish()
            }
        }.start()
    }
}
