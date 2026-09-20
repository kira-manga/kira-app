@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.storage

import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFBooleanGetTypeID
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFCompareEqualTo

/** Borrowed references only; no decoding, normalization, ownership transfer or pointer equality. */
internal fun keychainCfStringEquals(
    first: CFTypeRef?,
    second: CFTypeRef?,
): Boolean =
    first != null &&
        second != null &&
        CFGetTypeID(first) == CFStringGetTypeID() &&
        CFGetTypeID(second) == CFStringGetTypeID() &&
        // Zero flags preserve literal full-string equality, including embedded NUL in recovery accounts.
        CFStringCompare(first.reinterpret(), second.reinterpret(), 0uL) == kCFCompareEqualTo

/** CFNumber zero/one and textual booleans are never substitutes for native CFBoolean attributes. */
internal fun keychainCfBooleanEquals(
    first: CFTypeRef?,
    second: CFTypeRef?,
): Boolean =
    first != null &&
        second != null &&
        CFGetTypeID(first) == CFBooleanGetTypeID() &&
        CFGetTypeID(second) == CFBooleanGetTypeID() &&
        CFBooleanGetValue(first.reinterpret()) == CFBooleanGetValue(second.reinterpret())
