package me.manga.yamiapk

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject


@HiltViewModel
class TextViewModel @Inject constructor(

    private val state : SavedStateHandle
) : ViewModel() {

    // 1) Backing MutableStateFlow
    private val _number = MutableStateFlow(state["counter"] ?: 0)

    // 2) Exposed as read‑only StateFlow
    val number: StateFlow<Int> = _number

    // 3) Increment function
    fun increase() {
        _number.value = _number.value + 1

        state["counter"] = _number.value
        //—or using the handy update extension:
        // _number.update { it + 1 }
    }
}