package com.fieldstock.app.model

data class InventoryTask(val id: String, val name: String, val createdAt: Long)
data class Country(val id: String, val name: String)
data class Warehouse(val id: String, val countryId: String, val name: String)
enum class ItemKind { PART, MACHINE }
data class StockRecord(
    val id: String, val taskId: String, val warehouseId: String, val kind: ItemKind,
    val bom: String, val sn: String, val quantity: Int, val condition: String, val note: String,
    val location: String,
    val sourceBom: String, val sourceSn: String, val rawBom: String, val rawSn: String,
    val createdAt: Long, val updatedAt: Long, val voided: Boolean
)
data class ChangeLog(val id: String, val recordId: String, val action: String, val before: String, val after: String, val at: Long)
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
        value.isEmpty() -> "请填写 BOM"
        !bom.matches(value) -> "BOM 只允许字母和数字，最多 14 位"
        else -> null
    }
    fun validateSn(value: String): String? = when {
        value.isEmpty() -> "请填写 SN"
        value.length > 15 || !sn.matches(value) -> "SN 需为两段字母或数字，用一个 - 分隔；不计 - 最多 14 位"
        else -> null
    }
}
