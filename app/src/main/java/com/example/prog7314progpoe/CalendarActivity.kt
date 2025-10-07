package com.example.prog7314progpoe

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314progpoe.api.ApiClient
import com.example.prog7314progpoe.api.Country
import com.example.prog7314progpoe.api.CountryResponse
import com.example.prog7314progpoe.database.holidays.FirebaseHolidayDbHelper
import com.example.prog7314progpoe.database.holidays.HolidayAdapter
import com.example.prog7314progpoe.database.holidays.HolidayModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.collections.emptyList

class CalendarActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var calendarTypeSpinner: Spinner
    private lateinit var countrySpinner: Spinner

    private val apiKey = "ZRjKfqyaZbAy9ZaKFHdmudPaFuN2hEPI"
    private var countries: List<Country> = emptyList()

    enum class CalendarType { API, CUSTOM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        recyclerView = findViewById(R.id.holidaysRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        calendarTypeSpinner = findViewById(R.id.calendarTypeSpinner)
        countrySpinner = findViewById(R.id.countrySpinner)

        setupCalendarTypeSpinner()
        fetchCountries()
    }

    /** Setup spinner to select between API or Custom calendar */
    private fun setupCalendarTypeSpinner() {
        val types = CalendarType.values().map { it.name }
        calendarTypeSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, types)

        calendarTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val type = CalendarType.values()[position]
                when (type) {
                    CalendarType.API -> countrySpinner.visibility = View.VISIBLE
                    CalendarType.CUSTOM -> countrySpinner.visibility = View.GONE
                }

                // Optionally load default calendar
                if (type == CalendarType.CUSTOM) {
                    val customCalendarId = "firebase_calendar_id" // Replace with actual ID
                    fetchHolidays(CalendarType.CUSTOM, calendarId = customCalendarId)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Fetch list of countries from API for premade calendars */
    private fun fetchCountries() {
        val call = ApiClient.api.getLocations(apiKey)
        call.enqueue(object : Callback<CountryResponse> {
            override fun onResponse(call: Call<CountryResponse>, response: Response<CountryResponse>) {
                if (response.isSuccessful) {
                    countries = response.body()?.response?.countries ?: emptyList()
                    showCountriesInSpinner(countries)
                }
            }

            override fun onFailure(call: Call<CountryResponse>, t: Throwable) {
                t.printStackTrace()
            }
        })
    }

    /** Populate the country spinner */
    private fun showCountriesInSpinner(countries: List<Country>) {
        val countryNames = countries.map { it.country_name }
        countrySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, countryNames)
        (countrySpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        countrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val selectedCountry = countries[position]
                fetchHolidays(CalendarType.API, country = selectedCountry.isoCode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Fetch holidays from API or Firebase depending on the calendar type
     */
    private fun fetchHolidays(
        calendarType: CalendarType,
        calendarId: String? = null,
        country: String? = null,
        year: Int = 2025,
        month: Int? = null
    ) {
        when (calendarType) {
            CalendarType.API -> {
                if (country == null) return
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val response = ApiClient.api.getHolidays(apiKey, country, 2025)
                        println("API RAW RESPONSE: $response") // <-- Add this
                        val holidays = response.response.holidays ?: emptyList()
                        withContext(Dispatchers.Main) {
                            recyclerView.adapter = HolidayAdapter(holidays)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            CalendarType.CUSTOM -> {
                if (calendarId == null) return
                FirebaseHolidayDbHelper.getAllHolidays(calendarId) { holidays ->
                    recyclerView.adapter = HolidayAdapter(holidays)
                }
            }
        }
    }
}
