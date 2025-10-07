package com.example.prog7314progpoe.ui.dashboard

sealed class SlotUiModel(open val index: Int) {
    data class Unassigned(override val index: Int): SlotUiModel(index)
    data class Populated(
        override val index: Int,
        val calendarName: String,
        val nextEventText: String
    ): SlotUiModel(index)
}
