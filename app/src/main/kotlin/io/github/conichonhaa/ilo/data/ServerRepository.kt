package io.github.conichonhaa.ilo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.conichonhaa.ilo.core.input.KeyboardLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val layout: KeyboardLayout = KeyboardLayout.US,
    /** SHA-256 fingerprint of the iLO certificate approved by the user. */
    val certFingerprint: String? = null,
    val lastConnected: Long? = null,
) {
    val displayName: String get() = name.ifBlank { address }
}

private val Context.serverStore by preferencesDataStore(name = "servers")

class ServerRepository(context: Context) {
    private val store = context.applicationContext.serverStore
    private val key = stringPreferencesKey("servers_v1")

    val servers: Flow<List<ServerConfig>> = store.data
        .map { prefs -> decode(prefs[key]) }
        .flowOn(Dispatchers.Default)

    suspend fun get(id: String): ServerConfig? = servers.first().firstOrNull { it.id == id }

    suspend fun upsert(server: ServerConfig) = update { list ->
        val i = list.indexOfFirst { it.id == server.id }
        if (i >= 0) list.toMutableList().apply { set(i, server) } else list + server
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun setFingerprint(id: String, fingerprint: String?) = update { list ->
        list.map { if (it.id == id) it.copy(certFingerprint = fingerprint) else it }
    }

    suspend fun markConnected(id: String) = update { list ->
        list.map { if (it.id == id) it.copy(lastConnected = System.currentTimeMillis()) else it }
    }

    private suspend fun update(transform: (List<ServerConfig>) -> List<ServerConfig>) {
        withContext(Dispatchers.Default) {
            store.edit { prefs -> prefs[key] = encode(transform(decode(prefs[key]))) }
        }
    }

    private fun decode(raw: String?): List<ServerConfig> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ServerConfig(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    address = o.optString("address"),
                    username = o.optString("username"),
                    password = SecretStore.decrypt(o.optString("password")),
                    layout = KeyboardLayout.fromId(o.optString("layout")),
                    certFingerprint = o.optString("fingerprint").ifEmpty { null },
                    lastConnected = o.optLong("lastConnected", 0L).takeIf { it > 0 },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(list: List<ServerConfig>): String {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("address", s.address)
                    .put("username", s.username)
                    .put("password", SecretStore.encrypt(s.password))
                    .put("layout", s.layout.name)
                    .put("fingerprint", s.certFingerprint ?: "")
                    .put("lastConnected", s.lastConnected ?: 0L),
            )
        }
        return array.toString()
    }
}
