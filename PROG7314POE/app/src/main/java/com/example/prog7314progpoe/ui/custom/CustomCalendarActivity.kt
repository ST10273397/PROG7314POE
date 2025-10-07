package com.example.prog7314progpoe.ui.custom

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import com.example.prog7314progpoe.R
import com.example.prog7314progpoe.database.calendar.FirebaseCalendarDbHelper
import com.example.prog7314progpoe.database.holidays.FirebaseHolidayDbHelper
import com.example.prog7314progpoe.ui.dashboard.DashboardFragment
import com.google.firebase.auth.FirebaseAuth

class CustomCalendarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_calendar)

        val titleEt = findViewById<EditText>(R.id.et_Title)
        val descEt = findViewById<EditText>(R.id.et_desc)
        val updateBtn = findViewById<Button>(R.id.btn_update)
        val cancelBtn = findViewById<Button>(R.id.btn_cancel)


        updateBtn.setOnClickListener {
            val title = titleEt.text.toString().trim()
            val desc = descEt.text.toString().trim()

            val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return@setOnClickListener
            FirebaseCalendarDbHelper.insertCalendar(
                ownerId = currentUserId,
                title = title
            ) { calendarId ->
                if (calendarId != null) {
                    Toast.makeText(this, "Calendar created!", Toast.LENGTH_SHORT).show()

                    val intent = Intent(this, CreateEventActivity::class.java)
                    intent.putExtra("calendarId", calendarId)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Failed to create calendar.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        cancelBtn.setOnClickListener {
            startActivity(Intent(this, DashboardFragment::class.java))
        }

    }
}




