package io.github.mich8bsp.fujicook.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Database(entities = [RecipeEntity::class, RevisionEntity::class], version = 2, exportSchema = true)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
}

// Drops the retired "category" field from recipes saved by app versions that predate "tags".
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id, settingsJson FROM RevisionEntity").use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val jsonIndex = cursor.getColumnIndexOrThrow("settingsJson")
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val original = cursor.getString(jsonIndex)
                val cleaned = RecipeJson.stripLegacyCategoryField(original)
                if (cleaned != original) db.execSQL("UPDATE RevisionEntity SET settingsJson = ? WHERE id = ?", arrayOf(cleaned, id))
            }
        }
    }
}
