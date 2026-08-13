package io.github.mich8bsp.fujicook.data

import androidx.room.withTransaction
import io.github.mich8bsp.fujicook.model.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecipeRepository(private val db: RecipeDatabase) {
    private val dao = db.recipeDao()
    val recipes: Flow<List<Recipe>> = dao.observeAll().map { rows -> rows.mapNotNull(::domain) }

    suspend fun create(name: String, settings: RecipeSettings): String = db.withTransaction {
        settings.validate()
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is required" }
        require(dao.byName(clean.lowercase()) == null) { "Recipe name already exists" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertRecipe(RecipeEntity(id, clean, clean.lowercase(), false, now, now))
        dao.insertRevision(RevisionEntity(UUID.randomUUID().toString(), id, 1, RecipeJson.settings(settings).toString(), now))
        id
    }

    suspend fun revise(id: String, settings: RecipeSettings) = db.withTransaction {
        settings.validate()
        val row = requireNotNull(dao.get(id))
        val current = requireNotNull(row.revisions.maxByOrNull { it.number })
        val now = System.currentTimeMillis()
        dao.updateRevision(current.copy(settingsJson = RecipeJson.settings(settings).toString(), createdAt = now))
        dao.updateRecipe(row.recipe.copy(updatedAt = now))
    }

    suspend fun rename(id: String, name: String) = db.withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty())
        val row = requireNotNull(dao.get(id))
        val conflict = dao.byName(clean.lowercase())
        require(conflict == null || conflict.id == id) { "Recipe name already exists" }
        dao.updateRecipe(row.recipe.copy(name = clean, normalizedName = clean.lowercase(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun archive(id: String, archived: Boolean) {
        val row = requireNotNull(dao.get(id))
        dao.updateRecipe(row.recipe.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) {
        val row = requireNotNull(dao.get(id))
        require(row.recipe.archived) { "Only archived recipes can be permanently deleted" }
        dao.delete(id)
    }

    private fun domain(row: RecipeWithRevisions): Recipe? {
        val latest = row.revisions.maxByOrNull { it.number } ?: return null
        fun revision(e: RevisionEntity) = RecipeRevision(e.id, e.recipeId, e.number, RecipeJson.parseSettings(org.json.JSONObject(e.settingsJson)), e.createdAt)
        return Recipe(row.recipe.id, row.recipe.name, row.recipe.archived, row.recipe.createdAt, row.recipe.updatedAt, revision(latest))
    }

    suspend fun matchableRevisions(): List<Pair<Recipe, RecipeRevision>> = dao.getAll().filter { !it.recipe.archived }.mapNotNull { row ->
        val current = domain(row) ?: return@mapNotNull null
        current to current.current
    }

    suspend fun exportCurrent(ids: Set<String>? = null): String {
        val entries = dao.getAll().mapNotNull(::domain).filter { ids == null || it.id in ids }.map { ExportRecipe(it.name, it.current.settings) }
        return RecipeJson.envelope(entries)
    }

    fun decodeImport(text: String): RecipeEnvelope = RecipeJson.parseEnvelope(text)

    suspend fun importCurrent(envelope: RecipeEnvelope, conflict: ImportConflict): Int {
        var imported = 0
        envelope.recipes.forEach { incoming ->
            val existing = dao.byName(incoming.name.trim().lowercase())
            when {
                existing == null -> {
                    create(incoming.name, incoming.settings)
                    imported++
                }
                conflict == ImportConflict.OVERWRITE -> {
                    revise(existing.id, incoming.settings)
                    imported++
                }
                conflict == ImportConflict.RENAME -> {
                    var suffix = 2
                    var name = incoming.name + " " + suffix
                    while (dao.byName(name.lowercase()) != null) {
                        suffix++
                        name = incoming.name + " " + suffix
                    }
                    create(name, incoming.settings)
                    imported++
                }
                else -> Unit
            }
        }
        return imported
    }
}

enum class ImportConflict { OVERWRITE, RENAME, SKIP }

data class RecipeEnvelope(val schemaVersion: Int = 1, val cameraModel: String = "Fujifilm X-T5", val recipes: List<ExportRecipe>)
data class ExportRecipe(val name: String, val settings: RecipeSettings)
