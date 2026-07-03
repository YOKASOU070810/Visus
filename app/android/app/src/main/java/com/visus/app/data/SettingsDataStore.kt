package com.visus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "visus_settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        val SERVER_IP = stringPreferencesKey("server_ip")
        val SERVER_PORT = stringPreferencesKey("server_port")
        val AMAP_KEY = stringPreferencesKey("amap_key")
        val ARK_KEY = stringPreferencesKey("ark_key")
        val DEFAULT_IP = "10.0.2.2"
        val DEFAULT_PORT = "8081"
        val DEFAULT_AMAP_KEY = ""
        val DEFAULT_ARK_KEY = "ark-1b4752cf-a621-4c2b-b4b1-7de39d2108ec-aa48e"
    }

    val serverIp: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_IP] ?: DEFAULT_IP
    }

    val serverPort: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_PORT] ?: DEFAULT_PORT
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        val ip = preferences[SERVER_IP] ?: DEFAULT_IP
        val port = preferences[SERVER_PORT] ?: DEFAULT_PORT
        "ws://$ip:$port"
    }

    suspend fun saveServerIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_IP] = ip
        }
    }

    suspend fun saveServerPort(port: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_PORT] = port
        }
    }

    val amapKey: Flow<String> = context.dataStore.data.map { it[AMAP_KEY] ?: DEFAULT_AMAP_KEY }
    val arkKey: Flow<String> = context.dataStore.data.map { it[ARK_KEY] ?: DEFAULT_ARK_KEY }

    suspend fun saveAmapKey(key: String) { context.dataStore.edit { it[AMAP_KEY] = key } }
    suspend fun saveArkKey(key: String) { context.dataStore.edit { it[ARK_KEY] = key } }
}
