package com.portnoir.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["macId", "timestamp"])],
)
data class ChatMessage(
    @PrimaryKey val id: String,
    val macId: String,
    val direction: String,
    val type: String,
    val timestamp: Long,
    val body: String = "",
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val blob: String = "",
    val delivered: Boolean = false,
    val transferState: String = "",
    val localPath: String = "",
    @ColumnInfo(defaultValue = "''") val locationUri: String = "",
)

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE macId = :macId ORDER BY timestamp, rowid")
    fun observe(macId: String): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM chat_messages WHERE id = :messageId")
    suspend fun get(messageId: String): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE macId = :macId AND direction = 'outgoing' AND delivered = 0 ORDER BY timestamp")
    suspend fun pending(macId: String): List<ChatMessage>

    @Query("UPDATE chat_messages SET delivered = 1, transferState = CASE WHEN type = 'chat.text' THEN '' ELSE transferState END WHERE macId = :macId AND id = :messageId")
    suspend fun acknowledgeMessage(macId: String, messageId: String)

    @Query("UPDATE chat_messages SET transferState = 'sending' WHERE macId = :macId AND id = :messageId AND direction = 'outgoing' AND type = 'chat.text' AND delivered = 0 AND transferState = 'pending'")
    suspend fun markTextSending(macId: String, messageId: String): Int

    @Query("UPDATE chat_messages SET transferState = 'pending' WHERE macId = :macId AND id = :messageId AND direction = 'outgoing' AND type = 'chat.text' AND delivered = 0 AND transferState = 'sending'")
    suspend fun markTextPending(macId: String, messageId: String)

    @Query("UPDATE chat_messages SET transferState = 'pending' WHERE macId = :macId AND direction = 'outgoing' AND type = 'chat.text' AND delivered = 0 AND transferState IN ('sending', '')")
    suspend fun queueUndeliveredTexts(macId: String)

    @Query("DELETE FROM chat_messages WHERE macId = :macId AND id = :messageId AND direction = 'outgoing' AND type = 'chat.text' AND delivered = 0 AND transferState = 'pending'")
    suspend fun recallPendingText(macId: String, messageId: String): Int

    @Query("UPDATE chat_messages SET transferState = :state, localPath = :path WHERE id = :messageId")
    suspend fun setTransfer(messageId: String, state: String, path: String)

    @Query("UPDATE chat_messages SET locationUri = :uri WHERE id = :messageId")
    suspend fun setLocationUri(messageId: String, uri: String)

    @Query("SELECT * FROM chat_messages WHERE macId = :macId AND direction = 'outgoing' AND type = 'chat.file' AND transferState != 'complete'")
    suspend fun pendingUploads(macId: String): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE macId = :macId AND direction = 'incoming' AND type = 'chat.file' AND (transferState = 'queued' OR transferState LIKE 'downloading%' OR transferState LIKE 'failed:%')")
    suspend fun pendingDownloads(macId: String): List<ChatMessage>
}

@Database(entities = [ChatMessage::class], version = 2, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messages(): ChatMessageDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN locationUri TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "portnoir-chat.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
