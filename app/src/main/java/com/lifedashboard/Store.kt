package com.lifedashboard

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "raw_event", indices = [Index(value = ["sourceType", "sourceKey", "revision"], unique = true)])
data class RawEvent(@PrimaryKey val id: String, val sourceType: String, val sourceKey: String,
    val revision: Int, val occurredAt: Long, val receivedAt: Long, val rawJson: String, val hash: String)

@Entity(tableName = "life_event", indices = [Index("occurredAt"), Index("type"), Index("category"), Index(value = ["sourceType", "sourceId"], unique = true)])
data class LifeEvent(@PrimaryKey val id: String, val type: String, val category: String?,
    val occurredAt: Long, val endedAt: Long?, val title: String, val summary: String?,
    val sourceType: String, val sourceId: String?, val rawEventId: String?, val dataJson: String,
    val importance: Double = 0.5, val createdAt: Long, val updatedAt: Long, val status: String = "ACTIVE", val calendarDate: String? = null)

@Entity(tableName = "event_tag", primaryKeys = ["eventId", "tag"], foreignKeys = [ForeignKey(entity = LifeEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)])
data class EventTag(val eventId: String, val tag: String)
@Entity(tableName = "event_entity", indices = [Index("eventId")], foreignKeys = [ForeignKey(entity = LifeEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)])
data class EventEntity(@PrimaryKey val id: String, val eventId: String, val entityType: String, val name: String, val normalizedName: String)
@Entity(tableName = "daily_summary")
data class DailySummary(@PrimaryKey val date: String, val stepCount: Long?, val sleepMinutes: Long?,
    val exerciseMinutes: Long?, val paymentCount: Int, val paymentAmount: Long, val calendarCount: Int,
    val deliveryCount: Int, val reservationCount: Int, val summaryJson: String, val updatedAt: Long)

@Dao
interface LifeDao {
    @Query("SELECT * FROM raw_event WHERE sourceType=:source AND sourceKey=:key ORDER BY revision DESC LIMIT 1")
    suspend fun latestRaw(source: String, key: String): RawEvent?
    @Insert suspend fun insertRaw(raw: RawEvent)
    @Query("SELECT * FROM raw_event WHERE id=:id") suspend fun raw(id: String): RawEvent?
    @Query("SELECT r.* FROM raw_event r WHERE NOT EXISTS (SELECT 1 FROM raw_event n WHERE n.sourceType=r.sourceType AND n.sourceKey=r.sourceKey AND n.revision>r.revision) ORDER BY r.receivedAt,r.id")
    suspend fun latestRaws(): List<RawEvent>
    @Query("SELECT * FROM life_event WHERE sourceType=:source AND sourceId=:key") suspend fun event(source: String, key: String): LifeEvent?
    @Query("SELECT * FROM life_event WHERE id=:id") suspend fun eventById(id: String): LifeEvent?
    @Upsert suspend fun put(event: LifeEvent)
    @Query("SELECT * FROM life_event WHERE status != 'DELETED' AND ((calendarDate IS NOT NULL AND calendarDate=:date) OR (calendarDate IS NULL AND ((type='SLEEP' AND endedAt>=:start AND endedAt<:end) OR (type='EXERCISE' AND occurredAt<:end AND endedAt>:start) OR (type NOT IN ('SLEEP','EXERCISE') AND occurredAt>=:start AND occurredAt<:end)))) AND (:type='' OR type=:type OR (:type='HEALTH' AND category='HEALTH')) AND (:inbox=0 OR sourceType='NOTIFICATION') ORDER BY CASE WHEN type='SLEEP' THEN endedAt ELSE occurredAt END DESC,id DESC LIMIT :limit")
    fun timeline(start: Long, end: Long, date: String, type: String, inbox: Boolean, limit: Int): Flow<List<LifeEvent>>
    @Query("SELECT * FROM life_event WHERE type NOT IN ('BRIEFING')") suspend fun allEvents(): List<LifeEvent>
    @Query("SELECT * FROM life_event WHERE sourceType=:source AND occurredAt>=:start AND occurredAt<:end AND status='ACTIVE'")
    suspend fun sourceWindow(source: String, start: Long, end: Long): List<LifeEvent>
    @Query("SELECT * FROM life_event WHERE type='BRIEFING' ORDER BY occurredAt DESC LIMIT 1") fun briefing(): Flow<LifeEvent?>
    @Query("SELECT * FROM daily_summary ORDER BY date DESC") fun observeSummaryRows(): Flow<List<DailySummary>>
    @Query("SELECT * FROM daily_summary WHERE updatedAt>0 ORDER BY date DESC") fun summaries(): Flow<List<DailySummary>>
    @Query("SELECT * FROM daily_summary WHERE updatedAt>0 ORDER BY date") suspend fun summariesOnce(): List<DailySummary>
    @Query("SELECT * FROM life_event WHERE status='ACTIVE' AND type!='BRIEFING' AND ((type='EXERCISE' AND occurredAt<:end AND endedAt>:start) OR json_extract(dataJson,'$.date')=:date OR (json_extract(dataJson,'$.date') IS NULL AND ((type='SLEEP' AND endedAt>=:start AND endedAt<:end) OR (type!='SLEEP' AND occurredAt>=:start AND occurredAt<:end))))")
    suspend fun summaryEvents(start: Long, end: Long, date: String): List<LifeEvent>
    @Query("SELECT * FROM life_event WHERE type='CALENDAR' AND status='ACTIVE' AND ((calendarDate IS NOT NULL AND calendarDate=:date) OR (calendarDate IS NULL AND occurredAt>=:start AND occurredAt<:end))")
    suspend fun calendarForDay(start: Long, end: Long, date: String): List<LifeEvent>
    @Query("SELECT * FROM life_event WHERE type='CALENDAR' AND status='ACTIVE' AND ((calendarDate IS NOT NULL AND calendarDate=:date) OR (calendarDate IS NULL AND occurredAt>=:start AND occurredAt<:end)) ORDER BY CASE WHEN calendarDate IS NOT NULL THEN 0 ELSE 1 END, occurredAt,id")
    fun observeCalendarDay(start: Long, end: Long, date: String): Flow<List<LifeEvent>>
    @Query("SELECT * FROM daily_summary ORDER BY date") suspend fun summaryRows(): List<DailySummary>
    @Upsert suspend fun putSummary(summary: DailySummary)
    @Query("DELETE FROM daily_summary") suspend fun clearSummaries()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun tags(tags: List<EventTag>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun entities(entities: List<EventEntity>)
    @Query("DELETE FROM event_tag WHERE eventId=:id") suspend fun clearTags(id: String)
    @Query("DELETE FROM event_entity WHERE eventId=:id") suspend fun clearEntities(id: String)
    @Query("SELECT * FROM event_tag WHERE eventId=:id") suspend fun tagsFor(id: String): List<EventTag>
    @Query("SELECT * FROM event_entity WHERE eventId=:id") suspend fun entitiesFor(id: String): List<EventEntity>
    @Query("SELECT COUNT(*) FROM raw_event") suspend fun rawCount(): Int
    @Query("DELETE FROM life_event") suspend fun clearEvents()
    @Query("DELETE FROM raw_event") suspend fun clearRaws()
}

@Database(entities = [RawEvent::class, LifeEvent::class, EventTag::class, EventEntity::class, DailySummary::class], version = 1, exportSchema = true)
abstract class LifeDatabase : RoomDatabase() {
    abstract fun dao(): LifeDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, LifeDatabase::class.java, "life.db").build()
    }
}
