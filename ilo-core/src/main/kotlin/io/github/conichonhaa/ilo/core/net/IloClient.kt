package io.github.conichonhaa.ilo.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

class IloException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        AUTH_FAILED,
        UNKNOWN_HOST,
        UNREACHABLE,
        TIMEOUT,
        TLS,
        NOT_SUPPORTED,
        PROTOCOL,
        HTTP,
    }
}

/** Login session obtained from the iLO JSON API. */
data class IloSession(val sessionKey: String, val cookie: String?)

/** Remote console parameters. */
class RcInfo(
    /** true for the Redfish based handshake used by iLO 5 and later. */
    val version2: Boolean,
    /** Hex string as returned by the iLO. */
    val keyHex: String,
    val port: Int,
    /** iLO 4: the session token is XOR-obfuscated with [keyHex] during the handshake. */
    val obfuscateToken: Boolean,
) {
    val key: ByteArray = ByteArray(keyHex.length / 2) { i -> keyHex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

data class ServerOverview(
    val productName: String? = null,
    val serverName: String? = null,
    val serialNumber: String? = null,
    val iloModel: String? = null,
    val iloFirmware: String? = null,
    val biosVersion: String? = null,
    val health: String? = null,
    val powerState: String? = null,
)

/** Power actions accepted by the Redfish `ComputerSystem.Reset` action. */
enum class ResetType(val redfish: String) {
    ON("On"),
    PUSH_POWER_BUTTON("PushPowerButton"),
    FORCE_OFF("ForceOff"),
    FORCE_RESTART("ForceRestart"),
}

/**
 * HTTPS client for the management interfaces of an iLO 3/4/5/6.
 *
 * @param address host name or IP, optionally with ":port"
 * @param pinnedFingerprint SHA-256 fingerprint of the certificate approved by the user
 */
class IloClient(address: String, private val pinnedFingerprint: String?) {
    val authority: String = normalizeAddress(address)
    val host: String = URL("https://$authority").host.removePrefix("[").removeSuffix("]")
    val baseUrl: String = "https://$authority"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sslFactory by lazy { CertificatePinning.socketFactory(pinnedFingerprint) }

    fun login(username: String, password: String): IloSession {
        val body = buildJsonObject {
            put("method", "login")
            put("user_login", username)
            put("password", password)
        }.toString()
        val response = request("POST", "/json/login_session", body = body)
        if (response.code == 403 || response.code == 401) {
            throw IloException(IloException.Kind.AUTH_FAILED, "Invalid user name or password")
        }
        if (response.code == 404) {
            throw IloException(IloException.Kind.NOT_SUPPORTED, "This iLO does not provide the JSON login API (iLO 2 or older?)")
        }
        val key = parseObject(response.body)?.get("session_key")?.jsonPrimitive?.contentOrNull
        if (response.code !in 200..299 || key.isNullOrEmpty()) {
            throw IloException(IloException.Kind.AUTH_FAILED, "Login rejected (HTTP ${response.code})")
        }
        return IloSession(key, response.headers["set-cookie"]?.firstOrNull())
    }

    fun logout(session: IloSession) {
        runCatching {
            val body = buildJsonObject {
                put("method", "logout")
                put("session_key", session.sessionKey)
            }.toString()
            request("POST", "/json/login_session", body = body, headers = sessionCookie(session))
        }
    }

    fun rcInfo(session: IloSession): RcInfo {
        // iLO 5 and later publish the console parameters through Redfish.
        runCatching {
            val r = request("GET", "/redfish/v1/Managers/1/RcInfo/", headers = mapOf("X-Auth-Token" to session.sessionKey))
            if (r.code == 200) {
                val o = parseObject(r.body)
                val key = o?.string("MasterKey")
                val port = o?.int("RcPort")
                if (key != null && key.length >= 32 && port != null) return RcInfo(true, key, port, false)
            }
        }.onFailure { if (it is IloException && it.kind == IloException.Kind.TLS) throw it }

        val r = request("GET", "/json/rc_info", headers = sessionCookie(session))
        if (r.code != 200) {
            throw IloException(IloException.Kind.PROTOCOL, "Unable to get remote console information (HTTP ${r.code})")
        }
        val o = parseObject(r.body) ?: throw IloException(IloException.Kind.PROTOCOL, "Invalid rc_info response")
        val key = o.string("enc_key")
        val port = o.int("rc_port")
        if (key == null || key.length < 32 || port == null) {
            throw IloException(IloException.Kind.PROTOCOL, "Remote console is not available on this iLO")
        }
        val features = o.string("optional_features") ?: ""
        return RcInfo(false, key, port, features.contains("ENCRYPT_KEY"))
    }

    /** Server summary from the legacy JSON API (iLO 3/4/5). */
    fun overview(session: IloSession): ServerOverview? = runCatching {
        val r = request("GET", "/json/overview", headers = sessionCookie(session))
        if (r.code != 200) return null
        val o = parseObject(r.body) ?: return null
        ServerOverview(
            productName = o.string("product_name"),
            serverName = o.string("server_name"),
            serialNumber = o.string("serial_num"),
            iloFirmware = o.string("ilo_fw_version"),
            iloModel = o.string("ilo_name"),
            health = o.string("system_health"),
            powerState = o.string("power"),
        )
    }.getOrNull()

    /** Server summary through Redfish with HTTP basic authentication (iLO 4 2.x and later). */
    fun redfishOverview(username: String, password: String): ServerOverview {
        val auth = basicAuth(username, password)
        val r = request("GET", "/redfish/v1/Systems/1/", headers = auth)
        checkRedfish(r)
        val sys = parseObject(r.body) ?: throw IloException(IloException.Kind.PROTOCOL, "Invalid Redfish response")
        val mgr = runCatching { parseObject(request("GET", "/redfish/v1/Managers/1/", headers = auth).body) }.getOrNull()
        return ServerOverview(
            productName = sys.string("Model"),
            serverName = sys.string("HostName"),
            serialNumber = sys.string("SerialNumber"),
            iloModel = mgr?.string("Model"),
            iloFirmware = mgr?.string("FirmwareVersion"),
            biosVersion = sys.string("BiosVersion"),
            health = (sys["Status"] as? JsonObject)?.string("Health"),
            powerState = sys.string("PowerState"),
        )
    }

    fun redfishReset(username: String, password: String, type: ResetType) {
        val body = buildJsonObject { put("ResetType", type.redfish) }.toString()
        val r = request(
            "POST",
            "/redfish/v1/Systems/1/Actions/ComputerSystem.Reset/",
            body = body,
            headers = basicAuth(username, password),
            contentType = "application/json",
        )
        checkRedfish(r)
    }

    private fun checkRedfish(r: Response) {
        when {
            r.code == 401 || r.code == 403 -> throw IloException(IloException.Kind.AUTH_FAILED, "Invalid user name or password")
            r.code == 404 -> throw IloException(IloException.Kind.NOT_SUPPORTED, "Redfish is not available on this iLO")
            r.code !in 200..299 -> {
                val msg = parseObject(r.body)?.let { extractRedfishMessage(it) }
                throw IloException(IloException.Kind.HTTP, msg ?: "HTTP ${r.code}")
            }
        }
    }

    private fun extractRedfishMessage(o: JsonObject): String? {
        val err = o["error"] as? JsonObject ?: return null
        val ext = err["@Message.ExtendedInfo"]
        val first = (ext as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
        return first?.string("MessageId") ?: err.string("message")
    }

    private fun sessionCookie(session: IloSession) = mapOf("Cookie" to "sessionKey=${session.sessionKey}")

    private fun basicAuth(user: String, pass: String): Map<String, String> {
        val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
        return mapOf("Authorization" to "Basic $token")
    }

    class Response(val code: Int, val body: String, val headers: Map<String, List<String>>)

    fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/x-www-form-urlencoded;charset=UTF-8",
    ): Response {
        val conn = URL(baseUrl + path).openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = sslFactory
            // Identity is established by the pinned certificate, not by the host name.
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val lowerHeaders = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
            return Response(code, text, lowerHeaders)
        } catch (e: IloException) {
            throw e
        } catch (e: IOException) {
            throw translate(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    companion object {
        fun normalizeAddress(address: String): String =
            address.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

        fun translate(e: Throwable): IloException {
            CertificatePinning.findUntrusted(e)?.let {
                return IloException(IloException.Kind.TLS, it.message ?: "Untrusted certificate", it)
            }
            return when (e) {
                is IloException -> e
                is UnknownHostException -> IloException(IloException.Kind.UNKNOWN_HOST, "Unknown host", e)
                is SocketTimeoutException -> IloException(IloException.Kind.TIMEOUT, "Connection timed out", e)
                is ConnectException, is NoRouteToHostException ->
                    IloException(IloException.Kind.UNREACHABLE, e.message ?: "Host unreachable", e)
                is SSLException -> IloException(IloException.Kind.TLS, e.message ?: "TLS error", e)
                else -> IloException(IloException.Kind.UNREACHABLE, e.message ?: e.toString(), e)
            }
        }
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(name: String): Int? {
    val p = this[name] as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
}

