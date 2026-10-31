package com.ghayyath.claudepulse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM credentials vault. No fallback to plaintext on decryption errors. */
object AccountVault {
    data class Account(
        val id: String,
        val label: String,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val expiresAtMs: Long = 0L,
        val pendingVerifier: String? = null,
        val pendingState: String? = null
    ) {
        val connected: Boolean get() = !accessToken.isNullOrEmpty() || !refreshToken.isNullOrEmpty()
    }

    private const val PREFS = "trigenys_credential_vault"
    private const val DATA = "encrypted_accounts_v1"
    private const val KEY_ALIAS = "trigenys_claude_monitor_aes_gcm_v1"
    private val defaults = listOf(
        "ibrahim" to "Ibrahim",
        "thunderbrainstorm" to "Thunderbrainstorm",
        "data-school" to "Trigenys Data School",
        "hello-trigenys" to "Hello Trigenys"
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized fun ensureDefaults(ctx: Context) {
        if (prefs(ctx).contains(DATA)) {
            read(ctx) // A broken vault must NOT be overwritten.
        } else {
            save(ctx, defaults.map { (id, name) -> Account(id, name) })
        }
    }

    @Synchronized fun read(ctx: Context): List<Account> {
        val encoded = prefs(ctx).getString(DATA, null) ?: return emptyList()
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size > 12) { "Invalid credential vault" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8))
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Account(
                id = item.getString("id"),
                label = item.getString("label"),
                accessToken = nullable(item, "accessToken"),
                refreshToken = nullable(item, "refreshToken"),
                expiresAtMs = item.optLong("expiresAtMs", 0),
                pendingVerifier = nullable(item, "pendingVerifier"),
                pendingState = nullable(item, "pendingState")
            )
        }
    }

    @Synchronized fun find(ctx: Context, id: String): Account? =
        read(ctx).firstOrNull { it.id == id }

    @Synchronized fun update(ctx: Context, id: String, modify: (Account) -> Account) {
        val existing = read(ctx)
        require(existing.any { it.id == id }) { "Unknown account" }
        save(ctx, existing.map { if (it.id == id) modify(it) else it })
    }

    private fun nullable(value: JSONObject, name: String): String? =
        if (value.isNull(name)) null else value.optString(name).takeIf { it.isNotEmpty() && it != "null" }

    private fun save(ctx: Context, accounts: List<Account>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(JSONObject().apply {
                put("id", account.id)
                put("label", account.label)
                put("accessToken", account.accessToken)
                put("refreshToken", account.refreshToken)
                put("expiresAtMs", account.expiresAtMs)
                put("pendingVerifier", account.pendingVerifier)
                put("pendingState", account.pendingState)
            })
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(array.toString().toByteArray(StandardCharsets.UTF_8))
        check(prefs(ctx).edit().putString(DATA, Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not save encrypted credentials"
        }
    }
}
