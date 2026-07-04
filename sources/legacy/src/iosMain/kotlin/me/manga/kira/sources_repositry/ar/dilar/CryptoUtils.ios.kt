package me.manga.kira.sources_repositry.ar.dilar

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCKeySizeAES256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.darwin.NSUIntegerVar

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
actual fun decrypt(responseData: String): String {
    val enc = responseData.split("|")
    val secretKey = enc[3].sha256Hex().hexStringToByteArray()
    return enc[0].aesDecrypt(secretKey, enc[2])
}

private fun String.hexStringToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = (
                (Character_digit(this[i]) shl 4) + Character_digit(this[i + 1])
                ).toByte()
        i += 2
    }
    return data
}

/** Replacement for `java.lang.Character.digit(ch, 16)` — returns the 0..15 value of a hex char. */
private fun Character_digit(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> -1
}

@OptIn(ExperimentalForeignApi::class)
private fun String.sha256Hex(): String {
    val bytes = encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { inputPin ->
        digest.usePinned { digestPin ->
            CC_SHA256(
                inputPin.addressOf(0),
                bytes.size.convert(),
                digestPin.addressOf(0).reinterpret(),
            )
        }
    }
    return digest.joinToString(separator = "") { b ->
        val v = b.toInt() and 0xFF
        val hi = v ushr 4
        val lo = v and 0x0F
        "${hexChar(hi)}${hexChar(lo)}"
    }
}

private fun hexChar(nibble: Int): Char =
    if (nibble < 10) ('0' + nibble) else ('a' + (nibble - 10))

/**
 * Decode a Base64 string into raw bytes using Kotlin stdlib's `kotlin.io.encoding.Base64`
 * (KMP-portable). Returns an empty array on malformed input rather than throwing — preserves the
 * defensive behaviour the Foundation `base64DecodedDataWithOptions` API exhibited.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun base64Decode(base64: String): ByteArray =
    try {
        Base64.decode(base64)
    } catch (_: IllegalArgumentException) {
        ByteArray(0)
    }

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
private fun String.aesDecrypt(secretKey: ByteArray, ivString: String): String {
    val iv = base64Decode(ivString)
    val cipherBytes = base64Decode(this)
    if (cipherBytes.isEmpty()) return ""

    require(secretKey.size == kCCKeySizeAES256.toInt()) {
        "AES key must be ${kCCKeySizeAES256.toInt()} bytes, was ${secretKey.size}"
    }

    val outBufferSize = cipherBytes.size + kCCBlockSizeAES128.toInt()
    val outBytes = ByteArray(outBufferSize)

    memScoped {
        val numBytesDecrypted = alloc<NSUIntegerVar>()
        val status = CCCrypt(
            op = kCCDecrypt,
            alg = kCCAlgorithmAES,
            options = kCCOptionPKCS7Padding,
            key = secretKey.refTo(0),
            keyLength = secretKey.size.convert(),
            iv = iv.refTo(0),
            dataIn = cipherBytes.refTo(0),
            dataInLength = cipherBytes.size.convert(),
            dataOut = outBytes.refTo(0),
            dataOutAvailable = outBufferSize.convert(),
            dataOutMoved = numBytesDecrypted.ptr,
        )
        check(status == kCCSuccess) { "CCCrypt failed with status $status" }
        val decryptedLen = numBytesDecrypted.value.toInt()
        return outBytes.decodeToString(0, decryptedLen)
    }
}
