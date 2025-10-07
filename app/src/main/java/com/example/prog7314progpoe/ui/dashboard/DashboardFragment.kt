package com.example.prog7314progpoe.ui.dashboard

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.prog7314progpoe.R
import com.example.prog7314progpoe.api.ApiClient
import com.example.prog7314progpoe.api.Country
import com.example.prog7314progpoe.api.CountryResponse
import com.example.prog7314progpoe.database.holidays.FirebaseHolidayDbHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ✅ Use the TOP-LEVEL model you already created in SlotUiModel.kt
import com.example.prog7314progpoe.ui.dashboard.SlotUiModel

/**
 * Minimal, functional dashboard:
 * - 6 slots
 * - tap to assign Public Holidays (country) or Custom (calendarId)
 * - shows calendar name + next upcoming event
 * - saves choices in SharedPreferences
 */
class DashboardFragment : Fragment() {

    // === YOUR SAME API KEY (used in old CalendarActivity) ===
    private val apiKey = "ZRjKfqyaZbAy9ZaKFHdmudPaFuN2hEPI"
    private val userZone: ZoneId = ZoneId.systemDefault()

    private lateinit var recycler: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: DashboardSlotsAdapter

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    // in-memory cache of countries to avoid refetch
    private var countries: List<Country> = emptyList()

    // --- keep it simple: tiny types inside this file ---
    private enum class CalType { PUBLIC, CUSTOM }

    private data class SlotAssignment(
        val type: CalType,
        val id: String,                // PUBLIC: country ISO code; CUSTOM: calendarId
        val displayName: String        // e.g., "Public Holidays — South Africa" or "Custom: Work"
    )

    // === PREFERENCES (simple persistence) ===
    private val prefs by lazy {
        requireContext().getSharedPreferences("dashboard_slots", Context.MODE_PRIVATE)
    }

    private fun saveSlot(index: Int, slot: SlotAssignment?) {
        val e = prefs.edit()
        if (slot == null) {
            e.remove("type_$index")
            e.remove("id_$index")
            e.remove("name_$index")
        } else {
            e.putString("type_$index", slot.type.name)
            e.putString("id_$index", slot.id)
            e.putString("name_$index", slot.displayName)
        }
        e.apply()
    }

    private fun loadSlot(index: Int): SlotAssignment? {
        val type = prefs.getString("type_$index", null) ?: return null
        val id = prefs.getString("id_$index", null) ?: return null
        val name = prefs.getString("name_$index", null) ?: return null
        return SlotAssignment(CalType.valueOf(type), id, name)
    }

