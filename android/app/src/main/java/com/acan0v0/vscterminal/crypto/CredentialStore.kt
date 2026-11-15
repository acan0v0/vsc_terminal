package com.acan0v0.vscterminal.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * VPS 地址、relay token、SSH 密码存于 Keystore 加密的 SharedPreferences，
 * 不写入普通偏好设置，也不进日志。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Endpoint(val host: String, val sshPort: Int, val sshUser: String, val token: String, val password: String)

    fun load(): Endpoint? {
        val host = prefs.getString(K_HOST, null) ?: return null
        val password = prefs.getString(K_PASSWORD, null) ?: return null
        val token = prefs.getString(K_TOKEN, null) ?: return null
        return Endpoint(
            host = host,
            sshPort = prefs.getInt(K_SSH_PORT, 22),
            sshUser = prefs.getString(K_SSH_USER, "root") ?: "root",
            token = token,
            password = password,
        )
    }

    fun save(e: Endpoint) {
        prefs.edit()
            .putString(K_HOST, e.host)
            .putInt(K_SSH_PORT, e.sshPort)
            .putString(K_SSH_USER, e.sshUser)
            .putString(K_TOKEN, e.token)
            .putString(K_PASSWORD, e.password)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val FILE = "bridge_credentials"
        const val K_HOST = "host"
        const val K_SSH_PORT = "ssh_port"
        const val K_SSH_USER = "ssh_user"
        const val K_TOKEN = "token"
        const val K_PASSWORD = "password"
    }
}
