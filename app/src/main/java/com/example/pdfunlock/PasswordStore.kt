package com.example.pdfunlock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores PDF passwords encrypted with an Android Keystore key (never leaves the device). */
object PasswordStore {
    private const val ALIAS = "pdf_unlock_key"
    private const val PREFS = "pdf_passwords"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, id: String, password: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val enc = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + enc
            prefs(ctx).edit().putString(id, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        } catch (_: Exception) {
        }
    }

    fun get(ctx: Context, id: String): String? {
        val s = prefs(ctx).getString(id, null) ?: return null
        return try {
            val blob = Base64.decode(s, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun forget(ctx: Context, id: String) {
        prefs(ctx).edit().remove(id).apply()
    }
}