    // === LIFECYCLE ===
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_dashboard, container, false)
        recycler = v.findViewById(R.id.recyclerSlots)
        swipe = v.findViewById(R.id.swipe)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DashboardSlotsAdapter { index -> onSlotClicked(index) }
        recycler.layoutManager = GridLayoutManager(requireContext(), 2) // 2×3 grid
        recycler.adapter = adapter

        renderFromPrefs()

        swipe.setOnRefreshListener {
            refreshAllSlots()
        }
    }

    private fun renderFromPrefs() {
        val list = MutableList<SlotUiModel>(6) { i ->
            val slot = loadSlot(i)
            if (slot == null) {
                SlotUiModel.Unassigned(i)
            } else {
                // temporary "loading" text until we fetch next event
                SlotUiModel.Populated(i, slot.displayName, getString(R.string.next_event_prefix) + " …")
            }
        }
        adapter.submitList(list)
        // kick off background refresh of next events for populated slots
        refreshAllSlots()
    }

    private fun refreshAllSlots() {
        uiScope.launch {
            val currentList = adapter.currentList.toMutableList() // MutableList<SlotUiModel>
            val today = LocalDate.now(userZone)
            for (i in 0 until currentList.size) {
                val assign = loadSlot(i) ?: continue
                val nextText = when (assign.type) {
                    CalType.PUBLIC -> fetchNextPublicHoliday(assign.id, today)
                    CalType.CUSTOM -> fetchNextCustomEvent(assign.id, today) // placeholder refined below
                }
                currentList[i] = SlotUiModel.Populated(i, assign.displayName, nextText)
            }
            adapter.submitList(currentList)
            swipe.isRefreshing = false
        }
    }

    // === SLOT INTERACTION ===
    private fun onSlotClicked(index: Int) {
        val existing = loadSlot(index)
        // If assigned, offer Change / Clear; if empty, offer Assign
        val options = if (existing == null)
            arrayOf(getString(R.string.picker_public_holidays), getString(R.string.picker_custom_calendar))
        else
            arrayOf(getString(R.string.picker_public_holidays), getString(R.string.picker_custom_calendar), "Clear")

        AlertDialog.Builder(requireContext())
            .setTitle("Choose source")
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.picker_public_holidays) -> pickCountryAndAssign(index)
                    getString(R.string.picker_custom_calendar) -> promptCustomIdAndAssign(index)
                    "Clear" -> {
                        saveSlot(index, null)
                        val list = adapter.currentList.toMutableList()
                        list[index] = SlotUiModel.Unassigned(index)
                        adapter.submitList(list)
                    }
                }
            }
            .show()
    }

    // === PUBLIC HOLIDAYS FLOW ===
    private fun pickCountryAndAssign(index: Int) {
        if (countries.isEmpty()) {
            // fetch once
            ApiClient.api.getLocations(apiKey).enqueue(object: Callback<CountryResponse> {
                override fun onResponse(call: Call<CountryResponse>, response: Response<CountryResponse>) {
                    if (response.isSuccessful) {
                        countries = response.body()?.response?.countries ?: emptyList()
                        showCountryDialog(index)
                    } else {
                        Toast.makeText(requireContext(), "Failed to load countries", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<CountryResponse>, t: Throwable) {
                    Toast.makeText(requireContext(), "Network error loading countries", Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            showCountryDialog(index)
        }
    }

    private fun showCountryDialog(index: Int) {
        if (countries.isEmpty()) {
            Toast.makeText(requireContext(), "No countries available", Toast.LENGTH_SHORT).show()
            return
        }
        val names = countries.map { it.country_name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Pick country")
            .setItems(names) { _, pos ->
                val country = countries[pos]
                val assign = SlotAssignment(
                    type = CalType.PUBLIC,
                    id = country.isoCode, // store ISO code
                    displayName = "Public Holidays — ${country.country_name}"
                )
                saveSlot(index, assign)
                // update UI immediately, then fetch next event
                val list = adapter.currentList.toMutableList()
                list[index] = SlotUiModel.Populated(index, assign.displayName, getString(R.string.next_event_prefix) + " …")
                adapter.submitList(list)
                refreshSingleSlot(index)
            }
            .show()
    }

    private fun refreshSingleSlot(index: Int) {
        uiScope.launch {
            val assign = loadSlot(index) ?: return@launch
            val today = LocalDate.now(userZone)
            val nextText = when (assign.type) {
                CalType.PUBLIC -> fetchNextPublicHoliday(assign.id, today)
                CalType.CUSTOM -> fetchNextCustomEvent(assign.id, today)
            }
            val list = adapter.currentList.toMutableList()
            list[index] = SlotUiModel.Populated(index, assign.displayName, nextText)
            adapter.submitList(list)
        }
    }

    private suspend fun fetchNextPublicHoliday(countryIso: String, today: LocalDate): String {
        // Check current year and next year to handle end-of-year
        val fmtOut = DateTimeFormatter.ofPattern("EEE, dd MMM")
        val years = listOf(today.year, today.year + 1)

        return withContext(Dispatchers.IO) {
            val all = mutableListOf<Pair<String, LocalDate>>() // (title, date)
            for (y in years) {
                try {
                    val resp = ApiClient.api.getHolidays(apiKey, countryIso, y)
                    val holidays = resp.response.holidays ?: emptyList()
                    for (h in holidays) {
                        val iso = h.date?.iso ?: continue // e.g. "2025-09-24"
                        val title = h.name ?: continue
                        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: continue
                        all += title to d
                    }
                } catch (_: Exception) {
                    // ignore and continue
                }
            }
            val next = all
                .filter { it.second >= today }
                .minByOrNull { it.second }

            if (next == null) {
                getString(R.string.no_upcoming)
            } else {
                "${getString(R.string.next_event_prefix)} ${next.first} — ${next.second.format(fmtOut)}"
            }
        }
    }

    // === CUSTOM CALENDAR FLOW ===
    private fun promptCustomIdAndAssign(index: Int) {
        val input = EditText(requireContext())
        input.hint = "Enter calendarId"
        input.inputType = InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(requireContext())
            .setTitle("Custom Calendar ID")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val id = input.text?.toString()?.trim().orEmpty()
                if (id.isEmpty()) {
                    Toast.makeText(requireContext(), "Calendar ID required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val assign = SlotAssignment(CalType.CUSTOM, id, "Custom: $id")
                saveSlot(index, assign)
                val list = adapter.currentList.toMutableList()
                list[index] = SlotUiModel.Populated(index, assign.displayName, getString(R.string.next_event_prefix) + " …")
                adapter.submitList(list)
                refreshSingleSlot(index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun fetchNextCustomEvent(calendarId: String, today: LocalDate): String {
        // We call your Firebase helper and try to compute a next date.
        // If you share the exact HolidayModel field names for date/title, I’ll make this exact.
        return withContext(Dispatchers.IO) {
            var result: String = getString(R.string.no_upcoming)
            val latch = CompletableDeferred<Unit>()
            FirebaseHolidayDbHelper.getAllHolidays(calendarId) { holidays ->
                try {
                    val mapped = holidays.mapNotNull { h ->
                        // Try common field names: title/name + date/day (ISO)
                        val title = runCatching {
                            val f = h.javaClass.getDeclaredField("title"); f.isAccessible = true; (f.get(h) as? String) ?: ""
                        }.getOrElse {
                            runCatching {
                                val f = h.javaClass.getDeclaredField("name"); f.isAccessible = true; (f.get(h) as? String) ?: ""
                            }.getOrDefault("")
                        }

                        val dateStr = runCatching {
                            val f = h.javaClass.getDeclaredField("date"); f.isAccessible = true; (f.get(h) as? String) ?: ""
                        }.getOrElse {
                            runCatching {
                                val f = h.javaClass.getDeclaredField("day"); f.isAccessible = true; (f.get(h) as? String) ?: ""
                            }.getOrDefault("")
                        }

                        if (title.isBlank() || dateStr.isBlank()) null else {
                            val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@mapNotNull null
                            title to d
                        }
                    }

                    val next = mapped.filter { it.second >= today }.minByOrNull { it.second }
                    result = if (next == null) getString(R.string.no_upcoming)
                    else "${getString(R.string.next_event_prefix)} ${next.first} — ${next.second.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))}"
                } catch (_: Exception) {
                    result = getString(R.string.no_upcoming)
                } finally {
                    latch.complete(Unit)
                }
            }
            latch.await()
            result
        }
    }
}
