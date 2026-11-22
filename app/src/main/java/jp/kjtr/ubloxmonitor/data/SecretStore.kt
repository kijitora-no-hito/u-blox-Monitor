package jp.kjtr.ubloxmonitor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * NTRIP のパスワードを暗号化して保存する（［パスワードを保存する］を ON にしたときだけ使う）。
 * 鍵は Android Keystore の AES-256/GCM で、Keystore の外には出ない。保存先は SharedPreferences の
 * correction_secret.xml（バックアップ・機種変更の転送から外してある。res/xml/data_extraction_rules.xml）。
 * 復号できない値（鍵が消えた・別の端末から来た・壊れた）は捨てる。
 */
class SecretStore(context: Context) {
    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** 暗号化して保存する。失敗したら false（保存しない）。 */
    fun save(name: String, value: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + encrypted
        sp.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        true
    } catch (_: Exception) {
        clear(name)
        false
    }

    /** 復号して返す。無い・復号できなければ null（復号できない値は消す）。 */
    fun load(name: String): String? {
        val text = sp.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(text, Base64.NO_WRAP)
            if (blob.size <= IV_SIZE) throw IllegalArgumentException("短すぎます")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
            String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(name)
            null
        }
    }

    fun clear(name: String) {
        sp.edit().remove(name).apply()
    }

    companion object {
        const val FILE = "correction_secret"
        const val NTRIP_PASSWORD = "ntrip_password"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "correction_password"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
