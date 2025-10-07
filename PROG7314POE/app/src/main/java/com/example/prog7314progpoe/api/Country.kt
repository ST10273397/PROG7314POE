package com.example.prog7314progpoe.api

import com.google.gson.annotations.SerializedName

data class CountryResponse(
    val response: CountryList
)

data class CountryList(
    val countries: List<Country>
)

data class Country(
    val country_name: String,
    @SerializedName("iso-3166") val isoCode: String
)