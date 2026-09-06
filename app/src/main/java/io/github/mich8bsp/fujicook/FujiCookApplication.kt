package io.github.mich8bsp.fujicook

import android.app.Application
import androidx.room.Room
import io.github.mich8bsp.fujicook.data.MIGRATION_1_2
import io.github.mich8bsp.fujicook.data.MIGRATION_2_3
import io.github.mich8bsp.fujicook.data.MIGRATION_3_4
import io.github.mich8bsp.fujicook.data.MIGRATION_4_5
import io.github.mich8bsp.fujicook.data.RecipeDatabase
import io.github.mich8bsp.fujicook.data.RecipeRepository
import io.github.mich8bsp.fujicook.data.TAG_SEED_CALLBACK
import io.github.mich8bsp.fujicook.data.TagRepository

class FujiCookApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, RecipeDatabase::class.java, "fuji-cook.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .addCallback(TAG_SEED_CALLBACK)
            .build()
    }
    val recipes by lazy { RecipeRepository(database) }
    val tags by lazy { TagRepository(database) }
}
