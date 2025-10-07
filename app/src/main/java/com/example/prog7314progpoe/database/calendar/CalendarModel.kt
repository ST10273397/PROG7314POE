package com.example.prog7314progpoe.database.calendar

import android.location.Location
import com.example.prog7314progpoe.database.holidays.HolidayModel

data class CalendarModel(
    var calendarId: String = "",
    val title: String = "",
    val ownerId: String = "",
    val sharedWith: Map<String, Boolean>? = null,
    val holidays: Map<String, HolidayModel>? = null
){
    constructor() : this("", "", "", null, null)
}

