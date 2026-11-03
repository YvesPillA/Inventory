# FieldStock

[简体中文](README.md)

An offline Android inventory app for parts and machines, organized by country and warehouse. Includes Chinese and English, printed-label OCR, Excel/CSV reports, and full backups. No account or internet connection required.

## Download

Current version: **2.4.4**. Requires **Android 8.0 or later**.

[Release APK](artifacts/盘库助手-2.4.4.apk) · [Source archive](artifacts/盘库助手-2.4.4-源码.zip) · [Checksums](artifacts/SHA256SUMS.txt)

Export full backups regularly and copy them to another device.

## Features

- **Parts and machines:** Count parts by BOM and quantity, with existing records shown after typing or scanning. Machines require only an SN; BOM and PN are optional.
- **Offline scanning:** Read BOM, PN, SN, and multiline notes. Review recognized text before saving.
- **Chinese and English:** Choose a language on first use or change it in Settings. Import templates, reports, and backups from either language.
- **Import and export:** Export Excel/CSV reports and full JSON backups, then import them directly. Includes duplicate checks and warehouse merging.
- **Guidance and recovery:** Built-in quick start, local automatic backups, recovery points, and diagnostic logs.

## Use and development

Choose a language, follow the quick start, and create a country and warehouse to begin counting. Export reports and a full backup when finished.

[Operating guide](docs/操作说明.md) · [Build and signing](docs/构建与发布.md) · [Version history](docs/版本记录.md) · [Validation](docs/验收记录.md) (Chinese)

Built with Kotlin, Jetpack Compose, and SQLite. Requires JDK 17 and Android SDK 35. Signing keys and passwords are kept privately by the maintainer and excluded from the repository and public source archives.
