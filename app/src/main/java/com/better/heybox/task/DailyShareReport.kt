package com.better.heybox.task

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

                                                                 
object DailyShareReport {

    private const val PUBLIC_KEY_BODY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDZgjVwAiKTjZ55nG+mW6r3TSU4" +
            "ECvNYqDMIS/bhCj2QaH5GI/KZb2TBp+CBvUj9SLFnmJQ0kzHzHoGZCQ88VevCffF7JePGF9cmKQqotlfTKbV4oxV5iLz7JSG6b/Vg7AXtrTolNtWsa8HiB0tI0YClYaQlOXm4UxLeSxQwSFETwIDAQAB"
    private const val AES_IV = "abcdefghijklmnop"
    private const val ALPHABET = "AB45STUVWZEFGJ6CH01D237IXYPQRKLMN89"

    class EncryptedReport(val data: String, val key: String, val sid: String, val time: String)

    class WebSignature(val hkey: String, val time: String, val nonce: String)

                                                                                                           
    fun encrypt(eventsJson: String, time: String, aesKeyHex: String? = null): EncryptedReport {
        val keyHex = aesKeyHex ?: newAesKeyHex()
        require(keyHex.length == 16) { "AES 密钥须为 16 个字符" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyHex.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.US_ASCII))
        )
        val payload = gzip(eventsJson.toByteArray(Charsets.UTF_8))
        val data = androidBase64(cipher.doFinal(payload))
        val key = androidBase64(rsaEncrypt(keyHex.toByteArray(Charsets.US_ASCII)))
        return EncryptedReport(data, key, md5Hex(key + time) + md5Hex(data), time)
    }

                                                         
    fun webSignature(path: String, time: Long, nonce: String = newNonce()): WebSignature {
        fun mapChars(text: String, chars: String): String =
            buildString { for (c in text) append(chars[c.code % chars.length]) }
        fun normalizePath(p: String): String =
            "/" + p.split('/').filter { it.isNotEmpty() }.joinToString("/") + "/"
        fun double(n: Int): Int = if (n and 128 != 0) ((n shl 1) xor 27) and 255 else n shl 1
        fun triple(n: Int): Int = double(n) xor n
        fun k(n: Int): Int = triple(double(n))
        fun c(n: Int): Int = k(triple(double(n)))
        fun s(n: Int): Int = c(n) xor k(n) xor triple(n)
        val parts = listOf(
            mapChars((time + 1).toString(), ALPHABET.substring(0, ALPHABET.length - 2)),
            mapChars(normalizePath(path), ALPHABET),
            mapChars(nonce, ALPHABET)
        )
        val maxLen = parts.maxOf { it.length }
        val mixed = buildString {
            for (i in 0 until maxLen) {
                for (part in parts) {
                    if (i < part.length) {
                        append(part[i])
                    }
                }
            }
        }
        val digest = md5Hex(mixed.take(20))
        val input = digest.takeLast(6).map { it.code }
        val transforms: Array<(Int) -> Int> =
            arrayOf({ s(it) }, { c(it) }, { k(it) }, { triple(it) })
        var total = input.drop(4).sum()
        for (row in 0 until 4) {
            var x = 0
            for (col in 0 until 4) {
                x = x xor transforms[(col - row + 4) % 4](input[col])
            }
            total += x
        }
        val hkey = mapChars(
            digest.take(5),
            ALPHABET.substring(0, ALPHABET.length - 4)
        ) + (total % 100).toString().padStart(2, '0')
        return WebSignature(hkey, time.toString(), nonce)
    }

                                        
    fun androidBase64(bytes: ByteArray): String {
        val raw = java.util.Base64.getEncoder().encodeToString(bytes)
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (i > 0) {
                sb.append('\n')
            }
            sb.append(raw, i, minOf(i + 76, raw.length))
            i += 76
        }
        sb.append('\n')
        return sb.toString()
    }

    fun md5Hex(value: String): String {
        return MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun newAesKeyHex(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.uppercase(Locale.US)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(input) }
        return out.toByteArray()
    }

    private fun rsaEncrypt(input: ByteArray): ByteArray {
        val spec = X509EncodedKeySpec(java.util.Base64.getDecoder().decode(PUBLIC_KEY_BODY))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(input)
    }
}
