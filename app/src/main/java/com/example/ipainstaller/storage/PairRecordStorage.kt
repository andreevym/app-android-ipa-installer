package com.example.ipainstaller.storage

import android.content.Context
import android.util.Base64
import com.example.ipainstaller.model.PairRecord
import org.json.JSONObject
import java.io.File

/**
 * Persists PairRecords to disk so the user doesn't have to tap "Trust"
 * every time the app restarts.
 *
 * Records are stored as JSON files in app-private storage:
 *   context.filesDir/pair_records/{udid}.json
 */
class PairRecordStorage(context: Context) {

    private val storageDir = File(context.filesDir, "pair_records").apply { mkdirs() }

    fun save(udid: String, record: PairRecord) {
        val json = JSONObject().apply {
            put("hostId", record.hostId)
            put("systemBuid", record.systemBuid)
            put("hostCertificate", Base64.encodeToString(record.hostCertificate, Base64.NO_WRAP))
            put("hostPrivateKey", Base64.encodeToString(record.hostPrivateKey, Base64.NO_WRAP))
            put("deviceCertificate", Base64.encodeToString(record.deviceCertificate, Base64.NO_WRAP))
            put("rootCertificate", Base64.encodeToString(record.rootCertificate, Base64.NO_WRAP))
            put("rootPrivateKey", Base64.encodeToString(record.rootPrivateKey, Base64.NO_WRAP))
            put("escrowBag", record.escrowBag?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
            put("wifiMacAddress", record.wifiMacAddress ?: "")
        }
        fileFor(udid).writeText(json.toString(2))
    }

    fun load(udid: String): PairRecord? {
        val file = fileFor(udid)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            PairRecord(
                hostId = json.getString("hostId"),
                systemBuid = json.getString("systemBuid"),
                hostCertificate = Base64.decode(json.getString("hostCertificate"), Base64.NO_WRAP),
                hostPrivateKey = Base64.decode(json.getString("hostPrivateKey"), Base64.NO_WRAP),
                deviceCertificate = Base64.decode(json.getString("deviceCertificate"), Base64.NO_WRAP),
                rootCertificate = Base64.decode(json.getString("rootCertificate"), Base64.NO_WRAP),
                rootPrivateKey = Base64.decode(json.getString("rootPrivateKey"), Base64.NO_WRAP),
                escrowBag = json.optString("escrowBag", null)?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) },
                wifiMacAddress = json.optString("wifiMacAddress", null),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun delete(udid: String) {
        fileFor(udid).delete()
    }

    fun exists(udid: String): Boolean = fileFor(udid).exists()

    private fun fileFor(udid: String): File = File(storageDir, "$udid.json")
}
