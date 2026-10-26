package com.fieldstock.app.model

import com.fieldstock.app.i18n.*

data class InventoryTask(val id: String, val name: String, val createdAt: Long)
data class Country(val id: String, val name: String)
data class Warehouse(val id: String, val countryId: String, val name: String)
enum class ItemKind { PART, MACHINE }
enum class DuplicateMode { ADD_QUANTITY, NEW_ENTRY }
data class StockRecord(
    val id: String, val taskId: String, val warehouseId: String, val kind: ItemKind,
    val bom: String, val sn: String, val quantity: Int, val condition: String, val note: String,
    val location: String,
    val sourceBom: String, val sourceSn: String, val rawBom: String, val rawSn: String,
    val createdAt: Long, val updatedAt: Long, val voided: Boolean
)
data class ChangeLog(val id: String, val recordId: String, val action: String, val before: String, val after: String, val at: Long)
data class MutationResult(val processed: Int, val duplicateMachines: List<StockRecord> = emptyList())
data class ImportMetadata(val fingerprint: String, val fileName: String)
data class ImportReceipt(val id: String, val fingerprint: String, val fileName: String,
    val warehouseId: String, val warehouseLabel: String, val at: Long, val rows: Int)
data class RecoveryPoint(val id: String, val label: String, val at: Long, val records: Int, val undone: Boolean)
data class InventorySnapshot(
    val tasks: List<InventoryTask> = emptyList(), val countries: List<Country> = emptyList(),
    val warehouses: List<Warehouse> = emptyList(), val records: List<StockRecord> = emptyList(),
    val changes: List<ChangeLog> = emptyList()
)
data class RecordInput(
    val taskId: String, val warehouseId: String, val kind: ItemKind, val bom: String, val sn: String,
    val quantity: Int, val condition: String, val note: String, val location: String = "",
    val sourceBom: String = "manual", val sourceSn: String = "manual", val rawBom: String = "", val rawSn: String = ""
)
class DuplicateSnException(val existing: StockRecord, message: String) : IllegalArgumentException(message)

object IdentifierRules {
    private val bom = Regex("[A-Za-z0-9]{1,14}")
    private val sn = Regex("[A-Za-z0-9]+-[A-Za-z0-9]+")
    fun validateBom(value: String): String? = when {
        value.isEmpty() -> tr(TextKey.ENTER_A_BOM)
        !bom.matches(value) -> tr(TextKey.BOM_MUST_CONTAIN_ONLY_LETTERS_AND_DIGITS_UP_TO)
        else -> null
    }
    fun validateSn(value: String): String? = when {
        value.isEmpty() -> tr(TextKey.ENTER_AN_SN)
        value.length > 15 || !sn.matches(value) -> tr(TextKey.SN_MUST_CONTAIN_TWO_GROUPS_OF_LETTERS_OR_DIGITS)
        else -> null
    }
}
