package com.portnoir.data

import android.content.Context
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

    @Query("UPDATE chat_messages SET delivered = 1 WHERE macId = :macId AND id = :messageId")
    suspend fun acknowledge(macId: String, messageId: String)

    @Query("UPDATE chat_messages SET transferState = :state, localPath = :path WHERE id = :messageId")
    suspend fun setTransfer(messageId: String, state: String, path: String)

    @Query("SELECT * FROM chat_messages WHERE macId = :macId AND direction = 'outgoing' AND type = 'chat.file' AND transferState != 'complete'")
    suspend fun pendingUploads(macId: String): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE macId = :macId AND direction = 'incoming' AND type = 'chat.file' AND transferState != 'complete'")
    suspend fun pendingDownloads(macId: String): List<ChatMessage>
}

@Database(entities = [ChatMessage::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messages(): ChatMessageDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "portnoir-chat.db",
                ).build().also { instance = it }
            }
    }
}
