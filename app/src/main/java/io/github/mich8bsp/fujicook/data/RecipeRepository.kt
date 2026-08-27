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

    suspend fun setDescription(id: String, description: String) = db.withTransaction {
        val row = requireNotNull(dao.get(id))
        dao.updateRecipe(row.recipe.copy(description = description, updatedAt = System.currentTimeMillis()))
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
        return Recipe(row.recipe.id, row.recipe.name, row.recipe.archived, row.recipe.createdAt, row.recipe.updatedAt, revision(latest), row.recipe.description)
    }

    suspend fun matchableRevisions(includeArchived: Boolean = false): List<Pair<Recipe, RecipeRevision>> = dao.getAll().filter { includeArchived || !it.recipe.archived }.mapNotNull { row ->
        val current = domain(row) ?: return@mapNotNull null
        current to current.current
    }

}
