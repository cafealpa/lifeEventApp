package com.lifedashboard

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HealthNormalizerTest {
    private val start = Instant.parse("2026-10-06T14:00:00Z")
    private val end = start.plusSeconds(8 * 3600)
    @Test fun sleepRecordPreservesStagesMetadataAndOffsets() {
        val record = SleepSessionRecord(start,ZoneOffset.ofHours(9),end,ZoneOffset.ofHours(9),Metadata.manualEntry(),
            title="테스트 수면",stages=listOf(SleepSessionRecord.Stage(start,start.plusSeconds(3600),SleepSessionRecord.STAGE_TYPE_AWAKE), SleepSessionRecord.Stage(start.plusSeconds(3600),end,SleepSessionRecord.STAGE_TYPE_SLEEPING)))
        val (occurredAt,json) = HealthNormalizer.payload(record)
        assertEquals(start.toEpochMilli(),occurredAt)
        assertEquals(420L,json.getLong("durationMinutes"))
        assertEquals(2,json.getJSONObject("raw").getJSONArray("stages").length())
        assertEquals("+09:00",json.getJSONObject("raw").getString("startZoneOffset"))
        assertTrue(json.getJSONObject("raw").has("metadata"))
    }
    @Test fun stepRecordPreservesCountAndMetadata() {
        val (_,json) = HealthNormalizer.payload(StepsRecord(start,null,start.plusSeconds(60),null,123,Metadata.manualEntry()))
        assertEquals("STEP",json.getString("type"))
        assertEquals(123L,json.getJSONObject("raw").getLong("count"))
    }
    @Test fun exerciseRecordPreservesTypeAndTimes() {
        val (_,json) = HealthNormalizer.payload(ExerciseSessionRecord(start,null,start.plusSeconds(1800),null,Metadata.manualEntry(),ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals("EXERCISE",json.getString("type"))
        assertEquals(30L,json.getLong("durationMinutes"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING,json.getJSONObject("raw").getInt("exerciseType"))
    }
}
