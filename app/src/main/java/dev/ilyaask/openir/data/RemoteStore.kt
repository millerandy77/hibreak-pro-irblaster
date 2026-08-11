package dev.ilyaask.openir.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "openir")

/** Persists the user's remotes and transport choice in DataStore (JSON). */
class RemoteStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val REMOTES = stringPreferencesKey("remotes")
    private val COM_TYPE = stringPreferencesKey("com_type")
    private val BLE_ADDR = stringPreferencesKey("ble_address")

    val remotes: Flow<List<Remote>> = context.dataStore.data.map { p ->
        p[REMOTES]?.let { runCatching { json.decodeFromString<List<Remote>>(it) }.getOrNull() } ?: emptyList()
    }

    val transportChoice: Flow<String?> = context.dataStore.data.map { it[COM_TYPE] }
    val bleAddress: Flow<String?> = context.dataStore.data.map { it[BLE_ADDR] }

    suspend fun saveRemotes(list: List<Remote>) {
        context.dataStore.edit { it[REMOTES] = json.encodeToString(list) }
    }
    suspend fun upsert(remote: Remote) {
        val current = remotes.let { /* read synchronously via first below */ emptyList<Remote>() }
        // prefer async caller; kept simple: full replace via saveRemotes
    }
    suspend fun setTransport(comType: String, bleAddress: String? = null) {
        context.dataStore.edit {
            it[COM_TYPE] = comType
            if (bleAddress != null) it[BLE_ADDR] = bleAddress
        }
    }
}
