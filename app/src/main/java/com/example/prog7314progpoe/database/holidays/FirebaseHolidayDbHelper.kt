package com.example.prog7314progpoe.database.holidays

import com.example.prog7314progpoe.database.calendar.CalendarModel
import com.google.firebase.database.FirebaseDatabase

object FirebaseHolidayDbHelper {

    private val db = FirebaseDatabase
        .getInstance("https://chronosync-f3425-default-rtdb.europe-west1.firebasedatabase.app/")
        .reference

    // Add a holiday to a calendar
    fun addHoliday(
        calendarId: String,
        holiday: HolidayModel,
        onComplete: () -> Unit = {}
    ) {
        val holidayId = holiday.holidayId.ifEmpty { db.child("calendars/$calendarId/holidays").push().key!! }
        holiday.holidayId = holidayId

        db.child("calendars").child(calendarId).child("holidays").child(holidayId)
            .setValue(holiday)
            .addOnCompleteListener { onComplete() }
    }

    // Update an existing holiday
    fun updateHoliday(
        calendarId: String,
        holiday: HolidayModel,
        onComplete: (Boolean) -> Unit
    ) {
        if (holiday.holidayId.isEmpty()) {
            onComplete(false)
            return
        }

        db.child("calendars").child(calendarId).child("holidays").child(holiday.holidayId)
            .setValue(holiday)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    // Delete a holiday from a calendar
    fun deleteHoliday(
        calendarId: String,
        holidayId: String,
        onComplete: () -> Unit = {}
    ) {
        db.child("calendars").child(calendarId).child("holidays").child(holidayId)
            .removeValue()
            .addOnCompleteListener { onComplete() }
    }

    // Get all holidays for a specific calendar
    fun getAllHolidays(
        calendarId: String,
        callback: (List<HolidayModel>) -> Unit
    ) {
        db.child("calendars").child(calendarId).child("holidays").get()
            .addOnSuccessListener { snapshot ->
                val holidays = snapshot.children.mapNotNull { it.getValue(HolidayModel::class.java) }
                callback(holidays)
            }
            .addOnFailureListener { callback(emptyList()) }
    }
}
