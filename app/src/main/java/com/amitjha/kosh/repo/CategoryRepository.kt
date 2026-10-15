package com.amitjha.kosh.repo

import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.model.TxnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepository(private val db: KoshDatabase) {

    fun observeAll(): Flow<List<CategoryEntity>> = db.categories().observeAll()

    fun observeByType(type: TxnType): Flow<List<CategoryEntity>> = observeAll().map { list -> list.filter { it.type == type } }

    fun observeByKey(): Flow<Map<String, CategoryEntity>> = observeAll().map { list -> list.associateBy { it.key } }

    suspend fun all(): List<CategoryEntity> = db.categories().all()

    suspend fun byKey(key: String): CategoryEntity? = all().firstOrNull { it.key == key }

    /** Adds a custom category; the key is derived from the name. */
    suspend fun addCustom(name: String, type: TxnType, icon: String, colorArgb: Long): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "custom" }
        val existing = all().map { it.key }.toSet()
        var key = "custom_$base"
        var n = 2
        while (key in existing) key = "custom_${base}_${n++}"
        db.categories().upsert(CategoryEntity(key = key, name = name, type = type, icon = icon, colorArgb = colorArgb, isDefault = false))
        return key
    }
}
