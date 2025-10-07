package com.example.prog7314progpoe.database.calendar

import com.example.prog7314progpoe.database.holidays.HolidayModel
import com.example.prog7314progpoe.database.user.UserModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

object FirebaseCalendarDbHelper {

    private val db = FirebaseDatabase
        .getInstance("https://chronosync-f3425-default-rtdb.europe-west1.firebasedatabase.app/")
        .reference

    private val auth = FirebaseAuth.getInstance()

    // Create new calendar
    fun insertCalendar(
        ownerId: String,
        title: String,
        holidays: List<HolidayModel>? = null,
        onComplete: (String?) -> Unit = {} // pass back the calendar ID (or null if failed)
    ) {
        val key = db.child("calendars").push().key ?: return onComplete(null)

        val holidayMap = holidays?.associateBy { it.holidayId ?: db.child("calendars").push().key!! }

        val calendar = CalendarModel(
            calendarId = "UM-$key",
            title = title,
            ownerId = ownerId,
            sharedWith = mapOf(ownerId to true),
            holidays = holidayMap
        )

        val updates = hashMapOf<String, Any>(
            "/calendars/$key" to calendar,
            "/user_calendars/$ownerId/$key" to true
        )

        db.updateChildren(updates).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                onComplete("UM-$key") // send back the new calendar ID
            } else {
                onComplete(null)
            }
        }
    }


    // Share calendar with another user
    fun shareCalendar(calendarId: String, userId: String, onComplete: () -> Unit = {}) {
        val updates = hashMapOf<String, Any>(
            "/calendars/$calendarId/sharedWith/$userId" to true,
            "/user_calendars/$userId/$calendarId" to true
        )
        db.updateChildren(updates).addOnCompleteListener { onComplete() }
    }

    // Share calendar with another user via email
    fun shareCalendarByEmail(
        calendarId: String,
        targetEmail: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val usersRef = db.child("users")

        // Search for user with matching email
        usersRef.orderByChild("email").equalTo(targetEmail)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // There should only be one user with that email
                    val userId = snapshot.children.first().key ?: return@addOnSuccessListener
                    shareCalendar(calendarId, userId) {
                        onSuccess()
                    }
                } else {
                    onError("User with that email not found.")
                }
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "Failed to find user.")
            }
    }

    fun getSharedUsers(calendarId: String, onComplete: (List<UserModel>) -> Unit) {
        val db = FirebaseDatabase.getInstance().reference
        val usersRef = db.child("users")

        db.child("calendars").child(calendarId).child("sharedWith").get()
            .addOnSuccessListener { snapshot ->
                val sharedUserIds = snapshot.children.mapNotNull { it.key }
                if (sharedUserIds.isEmpty()) {
                    onComplete(emptyList())
                    return@addOnSuccessListener
                }

                val usersList = mutableListOf<UserModel>()
                var remaining = sharedUserIds.size

                sharedUserIds.forEach { userId ->
                    usersRef.child(userId).get()
                        .addOnSuccessListener { userSnap ->
                            userSnap.getValue(UserModel::class.java)?.let { usersList.add(it) }
                            remaining--
                            if (remaining == 0) onComplete(usersList)
                        }
                        .addOnFailureListener { remaining--; if (remaining == 0) onComplete(usersList) }
                }
            }
            .addOnFailureListener { onComplete(emptyList()) }
    }

    /**
     * Remove a specific user from a shared calendar.
     */
    fun removeUserFromCalendar(
        calendarId: String,
        userId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val updates = hashMapOf<String, Any?>(
            "/calendars/$calendarId/sharedWith/$userId" to null,
            "/user_calendars/$userId/$calendarId" to null
        )

        db.updateChildren(updates)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(e.message ?: "Failed to remove user.") }
    }

    /**
     * Let the current user leave a shared calendar.
     */
    fun leaveCalendar(
        calendarId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentUserId = auth.currentUser?.uid ?: return onError("Not logged in")

        // Prevent the owner from removing themselves
        db.child("calendars").child(calendarId).child("ownerId").get()
            .addOnSuccessListener { snapshot ->
                val ownerId = snapshot.value?.toString()
                if (ownerId == currentUserId) {
                    onError("You cannot leave your own calendar.")
                    return@addOnSuccessListener
                }

                removeUserFromCalendar(calendarId, currentUserId, onSuccess, onError)
            }
            .addOnFailureListener {
                onError("Error checking calendar ownership: ${it.message}")
            }
    }

    // Get all calendars for a user
    fun getUserCalendars(userId: String, callback: (List<CalendarModel>) -> Unit) {
        db.child("user_calendars").child(userId).get().addOnSuccessListener { snapshot ->
            val calendarIds = snapshot.children.mapNotNull { it.key }
            val calendars = mutableListOf<CalendarModel>()
            if (calendarIds.isEmpty()) {
                callback(emptyList())
                return@addOnSuccessListener
            }

            calendarIds.forEach { id ->
                db.child("calendars").child(id).get()
                    .addOnSuccessListener { calSnap ->
                        calSnap.getValue(CalendarModel::class.java)?.let { calendars.add(it) }
                        if (calendars.size == calendarIds.size) callback(calendars)
                    }
            }
        }
    }

    // Update calendar
    fun updateCalendar(calendar: CalendarModel, onComplete: (Boolean) -> Unit) {
        db.child("calendars").child(calendar.calendarId)
            .setValue(calendar)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    // Delete calendar
    fun deleteCalendar(calendarId: String, ownerId: String, onComplete: () -> Unit = {}) {
        val updates = hashMapOf<String, Any?>(
            "/calendars/$calendarId" to null,
            "/user_calendars/$ownerId/$calendarId" to null
        )
        db.updateChildren(updates).addOnCompleteListener { onComplete() }
    }
}
