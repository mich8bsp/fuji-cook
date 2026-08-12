package io.github.mich8bsp.fujicook

import android.app.Application
import androidx.room.Room
import io.github.mich8bsp.fujicook.data.RecipeDatabase
import io.github.mich8bsp.fujicook.data.RecipeRepository

class FujiCookApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, RecipeDatabase::class.java, "fuji-cook.db").build() }
    val recipes by lazy { RecipeRepository(database) }
}
