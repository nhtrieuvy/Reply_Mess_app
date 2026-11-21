package com.drivevoice.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.drivevoice.app.data.model.AppConfig
import com.drivevoice.app.data.model.MessageApp
import com.drivevoice.app.data.model.VipContact
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.driveSettings by preferencesDataStore("drive_settings")

@Singleton
internal class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.driveSettings

    val config = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences().toMutablePreferences().apply {
            this[SOURCES] = emptySet()
        }) else throw error
    }.map { preferences ->
        AppConfig(
            sources = preferences[SOURCES]?.mapNotNull(::messageApp)?.toSet() ?: MessageApp.entries.toSet(),
            headsetAddress = preferences[HEADSET],
            autoStart = preferences[AUTO_START] == true,
            handsFree = preferences[HANDS_FREE] == true,
            autoSend = preferences[AUTO_SEND] == true,
            vipOnly = preferences[VIP_ONLY] == true,
            vips = preferences[VIPS].orEmpty().mapNotNull(::decodeVip).toSet(),
        )
    }

    suspend fun setSource(app: MessageApp, enabled: Boolean) {
        store.edit { prefs ->
            val sources = prefs[SOURCES] ?: MessageApp.entries.map { it.name }.toSet()
            prefs[SOURCES] = if (enabled) sources + app.name else sources - app.name
        }
    }

    suspend fun selectHeadset(address: String?) {
        store.edit {
            if (address == null) {
                it.remove(HEADSET)
                it[AUTO_START] = false
            } else it[HEADSET] = address
        }
    }

    suspend fun setAutoStart(enabled: Boolean) {
        store.edit { it[AUTO_START] = enabled && it[HEADSET] != null }
    }

    suspend fun setHandsFree(enabled: Boolean) {
        store.edit {
            it[HANDS_FREE] = enabled
            if (!enabled) it[AUTO_SEND] = false
        }
    }

    suspend fun setAutoSend(enabled: Boolean) {
        store.edit { it[AUTO_SEND] = enabled && it[HANDS_FREE] == true }
    }

    suspend fun setVipOnly(enabled: Boolean) {
        store.edit { it[VIP_ONLY] = enabled }
    }

    suspend fun addVip(app: MessageApp, rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 96) return false
        val candidate = VipContact(app, name)
        var added = false
        store.edit {
            val entries = it[VIPS].orEmpty()
            if (entries.size < 50 && entries.mapNotNull(::decodeVip).none { vip ->
                    vip.app == app && vip.normalizedName == candidate.normalizedName
                }
            ) {
                it[VIPS] = entries + encodeVip(candidate)
                added = true
            }
        }
        return added
    }

    suspend fun removeVip(vip: VipContact) {
        store.edit { it[VIPS] = it[VIPS].orEmpty() - encodeVip(vip) }
    }

    private fun decodeVip(value: String): VipContact? {
        val app = messageApp(value.substringBefore(':')) ?: return null
        val name = value.substringAfter(':', "").takeIf { it.isNotBlank() } ?: return null
        return VipContact(app, name)
    }

    private fun encodeVip(vip: VipContact): String = "${vip.app.name}:${vip.name}"

    private fun messageApp(value: String): MessageApp? = MessageApp.entries.firstOrNull { it.name == value }

    private companion object {
        val SOURCES = stringSetPreferencesKey("sources")
        val HEADSET = stringPreferencesKey("headset")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val HANDS_FREE = booleanPreferencesKey("hands_free")
        val AUTO_SEND = booleanPreferencesKey("auto_send")
        val VIP_ONLY = booleanPreferencesKey("vip_only")
        val VIPS = stringSetPreferencesKey("vip_names")
    }
}
