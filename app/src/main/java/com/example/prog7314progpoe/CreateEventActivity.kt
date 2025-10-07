package com.example.prog7314progpoe

import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class CreateEventActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_event)

        val calendarAssigned = findViewById<Spinner>(R.id.spn_calendar)
        val title = findViewById<EditText>(R.id.et_EventTitle)
        val timeStart = findViewById<EditText>(R.id.et_StartTime)
        val timeEnd = findViewById<EditText>(R.id.et_EndTime)
        val date = findViewById<EditText>(R.id.et_Date)
        val desc = findViewById<EditText>(R.id.et_Desc)
        val repeats = findViewById<Spinner>(R.id.spn_Repeats)

    }
}