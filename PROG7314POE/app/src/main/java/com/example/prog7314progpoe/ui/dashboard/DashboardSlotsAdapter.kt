package com.example.prog7314progpoe.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314progpoe.R

class DashboardSlotsAdapter(
    private val onSlotClick: (index: Int) -> Unit
) : ListAdapter<SlotUiModel, DashboardSlotsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_slot_card, parent, false)
        return VH(v, onSlotClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(itemView: View, onClick: (Int) -> Unit): RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.txtCalendarName)
        private val next: TextView = itemView.findViewById(R.id.txtNextEvent)

        init {
            itemView.setOnClickListener { onClick(bindingAdapterPosition) }
        }

        fun bind(model: SlotUiModel) {
            when (model) {
                is SlotUiModel.Unassigned -> {
                    name.setText(R.string.slot_empty_title)
                    next.setText(R.string.slot_empty_subtitle)
                }
                is SlotUiModel.Populated -> {
                    name.text = model.calendarName
                    next.text = model.nextEventText
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SlotUiModel>() {
            override fun areItemsTheSame(old: SlotUiModel, new: SlotUiModel) =
                old.index == new.index
            override fun areContentsTheSame(old: SlotUiModel, new: SlotUiModel) =
                old == new
        }
    }
}
