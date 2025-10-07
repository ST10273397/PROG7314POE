package com.example.prog7314progpoe.api

import com.example.prog7314progpoe.database.calendar.CalendarModel
import com.example.prog7314progpoe.database.holidays.HolidayResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface CalendarificApi {
    @GET("holidays")
    suspend fun getHolidays(
        @Query("api_key") apiKey: String,
        @Query("country") country: String,
        @Query("year") year: Int,
        @Query("month") month: Int? = null, // optional
        @Query("day") day: Int? = null,     // optional
        @Query("type") type: String? = null,// optional: "national", "religious", etc.
        @Query("location") location: String? = null // optional: e.g. "us-ny"
    ): HolidayResponse

    @GET("countries")
    fun getLocations(
        @Query("api_key") apiKey: String
    ): Call<CountryResponse>
}