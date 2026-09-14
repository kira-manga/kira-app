package me.manga.kira.platform.cbz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CbzTranscodeBudgetTest {
    @Test
    fun exactBudgetAdmitsOneRowButOneBytePixelOrRowOverDoesNot() {
        // 3*128 encoded +16*(8*9) source +4MiB fixed +2*64KiB output slack +48*8 one band row.
        val exactBudget = 4_327_296L
        val result = assertIs<CbzTranscodeAdmission.Admitted>(CbzTranscodeBudget.admit(8, 9, 128, 1, exactBudget))
        assertEquals(1, result.plan.bandHeight)
        assertEquals(exactBudget, result.plan.estimatedPeakBytes)
        assertEquals(65_568, result.plan.maxEncodedBandBytes)
        assertEquals(CbzTranscodeAdmission.PreserveBudget, CbzTranscodeBudget.admit(8, 9, 128, 1, exactBudget - 1))
        assertEquals(CbzTranscodeAdmission.PreserveBudget, CbzTranscodeBudget.admit(9, 9, 128, 1, exactBudget))
        assertEquals(CbzTranscodeAdmission.PreserveBudget, CbzTranscodeBudget.admit(8, 10, 128, 1, exactBudget))
    }

    @Test
    fun encodedSnapshotAndNativeCopiesAreReservedBeforeTheFirstBand() {
        val budgetWithoutEncodedCopies = 4_326_912L
        assertEquals(CbzTranscodeAdmission.PreserveBudget, CbzTranscodeBudget.admit(8, 9, 128, 1, budgetWithoutEncodedCopies))
        assertEquals(CbzTranscodeAdmission.PreserveBudget, CbzTranscodeBudget.admit(8, 9, Long.MAX_VALUE, 1, Long.MAX_VALUE))
        assertEquals(CbzTranscodeAdmission.PreserveBudget, CbzTranscodeBudget.admit(8, 9, 128, 1, 1))
    }

    @Test
    fun availableMemorySelectsOneBandWithoutAccumulatingAllBands() {
        val result = assertIs<CbzTranscodeAdmission.Admitted>(CbzTranscodeBudget.admit(8, 90, 128, 20, 4_338_816))
        assertEquals(4, result.plan.bandHeight)
        assertTrue(result.plan.estimatedPeakBytes <= 4_338_816)
    }

    @Test
    fun webpWidthLimitHasItsOwnPreservationReason() {
        assertEquals(CbzTranscodeAdmission.PreserveWebpDimensions, CbzTranscodeBudget.admit(16_384, 1, 128, 1))
        assertEquals(CbzTranscodeAdmission.PreserveWebpDimensions, CbzTranscodeBudget.admit(Int.MAX_VALUE, Int.MAX_VALUE, 128, 1))
    }

    @Test
    fun invalidOrZeroInputsCannotBeCoercedIntoAnAdmittedRow() {
        assertFailsWith<IllegalArgumentException> { CbzTranscodeBudget.admit(0, 9, 128, 1) }
        assertFailsWith<IllegalArgumentException> { CbzTranscodeBudget.admit(8, 0, 128, 1) }
        assertFailsWith<IllegalArgumentException> { CbzTranscodeBudget.admit(8, 9, 0, 1) }
        assertFailsWith<IllegalArgumentException> { CbzTranscodeBudget.admit(8, 9, 128, 0) }
        assertFailsWith<IllegalArgumentException> { CbzTranscodeBudget.admit(8, 9, 128, 1, 0) }
        assertEquals(100_000_000L, CbzWriter.DEFAULT_MAX_MEMORY_BYTES)
    }
}
