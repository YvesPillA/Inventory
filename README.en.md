# FieldStock 2.4.1

[简体中文](README.md)

An offline inventory counting app for one person using one Android phone. Manage countries and warehouses, count parts by BOM and quantity, record machines by BOM and SN, read printed labels with on-device OCR, export Excel/CSV, and save full backups. Android 8.0 or later is required.

## Choose a language

On the first launch of 2.4.1, choose **English** or **简体中文**. Existing installations also get this choice when upgrading; inventory is preserved. Change the language later in **Settings → Language / 语言**. The choice is saved on this phone and does not depend on the device's language.

Changing language does not translate or change BOMs, SNs, country/warehouse names, notes, raw recognized text, or existing history descriptions. Unsaved inventory form values are preserved when switching language.

## Count inventory

1. Create or select a country and warehouse, then tap **Enter warehouse**.
2. For parts, enter or scan a BOM and enter the quantity being added. For machines, enter or scan a BOM and SN; each machine has a quantity of 1.
3. Use **More** for condition and notes. Choose **Pending verification** when a label is incomplete. Supply at least one identifier or a note. Pending records are excluded from confirmed totals.
4. Tap **Save record**. For matching parts in the same warehouse with the same BOM and condition, choose whether to add quantities or create a separate entry.
5. Use **Records** to edit or void entries. Voided records can be restored. An active machine SN must be unique across all countries and warehouses.
6. At the end of the day, export the current warehouse and save a full JSON backup. Copy the files to a computer or another location.

BOMs contain 1–14 letters or digits. SNs contain two groups of letters or digits separated by a hyphen, with at most 14 letters/digits in total. Leading zeros and case are preserved. Case-only SN differences prompt a review.

## Import Chinese or English CSV

Download the template in **Files → Import → Download import template**. The template uses the selected language and contains only a header:

```csv
Type,BOM,SN,Quantity,Condition,Note
```

```csv
类型,BOM,SN,数量,状态,备注
```

Standard columns, in this exact order:

| English | Chinese | Accepted values |
| --- | --- | --- |
| Type | 类型 | `Part` / `Parts` / `配件`; `Machine` / `整机` |
| BOM | BOM | Letters/digits, preserving leading zeros |
| SN | SN | Empty for parts; full identifier with a hyphen for machines |
| Quantity | 数量 | Positive integer; exactly 1 for machines |
| Condition | 状态 | `Normal` / `正常`; `Damaged` / `损坏`; `Pending verification` / `Pending` / `待核查` |
| Note | 备注 | Optional free text; required for pending rows if both identifiers are empty |

Either language works under either interface language. Chinese/English columns and fixed values may be mixed. English headers and fixed values are case insensitive. Column order and validation remain strict; identifiers and notes are not translated or normalized.

Save as **CSV UTF-8**. In Excel, format identifier columns as Text before entering data. Notes containing commas, quotes, or line breaks must use standard CSV quoting. Limits: 8 MiB, 10,000 data rows, 4,000 characters per field. Errors report physical line numbers; a malformed batch is not partially imported.

Importing appends inventory. A translated copy with equivalent parsed content has the same import fingerprint, so changing language does not bypass the repeated-import reminder. Changing actual content, row order or identifiers can produce a different fingerprint. History is local to this phone and is not included in JSON backups.

XLSX, ZIP and the daily report CSV files are not standard import templates. To restore all app data, use a full JSON backup.

## Export and back up

**Files → Export** exports the current warehouse's historical active records. Names, headers, units, fixed conditions and instructions use the language selected when starting the export, even if the process is recreated while the file picker is open.

- **Excel:** Counting guide, Parts detail, Parts summary, Machine detail, and Pending verification. Identifiers are text cells; quantities are numeric.
- **CSV ZIP:** Separate parts, machine and pending reports plus instructions, all UTF-8. Text that could be interpreted as a spreadsheet formula receives a leading apostrophe. Prefer XLSX for direct viewing in Excel.
- **Full JSON backup:** All countries, warehouses, existing inventory including voided/pending records, change history, and legacy compatibility fields. The format and stored values remain compatible with older Chinese releases. JSON backups work in both languages and do not store the selected language.

Restoring merges transactionally. Identical IDs/content are not added twice. A conflicting ID cancels the merge. New machines with duplicate active SNs are kept as voided records.

Daily full backups are enabled by default. The app saves all inventory on first use each local day and keeps up to seven verified backups. Tap **Back up now** to save a later state, then **Save as** or **Share** to copy it elsewhere. Local backups are deleted when the app is uninstalled. Check that shared files have arrived on the receiving device.

Important imports, merges, deletions and batch operations save local recovery points. Up to five are retained, subject to capacity. **Settings → Undo important operation** reverses the relevant changes while preserving unrelated later records; undo stops if affected records have subsequently changed. Recovery points do not replace external backups.

## Printed-label scanning

Single and continuous OCR run on the phone without a login or network connection. Barcode and QR decoding are outside the current feature set.

Continuous scanning shows identifiers line by line. Select a label in the preview or list, review the photo and ambiguous characters, then confirm to fill the form. Scanning does not automatically save inventory. Review leading zeros, letter case, `0/O`, `1/I` and `8/B`. Test real labels for glare, blur, angle and small text before field use.

## Build and upgrade

Version: **2.4.1**, Android version code **7**, database schema **4**. Existing published 0.2.4 APKs in `artifacts/` are the earlier Chinese release; this branch adds the bilingual source. Build a release with the original signing key for an in-place upgrade. A debug APK uses a different key and is intended for testing.

With JDK 17, Android SDK 35 and build tools configured:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest
```

The connected test task requires an emulator or Android device. The repository also includes `tools/setup-android.ps1` and `tools/build.ps1` for a local toolchain.

Before upgrading, export and copy a full backup elsewhere. Install a release signed with the same key over the existing app. Do not uninstall first: uninstalling deletes the app's local data.
