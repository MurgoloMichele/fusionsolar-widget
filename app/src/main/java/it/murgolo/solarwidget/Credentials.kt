package it.murgolo.solarwidget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the FusionSolar credentials on the device, password encrypted with an Android Keystore key. */
object Credentials {
    private const val ALIAS = "fusion_cred"

    fun save(ctx: Context, user: String, password: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.iv + c.doFinal(password.toByteArray())
        prefs(ctx).edit()
            .putString("user", user)
            .putString("pass", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    fun user(ctx: Context): String? = prefs(ctx).getString("user", null)

    fun password(ctx: Context): String? {
        val raw = prefs(ctx).getString("pass", null) ?: return null
        return try {
            val b = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12))
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cred", Context.MODE_PRIVATE)
}
