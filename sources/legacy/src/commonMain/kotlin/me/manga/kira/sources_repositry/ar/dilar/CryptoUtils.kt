package me.manga.kira.sources_repositry.ar.dilar

/**
 * Decrypts the pipe-delimited response payload used by the dilar source.
 *
 * The payload format is: `<ciphertext>|<unused>|<iv>|<keyMaterial>`.
 *  - `keyMaterial` is hashed with SHA-256 and the hex digest interpreted as a hex string
 *    is decoded to bytes to form the AES key.
 *  - `iv` is a Base64-encoded IV.
 *  - `ciphertext` is Base64-encoded and decrypted with AES/CBC/PKCS5Padding.
 *
 * Implemented per-platform via `expect/actual`:
 *  - Android/Desktop use `javax.crypto` + `java.security.MessageDigest`.
 *  - iOS uses CommonCrypto via Kotlin/Native cinterop.
 */
expect fun decrypt(responseData: String): String
