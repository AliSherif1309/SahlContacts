package com.sahl.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts

data class Person(
    val id: Long, val lookup: String, val name: String, val photo: String?,
    val starred: Boolean, val numbers: List<String>,
    val key: String, val section: String, val digits: String
)

data class Account(val type: String?, val name: String?)
data class Group(val id: Long, val title: String)
data class PhoneNum(val number: String, val type: Int)

/** Everything shown/edited for one contact. */
data class Full(
    val id: Long, val lookup: String, val rawId: Long, val account: Account,
    val name: String, val starred: Boolean,
    val phones: List<PhoneNum>, val emails: List<String>,
    val birthday: String?, val company: String?, val note: String?,
    val groupIds: Set<Long>
)

/** Reads and writes Android's shared contacts database. */
object Repo {
    /** Messaging apps add their own copies of contacts — never edit those. */
    private val foreign = listOf("whatsapp", "telegram", "thoughtcrime", "viber", "signal", "skype", "duo", "meet")
    private fun isForeign(type: String?) = type != null && foreign.any { type.contains(it, true) }

    fun list(c: Context): List<Person> {
        val cr = c.contentResolver
        val nums = HashMap<Long, MutableList<String>>()
        cr.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER), null, null, null)?.use {
            while (it.moveToNext()) {
                val n = it.getString(1) ?: continue
                val l = nums.getOrPut(it.getLong(0)) { mutableListOf() }
                if (l.none { x -> Norm.phoneKey(x) == Norm.phoneKey(n) }) l.add(n)
            }
        }
        val out = ArrayList<Person>()
        cr.query(Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI, Contacts.STARRED),
            null, null, null)?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val n = nums[id] ?: emptyList()
                val name = it.getString(2)?.takeIf { s -> s.isNotBlank() } ?: n.firstOrNull() ?: continue
                val sec = Norm.section(name)
                val dig = n.joinToString(" ") { x -> Norm.digits(x) + " " + Norm.phoneKey(x) }
                out.add(Person(id, it.getString(1) ?: "", name, it.getString(3), it.getInt(4) == 1, n,
                    Norm.text(name), sec, dig))
            }
        }
        out.sortWith(compareBy<Person>({ Norm.group(it.section) }, { it.key }))
        return out
    }

    /** Groups merged by title (same group can exist in several accounts). */
    fun groups(c: Context): Map<String, Set<Long>> {
        val byTitle = LinkedHashMap<String, MutableSet<Long>>()
        c.contentResolver.query(Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE),
            "${Groups.DELETED}=0", null, Groups.TITLE)?.use {
            while (it.moveToNext()) {
                val t = it.getString(1)?.trim() ?: continue
                if (t.isEmpty() || t.startsWith("System Group") || t.startsWith("Starred in") || t == "My Contacts") continue
                if (t.startsWith("Restored from") || t.startsWith("تمت الاستعادة")) continue
                byTitle.getOrPut(t) { mutableSetOf() }.add(it.getLong(0))
            }
        }
        return byTitle
    }

    /** groupId -> contact ids */
    fun members(c: Context): Map<Long, Set<Long>> {
        val m = HashMap<Long, MutableSet<Long>>()
        c.contentResolver.query(Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, GroupMembership.GROUP_ROW_ID),
            "${Data.MIMETYPE}=?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null)?.use {
            while (it.moveToNext()) m.getOrPut(it.getLong(1)) { mutableSetOf() }.add(it.getLong(0))
        }
        return m
    }

    fun groupsFor(c: Context, a: Account): List<Group> {
        val out = ArrayList<Group>()
        val sel = if (a.type == null) "${Groups.DELETED}=0 AND ${Groups.ACCOUNT_TYPE} IS NULL"
        else "${Groups.DELETED}=0 AND ${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=?"
        val args = if (a.type == null) null else arrayOf(a.type, a.name ?: "")
        c.contentResolver.query(Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE), sel, args, Groups.TITLE)?.use {
            while (it.moveToNext()) {
                val t = it.getString(1)?.trim() ?: continue
                if (t.isEmpty() || t.startsWith("System Group") || t.startsWith("Starred in") || t == "My Contacts") continue
                out.add(Group(it.getLong(0), t))
            }
        }
        return out
    }

    fun createGroup(c: Context, a: Account, title: String): Long? {
        val v = ContentValues().apply {
            put(Groups.TITLE, title); put(Groups.GROUP_VISIBLE, 1)
            put(Groups.ACCOUNT_TYPE, a.type); put(Groups.ACCOUNT_NAME, a.name)
        }
        return c.contentResolver.insert(Groups.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
    }

    /** Accounts you can save new contacts to (Google, phone, SIM…). */
    fun accounts(c: Context): List<Account> {
        val set = LinkedHashSet<Account>()
        c.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.DELETED}=0", null, null)?.use {
            while (it.moveToNext()) {
                val t = it.getString(0)
                if (t != null && !isForeign(t)) set.add(Account(t, it.getString(1)))
            }
        }
        val list = set.sortedBy { if (it.type == "com.google") 0 else 1 }.toMutableList()
        list.add(Account(null, null))
        return list
    }

    /** The raw contact we edit: the user's own copy, not WhatsApp's. */
    private fun pickRaw(c: Context, contactId: Long): Pair<Long, Account>? {
        var best: Pair<Long, Account>? = null
        c.contentResolver.query(RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()), null)?.use {
            while (it.moveToNext()) {
                val t = it.getString(1)
                val cand = it.getLong(0) to Account(t, it.getString(2))
                if (!isForeign(t)) { if (best == null || t == "com.google") best = cand }
                else if (best == null) best = cand
            }
        }
        return best
    }

    /** Loads a contact. editOnly = only the editable raw contact's rows. */
    fun full(c: Context, contactId: Long, editOnly: Boolean): Full? {
        val cr = c.contentResolver
        var lookup = ""; var starred = false; var display = ""
        cr.query(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
            arrayOf(Contacts.LOOKUP_KEY, Contacts.STARRED, Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use {
            if (!it.moveToFirst()) return null
            lookup = it.getString(0) ?: ""; starred = it.getInt(1) == 1; display = it.getString(2) ?: ""
        } ?: return null
        val raw = pickRaw(c, contactId) ?: return null
        val sel = if (editOnly) "${Data.RAW_CONTACT_ID}=?" else "${Data.CONTACT_ID}=?"
        val arg = arrayOf((if (editOnly) raw.first else contactId).toString())
        val phones = ArrayList<PhoneNum>(); val emails = ArrayList<String>(); val groups = HashSet<Long>()
        var name: String? = null; var bday: String? = null; var company: String? = null; var note: String? = null
        cr.query(Data.CONTENT_URI, arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.RAW_CONTACT_ID), sel, arg, null)?.use {
            while (it.moveToNext()) {
                val v = it.getString(1) ?: continue
                when (it.getString(0)) {
                    StructuredName.CONTENT_ITEM_TYPE -> if (name == null || it.getLong(3) == raw.first) name = v
                    Phone.CONTENT_ITEM_TYPE -> if (phones.none { p -> Norm.phoneKey(p.number) == Norm.phoneKey(v) })
                        phones.add(PhoneNum(v, it.getInt(2)))
                    Email.CONTENT_ITEM_TYPE -> if (v !in emails) emails.add(v)
                    Event.CONTENT_ITEM_TYPE -> if (it.getInt(2) == Event.TYPE_BIRTHDAY) bday = v
                    Organization.CONTENT_ITEM_TYPE -> company = v
                    Note.CONTENT_ITEM_TYPE -> if (v.isNotBlank()) note = v
                    GroupMembership.CONTENT_ITEM_TYPE -> v.toLongOrNull()?.let { g -> groups.add(g) }
                }
            }
        }
        return Full(contactId, lookup, raw.first, raw.second, name ?: (if (editOnly) "" else display), starred,
            phones, emails, bday, company, note, groups)
    }

    fun setStar(c: Context, id: Long, on: Boolean) {
        c.contentResolver.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, id),
            ContentValues().apply { put(Contacts.STARRED, if (on) 1 else 0) }, null, null)
    }

    fun delete(c: Context, id: Long, lookup: String) {
        val uri = if (lookup.isNotEmpty()) Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookup)
        else ContentUris.withAppendedId(Contacts.CONTENT_URI, id)
        c.contentResolver.delete(uri, null, null)
    }

    /**
     * Saves a contact. rawId == null creates a new one in [account].
     * Returns the contact id, or null on failure.
     */
    fun save(
        c: Context, rawId: Long?, account: Account, name: String, phones: List<PhoneNum>, emails: List<String>,
        birthday: String?, company: String?, note: String?, groupIds: Set<Long>, photo: ByteArray?
    ): Long? {
        val ops = ArrayList<ContentProviderOperation>()
        fun row(mime: String): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mime)
            return if (rawId == null) b.withValueBackReference(Data.RAW_CONTACT_ID, 0) else b.withValue(Data.RAW_CONTACT_ID, rawId)
        }
        if (rawId == null) {
            ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(RawContacts.ACCOUNT_NAME, account.name).build())
        } else {
            val managed = mutableListOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
                Organization.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, GroupMembership.CONTENT_ITEM_TYPE)
            if (photo != null) managed.add(Photo.CONTENT_ITEM_TYPE)
            for (m in managed) ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(rawId.toString(), m)).build())
            ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=? AND ${Data.DATA2}=?",
                    arrayOf(rawId.toString(), Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY.toString())).build())
        }
        if (name.isNotBlank()) ops.add(row(StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name.trim()).build())
        for (p in phones) if (p.number.isNotBlank())
            ops.add(row(Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, p.number.trim()).withValue(Phone.TYPE, p.type).build())
        for (e in emails) if (e.isNotBlank())
            ops.add(row(Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, e.trim()).withValue(Email.TYPE, Email.TYPE_OTHER).build())
        if (!birthday.isNullOrBlank())
            ops.add(row(Event.CONTENT_ITEM_TYPE).withValue(Event.START_DATE, birthday).withValue(Event.TYPE, Event.TYPE_BIRTHDAY).build())
        if (!company.isNullOrBlank()) ops.add(row(Organization.CONTENT_ITEM_TYPE).withValue(Organization.COMPANY, company.trim()).build())
        if (!note.isNullOrBlank()) ops.add(row(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, note.trim()).build())
        for (g in groupIds) ops.add(row(GroupMembership.CONTENT_ITEM_TYPE).withValue(GroupMembership.GROUP_ROW_ID, g).build())
        if (photo != null) ops.add(row(Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, photo).build())
        return try {
            val res = c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val raw = rawId ?: ContentUris.parseId(res[0].uri!!)
            var cid: Long? = null
            c.contentResolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw),
                arrayOf(RawContacts.CONTACT_ID), null, null, null)?.use { if (it.moveToFirst()) cid = it.getLong(0) }
            Sahl.announce(c, Sahl.EVENT_CONTACTS_CHANGED)
            cid
        } catch (e: Exception) { null }
    }
}
