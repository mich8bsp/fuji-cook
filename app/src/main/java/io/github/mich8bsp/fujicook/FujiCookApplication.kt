package io.github.mich8bsp.fujicook

import android.app.Application
import androidx.room.Room
import io.github.mich8bsp.fujicook.data.MIGRATION_1_2
import io.github.mich8bsp.fujicook.data.RecipeDatabase
import io.github.mich8bsp.fujicook.data.RecipeRepository

class FujiCookApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, RecipeDatabase::class.java, "fuji-cook.db").addMigrations(MIGRATION_1_2).build() }
    val recipes by lazy { RecipeRepository(database) }
}
