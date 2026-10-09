package com.lifedashboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedReader
import java.security.MessageDigest

data class SpotRecord(val id: Long, val name: String, val time: Long, val date: String, val transition: String, val placeId: String?) {
    fun json() = JSONObject().put("id",id).put("locationName",name).put("timestamp",time).put("dateString",date).put("type",transition).put("placeId",placeId)
}
data class SpotSnapshot(val datasetId: String, val records: List<SpotRecord>) {
    companion object {
        fun read(reader: BufferedReader): SpotSnapshot {
            val header=JSONObject(requireNotNull(reader.readLine()) { "스냅샷 헤더가 없어요" })
            require(header.getInt("version")==1) { "SpotTrace 연동 버전을 확인해 주세요" }
            val dataset=header.getString("datasetId").also { java.util.UUID.fromString(it) }
            val snapshot=header.getString("snapshotId")
            val records=mutableListOf<SpotRecord>();val ids=mutableSetOf<Long>()
            while(true) {
                val row=JSONObject(requireNotNull(reader.readLine()) { "스냅샷이 중간에 끊겼어요" })
                if(row.optBoolean("complete")) {
                    require(row.getString("snapshotId")==snapshot && row.getInt("count")==records.size && reader.readLine()==null) { "스냅샷 완료 정보가 일치하지 않아요" }
                    break
                }
                require(records.size<100_000) { "방문 기록이 10만 건을 넘어 분할 연동이 필요해요" }
                val id=row.getLong("id"); val name=row.getString("locationName");val time=row.getLong("timestamp");val type=row.getString("type")
                require(id>0 && ids.add(id) && name.isNotBlank() && name.length<=1000 && time>=0 && type in setOf("ENTER","EXIT")) { "잘못되거나 중복된 방문 기록이에요" }
                records+=SpotRecord(id,name,time,row.getString("dateString"),type,if(row.isNull("placeId"))null else row.getString("placeId").takeIf { it.isNotBlank() })
            }
            return SpotSnapshot(dataset,records)
        }
    }
}
class SpotTraceCollector(private val context: Context, private val repository: LifeRepository) {
    private val prefs=context.getSharedPreferences("spottrace_sync",Context.MODE_PRIVATE)
    fun enabled()=prefs.getBoolean("enabled",false)
    fun enable(value: Boolean) { check(prefs.edit().putBoolean("enabled",value).commit()) }
    fun allowNewDataset() { check(prefs.edit().remove("datasetId").commit()) }
    fun clear() { prefs.edit().clear().commit() }
    fun settingsIntent()=Intent().setClassName(PACKAGE,"$PACKAGE.MainActivity").putExtra("showLifeDashboard",true)
    suspend fun collect() {
        val provider=context.packageManager.resolveContentProvider(AUTHORITY,0) ?: error("SpotTrace 미설치 또는 연동 지원 업데이트가 필요해요")
        check(provider.packageName==PACKAGE && trustedPackage(context,PACKAGE)) { "SpotTrace 서명 확인 실패" }
        val snapshot=try {
            context.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/snapshot"))?.bufferedReader(Charsets.UTF_8)?.use(SpotSnapshot::read)
                ?: error("SpotTrace 기록을 열지 못했어요")
        } catch (_: SecurityException) { throw SecurityException("SpotTrace에서 Life Dashboard 연동을 허용해 주세요") }
        currentCoroutineContext().ensureActive()
        val previous=prefs.getString("datasetId",null)
        check(previous==null || previous==snapshot.datasetId) { "SpotTrace 데이터가 교체됐어요. 새 데이터 연결을 눌러 확인해 주세요" }
        repository.importSpotSnapshot(snapshot)
        check(prefs.edit().putString("datasetId",snapshot.datasetId).commit())
    }
    companion object {
        const val PACKAGE="com.chochocho.spottrace"
        const val AUTHORITY="$PACKAGE.life"
        // Public certificate shared by the existing personal distributions; never a private key.
        const val CERTIFICATE="f1a140b112f9ed61f6a40c60050e7b4f2e2fda4b224d837747775c4b2a34e7a8"
        fun trustedPackage(context: Context, name: String): Boolean = try {
            val signers=context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty()
            signers.size==1 && MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray()).joinToString("") { "%02x".format(it) }==CERTIFICATE
        } catch (_: PackageManager.NameNotFoundException) { false }
    }
}

suspend fun LifeRepository.importSpotSnapshot(snapshot: SpotSnapshot) {
    val keys=snapshot.records.map { "${snapshot.datasetId}:trace:${it.id}" }.toSet()
    snapshot.records.forEach { record ->
        currentCoroutineContext().ensureActive()
        val data=JSONObject().put("schemaVersion",1).put("type","PLACE_VISIT").put("category","LIFE")
            .put("title",record.name+if(record.transition=="ENTER") " 도착" else " 출발").put("summary",if(record.transition=="ENTER") "장소 진입 기록" else "장소 이탈 기록")
            .put("datasetId",snapshot.datasetId).put("recordId",record.id).put("placeId",record.placeId)
            .put("placeName",record.name).put("transition",record.transition).put("sourceDate",record.date).put("raw",record.json())
        ingest("SPOTTRACE","${snapshot.datasetId}:trace:${record.id}",record.time,data)
    }
    // Only a completely validated snapshot reaches this point. Retain all raw revisions.
    dao.activeVisits().forEach { event ->
        currentCoroutineContext().ensureActive()
        if(event.sourceId !in keys) {
            val data=JSONObject(event.dataJson).put("type",event.type).put("category",event.category).put("title",event.title).put("summary",event.summary)
            val sameDataset=data.getString("datasetId")==snapshot.datasetId
            data.put("status",if(sameDataset) "DELETED" else "UNVERIFIED")
            ingest("SPOTTRACE",requireNotNull(event.sourceId),event.occurredAt,data)
        }
    }
}
