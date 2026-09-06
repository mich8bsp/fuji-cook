package io.github.mich8bsp.fujicook.data

import androidx.room.withTransaction
import io.github.mich8bsp.fujicook.model.Tag
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TagRepository(private val db: RecipeDatabase) {
    private val dao = db.tagDao()
    val tags: Flow<List<Tag>> = dao.observeAll().map { rows -> rows.map { Tag(it.id, it.name, it.groupName, it.color, it.sortOrder) } }

    suspend fun add(name: String, group: String?, color: Long): String = db.withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is required" }
        require(dao.byName(clean) == null) { "A tag named \"$clean\" already exists" }
        val id = UUID.randomUUID().toString()
        dao.insert(TagEntity(id, clean, group?.trim()?.ifEmpty { null }, color, dao.maxSortOrder() + 1))
        id
    }

    suspend fun update(id: String, name: String, group: String?, color: Long) = db.withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is required" }
        val conflict = dao.byName(clean)
        require(conflict == null || conflict.id == id) { "A tag named \"$clean\" already exists" }
        val existing = requireNotNull(dao.get(id)) { "Tag not found" }
        dao.update(existing.copy(name = clean, groupName = group?.trim()?.ifEmpty { null }, color = color))
    }

    suspend fun delete(id: String) = dao.delete(id)
}
