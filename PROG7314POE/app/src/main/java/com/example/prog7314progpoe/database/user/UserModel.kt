package com.example.prog7314progpoe.database.user

import com.example.prog7314progpoe.database.holidays.HolidayModel.DateInfo

data class UserModel(
    var userId: String = "",
    var email: String = "",
    var firstName: String = "",
    var lastName: String = " ",
    var password: String = " ",
    var dateOfBirth: DateInfo?,
    var location: String = " ",
) {
}