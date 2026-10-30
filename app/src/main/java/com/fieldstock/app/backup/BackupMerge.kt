package com.fieldstock.app.backup

import com.fieldstock.app.i18n.*
import com.fieldstock.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class BackupWarehouseAction { MERGE, RENAME }
data class BackupWarehouseChoice(val action: BackupWarehouseAction, val name: String = "")
data class BackupMergeChoices(
    val countries: Set<String> = emptySet(),
    val warehouses: Map<String, BackupWarehouseChoice> = emptyMap(),
    val mode: DuplicateMode = DuplicateMode.NEW_ENTRY,
)
data class BackupCountryConflict(val incoming: Country, val existing: Country)
data class BackupWarehouseConflict(val incoming: Warehouse, val existing: Warehouse, val country: String)
data class BackupMergePlan(
    val snapshot: InventorySnapshot,
    val quantityWarehouses: Set<String>,
    val sources: Map<String, StockRecord>,
    val skipped: Int,
)

/** Resolves names before repository validation; never mutates either snapshot. */
object BackupMerge {
    private fun key(name: String) = name.trim().lowercase(Locale.ROOT)

    private data class Origin(val source: StockRecord, val destination: String)
    internal fun markers(json: List<String>): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        val pending = java.util.ArrayDeque<Any>()
        json.forEach { runCatching { pending.add(JSONObject(it)) } }
        while (pending.isNotEmpty()) {
            when (val value = pending.removeLast()) {
                is JSONObject -> {
                    value.optJSONObject("backupImport")?.let(found::add)
                    value.keys().asSequence().toList().forEach { key -> value.opt(key)?.let { if (it is JSONObject || it is JSONArray) pending.add(it) } }
                }
                is JSONArray -> for (i in 0 until value.length()) value.opt(i)?.let { if (it is JSONObject || it is JSONArray) pending.add(it) }
            }
        }
        return found
    }

    private fun origins(current: InventorySnapshot): Map<String, Origin> {
        val found = linkedMapOf<String, Origin>()
        markers(current.changes.map { it.after }).forEach { marker ->
            // A string keeps the original identity intact when live audit references move.
            runCatching {
                val source = stockRecord(JSONObject(marker.getString("source")))
                val target = marker.getJSONObject("destination").getString("warehouseId")
                found.putIfAbsent(source.id, Origin(source, target))
            }
        }
        return found
    }
    private fun stockRecord(j: JSONObject) = StockRecord(
        j.getString("id"), j.getString("taskId"), j.getString("warehouseId"), ItemKind.valueOf(j.getString("kind")),
        j.getString("bom"), j.getString("sn"), j.getInt("quantity"), j.getString("condition"), j.getString("note"),
        j.optString("location", ""), j.getString("sourceBom"), j.getString("sourceSn"), j.getString("rawBom"),
        j.getString("rawSn"), j.getLong("createdAt"), j.getLong("updatedAt"), j.getBoolean("voided"),
        j.optString("pn", ""), j.optString("sourcePn", "manual"), j.optString("rawPn", ""),
    )

    private fun knownWarehouses(current: InventorySnapshot, incoming: InventorySnapshot): Map<String, Warehouse> {
        val origins = origins(current)
        val targets = current.warehouses.associateBy { it.id }
        return incoming.records.mapNotNull { record ->
            origins[record.id]?.takeIf { it.source == record }?.let { origin ->
                targets[origin.destination]?.let { record.warehouseId to it }
            }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (source, values) ->
            values.distinctBy { it.id }.singleOrNull()?.let { source to it }
        }.toMap()
    }

    fun countryConflicts(current: InventorySnapshot, incoming: InventorySnapshot): List<BackupCountryConflict> {
        val known = knownWarehouses(current, incoming)
        val knownCountries = incoming.warehouses.mapNotNull { w -> known[w.id]?.let { w.countryId to it.countryId } }.toMap()
        return incoming.countries.mapNotNull { country ->
            if (country.id in knownCountries || current.countries.any { it.id == country.id }) null
            else current.countries.find { key(it.name) == key(country.name) }?.let { BackupCountryConflict(country, it) }
        }
    }

    fun warehouseConflicts(current: InventorySnapshot, incoming: InventorySnapshot): List<BackupWarehouseConflict> {
        val known = knownWarehouses(current, incoming)
        val countryIds = incoming.countries.associate { country ->
            country.id to (current.countries.find { it.id == country.id || key(it.name) == key(country.name) }?.id ?: country.id)
        }
        return incoming.warehouses.mapNotNull { warehouse ->
            if (warehouse.id in known || current.warehouses.any { it.id == warehouse.id }) null
            else current.warehouses.find { it.countryId == countryIds[warehouse.countryId] && key(it.name) == key(warehouse.name) }
                ?.let { BackupWarehouseConflict(warehouse, it, incoming.countries.find { c -> c.id == warehouse.countryId }?.name.orEmpty()) }
        }
    }

    fun prepare(current: InventorySnapshot, incoming: InventorySnapshot, choices: BackupMergeChoices): BackupMergePlan {
        fun ids(label: TextKey, values: List<String>) {
            val unique = mutableSetOf<String>()
            values.forEach { id ->
                require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { tr(TextKey.INVALID_DATA_ID) }
                require(unique.add(id)) { tr(TextKey.DUPLICATE_ID_IN_BACKUP, tr(label), id) }
            }
        }
        ids(TextKey.COUNTRY, incoming.countries.map { it.id })
        ids(TextKey.WAREHOUSE, incoming.warehouses.map { it.id })
        ids(TextKey.INVENTORY_RECORDS, incoming.records.map { it.id })
        ids(TextKey.INVENTORY_TASK, incoming.tasks.map { it.id })
        ids(TextKey.CHANGE_HISTORY, incoming.changes.map { it.id })
        fun name(value: String, label: TextKey) {
            require(value == value.trim() && value.isNotEmpty() && value.length <= 100 && value.none(Char::isISOControl)) {
                tr(TextKey.NAME_MUST_CONTAIN_1_TO_100_CHARACTERS_WITH_NO, tr(label))
            }
        }
        incoming.countries.forEach { name(it.name, TextKey.COUNTRY) }
        incoming.warehouses.forEach { name(it.name, TextKey.WAREHOUSE) }
        val names = mutableSetOf<String>()
        incoming.countries.forEach { require(names.add(key(it.name))) { tr(TextKey.BACKUP_CONTAINS_DUPLICATE_COUNTRY_NAMES, it.name) } }
        val warehouseNames = mutableSetOf<Pair<String, String>>()
        incoming.warehouses.forEach { require(warehouseNames.add(it.countryId to key(it.name))) { tr(TextKey.DUPLICATE_WAREHOUSE_NAMES_IN_ONE_COUNTRY, it.name) } }
        val origins = origins(current)
        val known = knownWarehouses(current, incoming)
        val knownCountries = incoming.warehouses.mapNotNull { w -> known[w.id]?.let { w.countryId to it.countryId } }.toMap()
        val countries = linkedMapOf<String, Country>()
        incoming.countries.forEach { country ->
            val sameId = current.countries.find { it.id == country.id }
            val knownCountry = knownCountries[country.id]?.let { id -> current.countries.find { it.id == id } }
            val sameName = current.countries.find { key(it.name) == key(country.name) }
            countries[country.id] = when {
                sameId != null -> { require(sameId == country) { tr(TextKey.ID_EXISTS_WITH_DIFFERENT_CONTENT_NOTHING_WAS_IMPORTED, tr(TextKey.COUNTRY), country.id) }; sameId }
                knownCountry != null -> knownCountry
                sameName != null -> {
                    require(country.id in choices.countries) { tr(TextKey.BACKUP_CHOOSE_COUNTRY, country.name) }
                    sameName
                }
                else -> country
            }
        }
        val warehouses = linkedMapOf<String, Warehouse>()
        val quantityWarehouses = mutableSetOf<String>()
        incoming.warehouses.forEach { warehouse ->
            val countryId = countries[warehouse.countryId]?.id ?: warehouse.countryId
            val mapped = warehouse.copy(countryId = countryId)
            val sameId = current.warehouses.find { it.id == warehouse.id }
            val sameName = current.warehouses.find { it.countryId == countryId && key(it.name) == key(warehouse.name) }
            val resolved = when {
                known[warehouse.id] != null -> known.getValue(warehouse.id)
                sameId != null -> { require(sameId == mapped) { tr(TextKey.ID_EXISTS_WITH_DIFFERENT_CONTENT_NOTHING_WAS_IMPORTED, tr(TextKey.WAREHOUSE), warehouse.id) }; sameId }
                sameName != null -> {
                    val choice = choices.warehouses[warehouse.id] ?: error(tr(TextKey.BACKUP_CHOOSE_WAREHOUSE, warehouse.name))
                    when (choice.action) {
                        BackupWarehouseAction.MERGE -> sameName
                        BackupWarehouseAction.RENAME -> {
                            val name = choice.name.trim()
                            require(name.isNotEmpty() && name.length <= 100 && name.none(Char::isISOControl)) { tr(TextKey.BACKUP_RENAME_REQUIRED) }
                            require((current.warehouses + warehouses.values).none { it.countryId == countryId && key(it.name) == key(name) }) { tr(TextKey.A_WAREHOUSE_WITH_THIS_NAME_ALREADY_EXISTS_IN_THIS) }
                            mapped.copy(name = name)
                        }
                    }
                }
                else -> mapped
            }
            warehouses[warehouse.id] = resolved
            if (resolved.id in current.warehouses.map { it.id } && choices.mode == DuplicateMode.ADD_QUANTITY) quantityWarehouses += resolved.id
        }
        val resolvedNames = mutableSetOf<Pair<String, String>>()
        (current.warehouses + warehouses.values).distinctBy { it.id }.forEach { warehouse ->
            require(resolvedNames.add(warehouse.countryId to key(warehouse.name))) { tr(TextKey.A_WAREHOUSE_WITH_THIS_NAME_ALREADY_EXISTS_IN_THIS) }
        }
        val skipped = mutableSetOf<String>()
        val existingRecords = current.records.associateBy { it.id }
        incoming.records.forEach { record ->
            origins[record.id]?.let { origin ->
                if (origin.source == record) skipped += record.id
                else require(existingRecords[record.id] == record) { tr(TextKey.ID_EXISTS_WITH_DIFFERENT_CONTENT_NOTHING_WAS_IMPORTED, tr(TextKey.INVENTORY_RECORDS), record.id) }
            }
        }
        val warehouseIds = warehouses.mapValues { it.value.id }
        fun rewrite(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val old = value.optString("warehouseId")
                    val replacement = warehouseIds[old]
                    if (replacement != null && replacement != old) {
                        if (!value.has("originalWarehouseId")) value.put("originalWarehouseId", old)
                        value.put("warehouseId", replacement)
                    }
                    value.keys().asSequence().toList().forEach { rewrite(value.opt(it)) }
                }
                is JSONArray -> for (i in 0 until value.length()) rewrite(value.opt(i))
            }
        }
        fun audit(text: String): String {
            if (warehouseIds.all { it.key == it.value }) return text
            val json = JSONObject(text)
            rewrite(json)
            return json.toString()
        }
        val sources = incoming.records.filter { record ->
            val warehouse = incoming.warehouses.find { it.id == record.warehouseId }
            record.id !in skipped && warehouse != null && (warehouses[warehouse.id] != warehouse || warehouse.countryId != countries[warehouse.countryId]?.id || warehouses[warehouse.id]?.id in quantityWarehouses)
        }.associateBy { it.id }
        return BackupMergePlan(incoming.copy(
            countries = countries.values.distinctBy { it.id },
            warehouses = warehouses.values.distinctBy { it.id },
            records = incoming.records.filter { it.id !in skipped }.map { it.copy(warehouseId = warehouseIds[it.warehouseId] ?: it.warehouseId) },
            changes = incoming.changes.filter { it.recordId !in skipped }.map { it.copy(before = audit(it.before), after = audit(it.after)) },
        ), quantityWarehouses, sources, skipped.size)
    }
}
