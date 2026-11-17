package com.example.myapplication

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {
    @Query("SELECT * FROM shifts ORDER BY id DESC")
    fun observeAll(): Flow<List<ShiftEntry>>

    @Query("SELECT * FROM shifts ORDER BY id DESC")
    suspend fun getAll(): List<ShiftEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shift: ShiftEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shifts: List<ShiftEntry>)

    @Query("DELETE FROM shifts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM shifts")
    suspend fun clear()
}

@Dao
interface IntercomDao {
    @Query("SELECT * FROM intercoms ORDER BY pinned DESC, id DESC")
    fun observeAll(): Flow<List<IntercomNote>>

    @Query("SELECT * FROM intercoms ORDER BY pinned DESC, id DESC")
    suspend fun getAll(): List<IntercomNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: IntercomNote)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<IntercomNote>)

    @Query("DELETE FROM intercoms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM intercoms")
    suspend fun clear()
}

@Database(entities = [ShiftEntry::class, IntercomNote::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shiftDao(): ShiftDao
    abstract fun intercomDao(): IntercomDao

    companion object {
        /** v2: в смену добавлена погода на момент сохранения (для персональной аналитики). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shifts ADD COLUMN weather TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3: чаевые, налог и сервис (агрегатор) у смены. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shifts ADD COLUMN tips INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shifts ADD COLUMN tax INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shifts ADD COLUMN service TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4: закрепление (избранное) у записей домофонов. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE intercoms ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "courier.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
