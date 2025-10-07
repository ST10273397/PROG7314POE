package com.example.prog7314progpoe.ui.custom

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.prog7314progpoe.R
import com.example.prog7314progpoe.database.calendar.CalendarModel
import com.example.prog7314progpoe.database.holidays.FirebaseHolidayDbHelper
import com.example.prog7314progpoe.database.holidays.HolidayModel
import com.example.prog7314progpoe.ui.dashboard.DashboardFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class CreateEventActivity : AppCompatActivity() {

    private lateinit var calendarSpinner: Spinner
    private lateinit var repeatsSpinner: Spinner
    private lateinit var db: DatabaseReference
    private var calendarList: MutableList<CalendarModel> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_event)

        // 🔹 Initialize views
        calendarSpinner = findViewById(R.id.spn_calendar)
        repeatsSpinner = findViewById(R.id.spn_Repeats)

        val titleEt = findViewById<EditText>(R.id.et_EventTitle)
        val timeStartEt = findViewById<EditText>(R.id.et_StartTime)
        val timeEndEt = findViewById<EditText>(R.id.et_EndTime)
        val dateEt = findViewById<EditText>(R.id.et_Date)
        val descEt = findViewById<EditText>(R.id.et_Desc)
        val createBtn = findViewById<Button>(R.id.btn_Create)
        val resetBtn = findViewById<Button>(R.id.btn_Reset)
        val cancelBtn = findViewById<Button>(R.id.btn_Cancel)

        db = FirebaseDatabase.getInstance().reference
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // 🔹 Load user calendars
        loadUserCalendars(currentUserId)

        // 🔹 Setup repeat spinner (simple selection by name)
        val repeatOptions = listOf("None", "Daily", "Weekly", "Monthly", "Annually")
        val repeatAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            repeatOptions
        )
        repeatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        repeatsSpinner.adapter = repeatAdapter
        repeatsSpinner.setSelection(0) // default "None"

        // 🔹 Create button
        createBtn.setOnClickListener {
            val selectedCalendarPos = calendarSpinner.selectedItemPosition
            if (selectedCalendarPos < 0 || selectedCalendarPos >= calendarList.size) {
                Toast.makeText(this, "Please select a calendar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedCalendar = calendarList[selectedCalendarPos]

            val title = titleEt.text.toString().trim()
            val timeStart = timeStartEt.text.toString().trim()
            val timeEnd = timeEndEt.text.toString().trim()
            val date = dateEt.text.toString().trim()
            val desc = descEt.text.toString().trim()
            val repeatChoice = repeatsSpinner.selectedItem.toString()

            if (title.isEmpty() || date.isEmpty()) {
                Toast.makeText(this, "Please fill out required fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 🔹 Build HolidayModel
            val holiday = HolidayModel(
                holidayId = null,
                name = title,
                desc = desc,
                date = HolidayModel.DateInfo(date),
                timeStart = System.currentTimeMillis(), // replace with parsed time if needed
                timeEnd = System.currentTimeMillis(),
                repeat = listOf(repeatChoice),
                type = listOf("General")
            )

            // 🔹 Save to Firebase
            FirebaseHolidayDbHelper.addHoliday(
                calendarId = selectedCalendar.calendarId ?: return@setOnClickListener,
                holiday = holiday
            ) {
                Toast.makeText(this, "Holiday created!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, DashboardFragment::class.java))// go back
            }
        }

        // 🔹 Reset button
        resetBtn.setOnClickListener {
            titleEt.text.clear()
            timeStartEt.text.clear()
            timeEndEt.text.clear()
            dateEt.text.clear()
            descEt.text.clear()
            repeatsSpinner.setSelection(0)
        }

        // 🔹 Cancel button
        cancelBtn.setOnClickListener {
            finish()
        }
    }

    // 🔹 Load user calendars for spinner (just names)
    private fun loadUserCalendars(userId: String) {
        db.child("user_calendars").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    calendarList.clear()
                    val ids = snapshot.children.mapNotNull { it.key }
                    ids.forEach { id ->
                        db.child("calendars").child(id)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(calSnap: DataSnapshot) {
                                    val calendar = calSnap.getValue(CalendarModel::class.java)
                                    if (calendar != null) {
                                        calendarList.add(calendar)
                                        updateCalendarSpinner()
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {}
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // 🔹 Update calendar spinner
    private fun updateCalendarSpinner() {
        val calendarNames = calendarList.map { it.title }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            calendarNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        calendarSpinner.adapter = adapter
    }
}
