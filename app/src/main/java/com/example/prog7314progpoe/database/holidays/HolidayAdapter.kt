package com.example.prog7314progpoe.database.holidays

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314progpoe.R

class HolidayAdapter(private val holidays: List<HolidayModel>) :
    RecyclerView.Adapter<HolidayAdapter.HolidayViewHolder>() {
    init {
        println("HOLIDAYS SIZE: ${holidays.size}")
    }

    class HolidayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val holidayName: TextView = itemView.findViewById(R.id.holidayName)
        val holidayDate: TextView = itemView.findViewById(R.id.holidayDate)
        val holidayType: TextView = itemView.findViewById(R.id.holidayType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HolidayViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.holiday_item, parent, false)
        return HolidayViewHolder(view)
    }

    override fun onBindViewHolder(holder: HolidayViewHolder, position: Int) {
        val holiday = holidays?.get(position)
        holder.holidayName.text = holiday?.name
        holder.holidayDate.text = holiday?.date?.iso
        holder.holidayType.text = holiday?.type?.joinToString(", ")
    }

    override fun getItemCount(): Int = (holidays?.size?: Int) as Int
}