# FieldStock

[简体中文](README.md)

An offline inventory app for one person using one Android phone. Count parts and machines across countries and warehouses, read printed labels, export reports, and keep full backups. No account or network connection is required.

## Download and install

Requires **Android 8.0 or later**. Current source: **2.4.1** (version code 7).

[2.4.1 release APK](artifacts/盘库助手-2.4.1.apk) · [Source archive](artifacts/盘库助手-2.4.1-源码.zip) · [SHA256 checksums](artifacts/SHA256SUMS.txt)

This release is for first-time customer installations and uses a new fixed signing key for future updates. It cannot replace earlier Chinese builds signed with the original key. If you already use an earlier build, export a full JSON backup and copy it elsewhere; **do not uninstall before saving your backup**.

## Features

- **Count and manage:** Parts by BOM and quantity; machines by BOM and SN, one record per machine. Edit, void, restore, batch-manage records, and merge warehouses within a country.
- **Scan offline:** Single or continuous OCR for printed identifiers, with photo zoom and ambiguous-character review. Confirm the value and save it manually. Barcode and QR decoding are outside the feature set.
- **Chinese and English:** Choose a language on first use and change it in Settings. UI, reports and templates follow the selected language; imports accept either language. Identifiers, names and notes retain their original text.
- **Export and recover:** Excel/CSV for the current warehouse; full JSON backups for all warehouses. Keep up to seven local backups and up to five recovery points for important operations, subject to capacity.
- **Check quantities:** Preview import totals and review duplicate-file warnings. Normal and damaged quantities are separate; pending and voided records are excluded from confirmed totals.

## Get started

1. Choose English or Chinese, then create or select a country and warehouse.
2. Enter or scan the BOM. Add a quantity for parts, or an SN for each machine. Use **More** for condition and notes.
3. Save, then review entries in **Records**. At the end of the day, use **Files → Export** to save reports and a full backup, and copy them to another device.

BOMs contain 1–14 letters or digits. SNs have two nonempty groups joined by `-`, with at most 14 letters/digits in total. Leading zeros and case are preserved. An active machine SN must be unique across all countries and warehouses.

## Import and export

Download the template in **Files → Import**. Imports require **UTF-8 CSV** and accept Chinese, English or mixed headers in this order. English headers and fixed values are case-insensitive.

| English / Chinese | Values |
| --- | --- |
| Type / 类型 | Part / Parts / 配件; Machine / 整机 |
| BOM | Letters or digits, preserving the original identifier |
| SN | Empty for parts; full `prefix-suffix` for machines |
| Quantity / 数量 | Positive integer; exactly 1 for machines |
| Condition / 状态 | Normal / 正常; Damaged / 损坏; Pending verification / Pending / 待核查 |
| Note / 备注 | Original free text; pending rows need at least one identifier or a note |

Format BOM and SN columns as text before entering values in Excel, then save as CSV UTF-8. Limits: 8 MiB and 10,000 rows. Invalid files report a row number and cancel the entire batch. CSV adds inventory; accepting a repeated import adds it again.

Excel reports contain the counting guide, parts detail/summary, machine detail and pending records. CSV ZIP contains three reports and instructions. **Report CSVs, XLSX and ZIP files are not import templates.** Use full JSON backups to migrate all warehouses, voided records and change history. Identical backup records are skipped; conflicting IDs cancel the merge, and newly imported duplicate machine SNs become voided records.

Local backups and recovery points are deleted on uninstall. Recovery points, CSV import history, preferences and scan photos are not included in portable backups.

## Development and documentation

Built with Kotlin, Jetpack Compose, SQLite, CameraX and bundled ML Kit OCR. Requires JDK 17 and Android SDK 35:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

See the [build and signing guide](docs/构建与发布.md), [operating guide](docs/操作说明.md), [version history](docs/版本记录.md), and [validation records](docs/验收记录.md) (Chinese). Check actual labels, exports and backup recovery on your phone before field use.
