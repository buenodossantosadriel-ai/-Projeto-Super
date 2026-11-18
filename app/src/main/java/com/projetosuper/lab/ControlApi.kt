package com.projetosuper.lab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ControlApi {
    private const val ENDPOINT = "https://syozyulcoypfqeasdawg.supabase.co/functions/v1/projeto-super-controle"
    private const val API_KEY = "sb_publishable_KEhlMxn5krWrM4SmfGzGQA_CCW73O-e"
    private const val PREFS = "projeto_super_device"
    private const val KEY_ALIAS = "projeto_super_device_secret_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptSecret(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptSecret(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Chave local inválida." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun loadSecret(prefs: android.content.SharedPreferences): String? =
        prefs.getString("device_secret_enc", null)?.let { decryptSecret(it) }

    data class Credentials(val deviceId: String, val secret: String)

    private fun request(
        payload: JSONObject,
        deviceSecret: String? = null
    ): JSONObject {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("Content-Type", "application/json")
            deviceSecret?.let { setRequestProperty("x-device-secret", it) }
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = JSONObject(text)
            if (connection.responseCode !in 200..299 || result.optBoolean("ok") == false) {
                throw IllegalStateException(result.optString("error", "Falha na comunicação com o servidor."))
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    suspend fun ensureRegistered(context: Context): Credentials = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldId = prefs.getString("device_id", null)
        val oldSecret = loadSecret(prefs)
        if (!oldId.isNullOrBlank() && !oldSecret.isNullOrBlank()) {
            return@withContext Credentials(oldId, oldSecret)
        }
        val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("action", "register_device")
            .put("device_name", android.os.Build.MODEL ?: "Android de laboratório")
        val result = request(payload, secret)
        val id = result.getString("device_id")
        prefs.edit().putString("device_id", id).putString("device_secret_enc", encryptSecret(secret))
            .remove("pairing_code").apply()
        Credentials(id, secret)
    }

    suspend fun poll(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: throw IllegalStateException("Dispositivo não registrado.")
        val secret = loadSecret(prefs) ?: throw IllegalStateException("Chave local indisponível.")
        request(JSONObject().put("action", "device_poll").put("device_id", id), secret)
    }

    suspend fun decide(context: Context, requestId: String, accept: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: throw IllegalStateException("Dispositivo não registrado.")
        val secret = loadSecret(prefs) ?: throw IllegalStateException("Chave local indisponível.")
        request(
            JSONObject().put("action", "device_decide").put("device_id", id)
                .put("request_id", requestId).put("decision", if (accept) "accept" else "reject"),
            secret
        )
    }

    suspend fun stopSession(context: Context, sessionId: String): JSONObject = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: throw IllegalStateException("Dispositivo não registrado.")
        val secret = loadSecret(prefs) ?: throw IllegalStateException("Chave local indisponível.")
        request(JSONObject().put("action", "stop_session").put("device_id", id).put("session_id", sessionId), secret)
    }

    suspend fun uploadAudioChunk(
        context: Context,
        sessionId: String,
        wav: ByteArray,
        mode: String,
        chunkIndex: Long,
        durationMs: Int
    ): JSONObject = withContext(Dispatchers.IO) {
        require(mode == "live" || mode == "recording")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: throw IllegalStateException("Dispositivo não registrado.")
        val secret = loadSecret(prefs) ?: throw IllegalStateException("Chave local indisponível.")
        val boundary = "ProjetoSuper-" + UUID.randomUUID().toString()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("x-device-secret", secret)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\\r\\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\\"$name\\"\\r\\n\\r\\n".toByteArray())
                    out.write(value.toByteArray())
                    out.write("\\r\\n".toByteArray())
                }
                field("device_id", id)
                field("session_id", sessionId)
                field("mode", mode)
                field("chunk_index", chunkIndex.toString())
                field("duration_ms", durationMs.toString())
                out.write("--$boundary\\r\\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\\"audio_chunk\\"; filename=\\"audio.wav\\"\\r\\n".toByteArray())
                out.write("Content-Type: audio/wav\\r\\n\\r\\n".toByteArray())
                out.write(wav)
                out.write("\\r\\n--$boundary--\\r\\n".toByteArray())
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val result = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
            if (connection.responseCode !in 200..299 || !result.optBoolean("ok")) {
                throw IllegalStateException(result.optString("error", "Falha ao enviar áudio."))
            }
            result
        } finally {
            connection.disconnect()
        }
    }

    suspend fun uploadFrame(
        context: Context,
        sessionId: String,
        jpeg: ByteArray
    ): JSONObject = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: throw IllegalStateException("Dispositivo não registrado.")
        val secret = loadSecret(prefs) ?: throw IllegalStateException("Chave local indisponível.")
        val boundary = "ProjetoSuper-" + UUID.randomUUID().toString()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("x-device-secret", secret)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write(value.toByteArray())
                    out.write("\r\n".toByteArray())
                }
                field("device_id", id)
                field("session_id", sessionId)
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"frame\"; filename=\"frame.jpg\"\r\n".toByteArray())
                out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                out.write(jpeg)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val result = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
            if (connection.responseCode !in 200..299 || !result.optBoolean("ok")) {
                throw IllegalStateException(result.optString("error", "Falha ao enviar quadro."))
            }
            result
        } finally {
            connection.disconnect()
        }
    }
}
