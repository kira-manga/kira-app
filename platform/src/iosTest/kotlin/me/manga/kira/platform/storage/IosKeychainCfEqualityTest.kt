@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.storage

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateMutableCopy
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real CF values only: no Security calls, authentication context or Keychain access. */
class IosKeychainCfEqualityTest {
    @Test
    fun stringsUseCompleteLiteralContentAcrossDistinctNativeObjects() {
        withCfString("slot.\u0000cafe\u0301") { first ->
            withCfString("slot.\u0000cafe\u0301") { second ->
                // Mutable copies cannot be interned into one object: identity is deliberately unequal.
                assertNotEquals(first, second)
                assertTrue(keychainCfStringEquals(first, second))
                assertTrue(keychainCfStringEquals(second, first))
            }
        }
        val unequalValues =
            listOf(
                "slot.\u0000first" to "slot.\u0000second",
                "slot.\u0000first" to "slot.",
                "Slot.value" to "slot.value",
                "slot.\u00e9" to "slot.e\u0301",
            )
        for ((firstText, secondText) in unequalValues) {
            withCfString(firstText) { first ->
                withCfString(secondText) { second ->
                    assertFalse(keychainCfStringEquals(first, second))
                    assertFalse(keychainCfStringEquals(second, first))
                }
            }
        }
    }

    @Test
    fun booleansAndStringsRejectNullOrWrongNativeTypes() {
        val yes = assertNotNull(kCFBooleanTrue)
        val no = assertNotNull(kCFBooleanFalse)
        assertTrue(keychainCfBooleanEquals(yes, yes))
        assertTrue(keychainCfBooleanEquals(no, no))
        assertFalse(keychainCfBooleanEquals(yes, no))
        assertFalse(keychainCfBooleanEquals(no, yes))
        withCfString("false") { string ->
            for (value in listOf(0, 1)) {
                withCfNumber(value) { number ->
                    assertEquals(CFNumberGetTypeID(), CFGetTypeID(number))
                    val boolean = if (value == 0) no else yes
                    for (invalid in listOf<CFTypeRef?>(null, number, string)) {
                        assertFalse(keychainCfBooleanEquals(invalid, boolean))
                        assertFalse(keychainCfBooleanEquals(boolean, invalid))
                        assertFalse(keychainCfBooleanEquals(invalid, invalid))
                    }
                    for (invalid in listOf<CFTypeRef?>(null, number, yes, no)) {
                        assertFalse(keychainCfStringEquals(invalid, string))
                        assertFalse(keychainCfStringEquals(string, invalid))
                        assertFalse(keychainCfStringEquals(invalid, invalid))
                    }
                }
            }
        }
    }
}

private fun withCfString(
    value: String,
    operation: (CFStringRef) -> Unit,
) {
    val bytes = value.encodeToByteArray()
    val original =
        bytes.usePinned {
            assertNotNull(
                CFStringCreateWithBytes(
                    null,
                    if (bytes.isEmpty()) null else it.addressOf(0).reinterpret(),
                    bytes.size.toLong(),
                    kCFStringEncodingUTF8,
                    false,
                ),
            )
        }
    try {
        val copy = assertNotNull(CFStringCreateMutableCopy(null, 0, original))
        try {
            assertEquals(value.length.toLong(), CFStringGetLength(copy))
            operation(copy)
        } finally {
            CFRelease(copy)
        }
    } finally {
        CFRelease(original)
    }
}

private fun withCfNumber(
    value: Int,
    operation: (CFNumberRef) -> Unit,
) {
    memScoped {
        val scalar = alloc<IntVar>()
        scalar.value = value
        val number = assertNotNull(CFNumberCreate(null, kCFNumberIntType, scalar.ptr))
        try {
            operation(number)
        } finally {
            CFRelease(number)
        }
    }
}
