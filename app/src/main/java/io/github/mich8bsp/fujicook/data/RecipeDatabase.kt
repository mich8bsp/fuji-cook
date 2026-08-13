package io.github.mich8bsp.fujicook.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["normalizedName"], unique = true)])
data class RecipeEntity(@PrimaryKey val id: String, val name: String, val normalizedName: String, val archived: Boolean, val createdAt: Long, val updatedAt: Long)

@Entity(
    foreignKeys = [ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeId"), Index(value = ["recipeId", "number"], unique = true)],
)
data class RevisionEntity(@PrimaryKey val id: String, val recipeId: String, val number: Int, val settingsJson: String, val createdAt: Long)

data class RecipeWithRevisions(@Embedded val recipe: RecipeEntity, @Relation(parentColumn = "id", entityColumn = "recipeId") val revisions: List<RevisionEntity>)

@Dao
interface RecipeDao {
    @Transaction
    @Query("SELECT * FROM RecipeEntity ORDER BY normalizedName")
    fun observeAll(): Flow<List<RecipeWithRevisions>>

    @Transaction
    @Query("SELECT * FROM RecipeEntity ORDER BY normalizedName")
    suspend fun getAll(): List<RecipeWithRevisions>

    @Transaction
    @Query("SELECT * FROM RecipeEntity WHERE id=:id")
    suspend fun get(id: String): RecipeWithRevisions?

    @Query("SELECT * FROM RecipeEntity WHERE normalizedName=:name LIMIT 1")
    suspend fun byName(name: String): RecipeEntity?

    @Insert
    suspend fun insertRecipe(recipe: RecipeEntity)

    @Insert
    suspend fun insertRevision(revision: RevisionEntity)

    @Update
    suspend fun updateRevision(revision: RevisionEntity)

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Query("DELETE FROM RecipeEntity WHERE id=:id")
    suspend fun delete(id: String)
}

@Database(entities = [RecipeEntity::class, RevisionEntity::class], version = 1, exportSchema = true)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
}
