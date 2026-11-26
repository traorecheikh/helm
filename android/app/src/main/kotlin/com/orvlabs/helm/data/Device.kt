package com.orvlabs.helm.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

object Device {
    private fun alias(id: Int) = if (id == 0) "helm_device" else "helm_device_$id"
    private val ks get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun spec(id: Int, strongBox: Boolean) = KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setIsStrongBoxBacked(strongBox)
        .build()

    private fun generate(id: Int) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(id, true))
            gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            gen.initialize(spec(id, false))
            gen.generateKeyPair()
        }
    }

    private fun publicDer(id: Int): ByteArray {
        if (!ks.containsAlias(alias(id))) generate(id)
        return ks.getCertificate(alias(id)).publicKey.encoded
    }

    fun publicKey(id: Int): String = Base64.encodeToString(publicDer(id), Base64.NO_WRAP)

    fun fingerprint(id: Int): String =
        MessageDigest.getInstance("SHA-256").digest(publicDer(id)).joinToString("") { "%02x".format(it) }.take(10).uppercase()
            .let { it.take(5) + "-" + it.drop(5) }

    fun sign(id: Int, message: ByteArray): String {
        publicDer(id)
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(ks.getKey(alias(id), null) as PrivateKey)
            update(message)
        }
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    fun reset(id: Int) {
        runCatching { ks.deleteEntry(alias(id)) }
    }
}
