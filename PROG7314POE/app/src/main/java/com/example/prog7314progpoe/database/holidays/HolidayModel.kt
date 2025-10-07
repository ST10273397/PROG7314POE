package com.example.prog7314progpoe.database.holidays

data class HolidayResponse(
    val response: HolidayList
)

data class HolidayList(
    val holidays: List<HolidayModel>? = emptyList()
)

data class HolidayModel(
    var holidayId: String?, //Mapping to the firebase key
    val name: String,
    val desc: String,
    val date: DateInfo?,
    val dateStart: DateInfo? = null,  //Optional if holiday extends past initial day
    val dateEnd: DateInfo? = null,
    val timeStart: Long? = null,
    val timeEnd: Long? = null,
    val repeat: List<String>? = listOf("Daily", "Weekly", "Monthly", "Annually"),
    val type: List<String>? = null // Example: ["National holiday", "Religious"]
    ){
    constructor() : this(" ","", "",
        null, null, null,
        0L, 0L, null, null)

    data class DateInfo(
        val iso: String // e.g., "2025-01-01"
    )
}