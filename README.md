# Sahl Contacts · سهل لجهات الاتصال

A calm, ad-free contacts app for Arabic and English, part of the **Sahl apps** family.

## Features (v1)
- Fast search by name or number. Arabic-smart, so أ/ا/إ, ة/ه and ى/ي all match, and Arabic digits work.
- A–Z and أ–ي side index, favorites row, and group filters.
- Contact page with call, message, WhatsApp and share (vCard) buttons. Long-press to copy.
- Add and edit contacts: photo, several numbers, emails, company, birthday, note and groups.
- Choose where new contacts are saved (Google, phone…). WhatsApp's copies are never edited.
- Opens from other apps' "Add to contacts".
- Light/dark theme, Arabic/English, and an in-app update check.

## New in v2
- Hidden contacts behind your fingerprint or screen lock (🔒)
- Birthday reminders each morning
- A favorites home-screen widget: tap a person to call them
- A QR code for each contact, which any camera can scan to add it
- A duplicate finder that links duplicates safely, using Android's own merge
- Export all contacts to a .vcf backup, or import one

## Sahl Union
Sahl apps signed with the same key share data safely through the `com.sahl.permission.UNION` signature permission.
- `content://com.sahl.contacts.union/lookup/<number>` returns the caller's name, photo and starred status
- `content://com.sahl.contacts.union/favorites` returns the favorite contacts
- The `com.sahl.action.EVENT` broadcast carries `event = contacts_changed` when contacts are edited

## Install
Download the latest APK from **Releases**. After that, the app updates itself.
