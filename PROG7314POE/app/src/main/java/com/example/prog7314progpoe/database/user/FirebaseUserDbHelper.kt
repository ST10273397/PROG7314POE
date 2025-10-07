package com.example.prog7314progpoe.database.user

import com.example.prog7314progpoe.database.holidays.HolidayModel.DateInfo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

object FirebaseUserDbHelper {

    private val db = FirebaseDatabase
        .getInstance("https://chronosync-f3425-default-rtdb.europe-west1.firebasedatabase.app/")
        .getReference("users")

    private val calendarRef = FirebaseDatabase
        .getInstance("https://chronosync-f3425-default-rtdb.europe-west1.firebasedatabase.app/")
        .getReference("calendars")

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    fun registerUser(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
        dateOfBirth: String,
        location: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener
                    val user = UserModel(
                        userId = uid,
                        email = email,
                        firstName = firstName,
                        lastName = lastName,
                        location = location,
                        dateOfBirth = DateInfo(dateOfBirth)
                    )
                    db.child(uid).setValue(user)
                        .addOnSuccessListener { onComplete(true, "Registration successful") }
                        .addOnFailureListener { ex -> onComplete(false, "Error: ${ex.message}") }
                } else {
                    onComplete(false, "Registration failed: ${task.exception?.message}")
                }
            }
    }

    fun loginUser(
        email: String,
        password: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) onComplete(true, "Login successful")
                else onComplete(false, "Login failed: ${task.exception?.message}")
            }
    }

    fun getUser(userId: String, callback: (UserModel?) -> Unit) {
        db.child(userId).get().addOnSuccessListener {
            callback(it.getValue(UserModel::class.java))
        }.addOnFailureListener { callback(null) }
    }

    fun getAllUsers(callback: (List<UserModel>) -> Unit) {
        db.get().addOnSuccessListener { snapshot ->
            val list = snapshot.children.mapNotNull { it.getValue(UserModel::class.java) }
            callback(list)
        }.addOnFailureListener { callback(emptyList()) }
    }

    // Add a calendar to a user's dashboard
    fun addCalendarToDashboard(calendarId: String, onComplete: (Boolean, String) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onComplete(false, "Not logged in")

        db.child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.childrenCount >= 8) {
                    onComplete(false, "You can only have 8 calendars on your dashboard")
                    return
                }

                db.child(uid).child(calendarId).setValue(true)
                    .addOnSuccessListener { onComplete(true, "Added successfully") }
                    .addOnFailureListener { onComplete(false, "Error adding calendar") }
            }

            override fun onCancelled(error: DatabaseError) {
                onComplete(false, "Error: ${error.message}")
            }
        })
    }

    // Remove a calendar from a user's dashboard
    fun removeCalendarFromDashboard(calendarId: String, onComplete: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onComplete(false)
        db.child(uid).child(calendarId).removeValue()
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    // Fetch all dashboard calendars (full calendar data)
    fun getUserDashboardCalendars(callback: (List<Map<String, Any>>) -> Unit) {
        val uid = auth.currentUser?.uid ?: return callback(emptyList())

        db.child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val calendarIds = snapshot.children.map { it.key ?: "" }

                if (calendarIds.isEmpty()) {
                    callback(emptyList())
                    return
                }

                val resultList = mutableListOf<Map<String, Any>>()
                var remaining = calendarIds.size

                for (id in calendarIds) {
                    calendarRef.child(id).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(calendarSnap: DataSnapshot) {
                            calendarSnap.value?.let {
                                val data = calendarSnap.value as Map<String, Any>
                                resultList.add(data)
                            }
                            remaining--
                            if (remaining == 0) callback(resultList)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            remaining--
                            if (remaining == 0) callback(resultList)
                        }
                    })
                }
            }

            override fun onCancelled(error: DatabaseError) {
                callback(emptyList())
            }
        })
    }
}

