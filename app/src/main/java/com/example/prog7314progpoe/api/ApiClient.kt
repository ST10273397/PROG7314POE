package com.example.prog7314progpoe.api

import com.example.prog7314progpoe.api.CalendarificApi
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private const val BASE_URL = "https://calendarific.com/api/v2/"

    val api: CalendarificApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CalendarificApi::class.java)
    }
}