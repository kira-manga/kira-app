package me.manga.yamiapk.core.avif



import androidx.annotation.IntRange

sealed interface HeifQualityArgument {
    fun getRequiredQuality(): Int
    fun getRequiredPreset(): HeifPreset
    fun getRequiredCrf(): Int
    fun isCrfMode(): Boolean
}

sealed class HeifQualityArg: HeifQualityArgument {
    data class Quality(@IntRange(from = 0, to = 100) val qual: Int):HeifQualityArgument {
        init {
            require(qual in 0..100) {
                throw IllegalStateException("Quality should be in 0..100 range")
            }
        }

        override fun getRequiredQuality(): Int {
            return qual
        }

        override fun getRequiredPreset(): HeifPreset {
            return HeifPreset.ULTRAFAST
        }

        override fun getRequiredCrf(): Int {
            return 40
        }

        override fun isCrfMode(): Boolean {
            return false
        }
    }
    data class Crf(@IntRange(from = 0, to = 51) val crf: Int = 40, val preset: HeifPreset = HeifPreset.ULTRAFAST): HeifQualityArgument {
        init {
            require(crf in 0..51) {
                throw IllegalStateException("CRF should be in 0..51 range")
            }
        }

        override fun getRequiredQuality(): Int {
            return 100
        }

        override fun getRequiredPreset(): HeifPreset {
            return preset
        }

        override fun getRequiredCrf(): Int {
            return crf
        }

        override fun isCrfMode(): Boolean {
            return true
        }
    }
}