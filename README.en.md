# FieldStock

[简体中文](README.md)

An offline inventory app for one person using one Android phone. Count parts and machines across countries and warehouses, read printed labels, export reports, and keep full backups. No account or network connection is required.

## Download and install

Requires **Android 8.0 or later**. Current source: **2.4.3** (version code 9).

[2.4.3 release APK](artifacts/盘库助手-2.4.3.apk) · [Source archive](artifacts/盘库助手-2.4.3-源码.zip) · [SHA256 checksums](artifacts/SHA256SUMS.txt)

This release uses the same fixed customer signing key as 2.4.1 and can update it directly. It cannot replace earlier Chinese builds signed with the original key. If you already use an earlier build, export a full JSON backup and copy it elsewhere; **do not uninstall before saving your backup**.

## Features

- **Count and manage:** Parts by BOM and quantity, with an existing-stock check after typing or scanning. Machines require SN; BOM and PN are optional. Edit, void, restore, batch-manage records, and merge warehouses within a country.
- **Scan offline:** Single or continuous OCR for BOM, PN and SN; multiline note scanning for both parts and machines, with photo zoom and ambiguous-character review. Confirm the value and save it manually. Barcode and QR decoding are outside the feature set.
- **Chinese and English:** Choose a language on first use and change it in Settings. UI, reports and templates follow the selected language; imports accept either language. Identifiers, names and notes retain their original text.
- **Export and recover:** Excel/CSV for the current warehouse; full JSON backups for all warehouses. Keep up to seven local backups and up to five recovery points for important operations, subject to capacity.
- **Check quantities:** Preview import totals and review duplicate-file warnings. Normal and damaged quantities are separate; pending and voided records are excluded from confirmed totals.

## Get started

1. Choose English or Chinese, then create or select a country and warehouse.
2. For parts, enter or scan the BOM and add a quantity; existing records appear below. For machines, enter an SN and optionally a BOM and PN. Use **More** for condition and notes, including note scanning.
3. Save, then review entries in **Records**. At the end of the day, use **Files → Export** to save reports and a full backup, and copy them to another device.

BOMs contain 1–14 letters or digits. PNs allow up to 100 letters/digits with multiple hyphens connecting nonempty groups. SNs have two nonempty groups joined by `-`, with at most 14 letters/digits in total. Leading zeros and case are preserved. An active machine SN must be unique across all countries and warehouses.

## Import and export

In **Files → Import**, choose an exported **Excel workbook, CSV ZIP, full JSON backup** or standard CSV directly. Chinese and English files work in either interface language. Excel/CSV rows go into the selected warehouse; JSON backups retain their original countries, warehouses, records and history and require confirmation before merging.

The downloadable **UTF-8 CSV** template accepts Chinese, English or mixed headers in this order. English headers and fixed values are case-insensitive.

| English / Chinese | Values |
| --- | --- |
| Type / 类型 | Part / Parts / 配件; Machine / 整机 |
| BOM | Letters or digits; optional for machines |
| SN | Empty for parts; full `prefix-suffix` for machines |
| Quantity / 数量 | Positive integer; exactly 1 for machines |
| Condition / 状态 | Normal / 正常; Damaged / 损坏; Pending verification / Pending / 待核查 |
| Note / 备注 | Original free text and line breaks; pending rows need at least one identifier or a note |
| PN | Optional for machines; letters, digits and multiple hyphens. Empty for parts |

Legacy six-column CSVs without PN still work; the new template appends PN as the seventh column. Format BOM, PN and SN columns as text before entering values in Excel, then save as CSV UTF-8. Limits: 8 MiB and 10,000 rows. Invalid files report a row number and cancel the entire batch. CSV adds inventory; accepting a repeated import adds it again.

Excel reports contain the counting guide, parts detail/summary, machine detail and pending records. CSV ZIP contains three reports and instructions. **Reports can be imported directly.** Only parts, machine and pending detail rows are read; guide and summary sheets are skipped to prevent double counting. Older reports without PN also work. Identifiers, case, leading zeros and multiline notes are retained as stored in the file. Safety apostrophes in report CSVs remain literal; use JSON for a complete migration. JSON backups allow up to 32 MiB and preserve all warehouses, voided records and change history. Identical backup records are skipped; conflicting IDs cancel the merge, and newly imported duplicate machine SNs become voided records.

2.4.3 reads legacy backups. New format-v2 backups preserve PN and require 2.4.2 or a later compatible version to restore. Local backups and recovery points are deleted on uninstall. Recovery points, CSV import history, preferences and scan photos are not included in portable backups.

## Development and documentation

Built with Kotlin, Jetpack Compose, SQLite, CameraX and bundled ML Kit OCR. Requires JDK 17 and Android SDK 35:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

See the [build and signing guide](docs/构建与发布.md), [operating guide](docs/操作说明.md), [version history](docs/版本记录.md), and [validation records](docs/验收记录.md) (Chinese). Check actual labels, exports and backup recovery on your phone before field use.
