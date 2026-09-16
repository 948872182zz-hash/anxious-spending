package com.anxiousspending.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val amount: Double,
    val category: String,
    val note: String,
    val date: String,
    val currency: String,
    val exchangeRateToCny: Double,
    val cnyAmount: Double,
    val paymentSource: String,
    val entryType: String
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC, id DESC")
    suspend fun loadAll(): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ExpenseEntity>)

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(items: List<ExpenseEntity>) {
        deleteAll()
        if (items.isNotEmpty()) insertAll(items)
    }
}

@Database(
    entities = [ExpenseEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AnxiousDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var instance: AnxiousDatabase? = null

        fun get(context: Context): AnxiousDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnxiousDatabase::class.java,
                    "anxious_spending.db"
                ).build().also { instance = it }
            }
    }
}
