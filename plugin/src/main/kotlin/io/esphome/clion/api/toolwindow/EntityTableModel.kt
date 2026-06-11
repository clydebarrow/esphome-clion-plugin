package io.esphome.clion.api.toolwindow

import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiState
import javax.swing.table.AbstractTableModel

/**
 * Backs the entity table: **Type · Name · State**, one row per entity, sorted by
 * type then name, with live state updates by key. All mutators run on the EDT.
 */
class EntityTableModel : AbstractTableModel() {

    private class Row(val entity: ApiEntity, var state: String = "—")

    private val rows = ArrayList<Row>()
    private val byKey = HashMap<Long, Row>()

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = 3
    override fun getColumnName(column: Int): String = COLUMNS[column]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.entity.type
            1 -> row.entity.name
            else -> formatState(row)
        }
    }

    /** Add a newly-listed entity (or refresh it), keeping the rows sorted. */
    fun putEntity(entity: ApiEntity) {
        val existing = byKey[entity.key]
        if (existing != null) {
            val idx = rows.indexOf(existing)
            rows[idx] = Row(entity, existing.state).also { byKey[entity.key] = it }
            fireTableRowsUpdated(idx, idx)
            return
        }
        val row = Row(entity)
        byKey[entity.key] = row
        val insert = rows.indexOfFirst { ORDER.compare(row, it) < 0 }.let { if (it < 0) rows.size else it }
        rows.add(insert, row)
        fireTableRowsInserted(insert, insert)
    }

    /** Apply a live state update; ignored if the entity hasn't been listed yet. */
    fun applyState(state: ApiState) {
        val row = byKey[state.key] ?: return
        row.state = state.display
        val idx = rows.indexOf(row)
        if (idx >= 0) fireTableRowsUpdated(idx, idx)
    }

    fun clear() {
        val n = rows.size
        rows.clear()
        byKey.clear()
        if (n > 0) fireTableRowsDeleted(0, n - 1)
    }

    private fun formatState(row: Row): String {
        val unit = row.entity.unit
        return if (unit.isNotEmpty() && row.state != "—") "${row.state} $unit" else row.state
    }

    private companion object {
        val COLUMNS = arrayOf("Type", "Name", "State")
        val ORDER = compareBy<Row>({ it.entity.type }, { it.entity.name.lowercase() })
    }
}
