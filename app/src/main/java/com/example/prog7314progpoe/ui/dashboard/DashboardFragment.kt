package com.example.prog7314progpoe.ui.dashboard

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// use the top level ui model
import com.example.prog7314progpoe.ui.dashboard.SlotUiModel

class DashboardFragment : Fragment() {

    //CLOCK AND SLOTS - basic setup for time and grid
    //-----------------------------------------------------------------------------------------------
    private val apiKey = "ZRjKfqyaZbAy9ZaKFHdmudPaFuN2hEPI" // same key as before
    private val userZone: ZoneId = ZoneId.systemDefault() // timezone to format the clock

    private lateinit var timeText: TextView // time header
    private lateinit var recycler: RecyclerView // grid list
    private lateinit var swipe: SwipeRefreshLayout // pull to refresh

    private lateinit var adapter: DashboardSlotsAdapter // adapter for slot items

    private val timeHandler = Handler(Looper.getMainLooper()) // handler for clock ticks
    private val timeUpdater = object : Runnable {
        override fun run() {
            updateTimeNow() // refresh the time text
            timeHandler.postDelayed(this, 60_000L) // tick every minute no rush
        }
    }

    private val uiScope = CoroutineScope(Dispatchers.Main + Job()) // scope for ui tasks

    private var countries: List<Country> = emptyList() // cache country list for picker
    //-----------------------------------------------------------------------------------------------

    //ASSIGNMENT MODEL - simple holder for what a slot points to
    //-----------------------------------------------------------------------------------------------
    private enum class CalType { PUBLIC, CUSTOM } // two kinds for now

    private data class SlotAssignment(
        val type: CalType,     // which type it is
        val id: String,        // PUBLIC: iso code  CUSTOM: name placeholder
        val displayName: String // what to show on the card
    )
    //-----------------------------------------------------------------------------------------------

    //PREFERENCES - tiny storage to remember slot choices
    //-----------------------------------------------------------------------------------------------
    private val prefs by lazy {
        requireContext().getSharedPreferences("dashboard_slots", Context.MODE_PRIVATE) // app prefs
    }

    private fun saveSlot(index: Int, slot: SlotAssignment?) {
        val e = prefs.edit()
        if (slot == null) {
            e.remove("type_$index") // remove type
            e.remove("id_$index") // remove id
            e.remove("name_$index") // remove name
        } else {
            e.putString("type_$index", slot.type.name) // save type
            e.putString("id_$index", slot.id) // save id
            e.putString("name_$index", slot.displayName) // save display name
        }
        e.apply() // commit changes
    }

    private fun loadSlot(index: Int): SlotAssignment? {
        val type = prefs.getString("type_$index", null) ?: return null // no type then empty
        val id = prefs.getString("id_$index", null) ?: return null // no id then empty
        val name = prefs.getString("name_$index", null) ?: return null // no name then empty
        return SlotAssignment(CalType.valueOf(type), id, name) // recreate object
    }
    //-----------------------------------------------------------------------------------------------

    //LIFECYCLE - view creation and setup
    //-----------------------------------------------------------------------------------------------
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_dashboard, container, false) // inflate layout
        timeText = v.findViewById(R.id.txtTime) // bind time view
        recycler = v.findViewById(R.id.recyclerSlots) // bind grid
        swipe = v.findViewById(R.id.swipe) // bind swipe
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        //GRID - 2 columns 8 items total
        //-------------------------------------------------
        adapter = DashboardSlotsAdapter { index -> onSlotClicked(index) } // click handler
        recycler.layoutManager = GridLayoutManager(requireContext(), 2) // 2 columns
        recycler.adapter = adapter // set adapter
        recycler.addItemDecoration(GridSpacingDecoration(dpToPx(8))) // even gaps
        recycler.setHasFixedSize(true) // slots are fixed height so ok
        //-------------------------------------------------

        //CLOCK - start the live clock
        //-------------------------------------------------
        updateTimeNow() // initial paint
        timeHandler.removeCallbacks(timeUpdater) // avoid dup
        timeHandler.post(timeUpdater) // start ticking
        //-------------------------------------------------

        //DATA - render 8 slots from prefs and fetch next events
        //-------------------------------------------------
        renderFromPrefs() // initial list
        swipe.setOnRefreshListener { refreshAllSlots() } // pull to refresh
        //-------------------------------------------------
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timeHandler.removeCallbacks(timeUpdater) // stop clock when view is gone
    }
    //-----------------------------------------------------------------------------------------------

    //CLOCK TEXT - format like 14:32 — Tue, 07 Oct
    //-----------------------------------------------------------------------------------------------
    private fun updateTimeNow() {
        val now: ZonedDateTime = ZonedDateTime.now(userZone) // get now
        val text = now.format(DateTimeFormatter.ofPattern("HH:mm — EEE, dd MMM")) // format
        timeText.text = text // set it
    }
    //-----------------------------------------------------------------------------------------------

    //RENDER - create list of 8 items and then refresh content
    //-----------------------------------------------------------------------------------------------
    private fun renderFromPrefs() {
        val list = MutableList<SlotUiModel>(8) { i -> // always 8 slots
            val slot = loadSlot(i) // read from prefs
            if (slot == null) {
                SlotUiModel.Unassigned(i) // empty tile
            } else {
                SlotUiModel.Populated(i, slot.displayName, "…") // show loading dots no words
            }
        }
        adapter.submitList(list) // push list to adapter
        refreshAllSlots() // then fetch next events
    }
    //-----------------------------------------------------------------------------------------------

    //REFRESH ALL - compute next event text per slot
    //-----------------------------------------------------------------------------------------------
    private fun refreshAllSlots() {
        uiScope.launch {
            val current = adapter.currentList.toMutableList() // snapshot list
            val today = LocalDate.now(userZone) // todays date
            for (i in 0 until current.size) {
                val assign = loadSlot(i) ?: continue // skip empty
                val nextText = when (assign.type) {
                    CalType.PUBLIC -> fetchNextPublicHoliday(assign.id, today) // from api
                    CalType.CUSTOM -> fetchNextCustomEvent(assign.id, today) // from firebase
                }
                current[i] = SlotUiModel.Populated(i, assign.displayName, nextText) // update tile
            }
            adapter.submitList(current) // apply updates
            swipe.isRefreshing = false // stop spinner
        }
    }
    //-----------------------------------------------------------------------------------------------

    //REFRESH ONE - update a single slot after change
    //-----------------------------------------------------------------------------------------------
    private fun refreshSingleSlot(index: Int) {
        uiScope.launch {
            val assign = loadSlot(index) ?: return@launch // nothing to do
            val today = LocalDate.now(userZone) // date
            val nextText = when (assign.type) {
                CalType.PUBLIC -> fetchNextPublicHoliday(assign.id, today) // api path
                CalType.CUSTOM -> fetchNextCustomEvent(assign.id, today) // custom path
            }
            val list = adapter.currentList.toMutableList() // copy list
            list[index] = SlotUiModel.Populated(index, assign.displayName, nextText) // put new text
            adapter.submitList(list) // post
        }
    }
    //-----------------------------------------------------------------------------------------------

    //SLOT CLICK - choose source or clear
    //-----------------------------------------------------------------------------------------------
    private fun onSlotClicked(index: Int) {
        val existing = loadSlot(index) // check if assigned
        val options = if (existing == null)
            arrayOf(getString(R.string.picker_public_holidays), getString(R.string.picker_custom_calendar)) // two choices
        else
            arrayOf(getString(R.string.picker_public_holidays), getString(R.string.picker_custom_calendar), "Clear") // add clear

        AlertDialog.Builder(requireContext())
            .setTitle("Choose source") // header
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.picker_public_holidays) -> pickCountryAndAssign(index) // pick a country
                    getString(R.string.picker_custom_calendar) -> promptCustomListAndAssign(index) // pick from lists
                    "Clear" -> {
                        saveSlot(index, null) // wipe prefs
                        val list = adapter.currentList.toMutableList() // copy
                        list[index] = SlotUiModel.Unassigned(index) // set empty
                        adapter.submitList(list) // post
                    }
                }
            }
            .show()
    }
    //-----------------------------------------------------------------------------------------------

    //PUBLIC HOLIDAYS - fetch countries then show searchable picker
    //-----------------------------------------------------------------------------------------------
    private fun pickCountryAndAssign(index: Int) {
        if (countries.isEmpty()) {
            ApiClient.api.getLocations(apiKey).enqueue(object : Callback<CountryResponse> {
                override fun onResponse(call: Call<CountryResponse>, response: Response<CountryResponse>) {
                    if (response.isSuccessful) {
                        countries = response.body()?.response?.countries ?: emptyList() // cache
                        showCountryDialog(index) // open picker
                    } else {
                        Toast.makeText(requireContext(), "Failed to load countries", Toast.LENGTH_SHORT).show() // error toast
                    }
                }
                override fun onFailure(call: Call<CountryResponse>, t: Throwable) {
                    Toast.makeText(requireContext(), "Network error loading countries", Toast.LENGTH_SHORT).show() // network fail
                }
            })
        } else {
            showCountryDialog(index) // already cached
        }
    }

    //COUNTRY PICKER - search and choose
    //-------------------------------------------------
    private fun showCountryDialog(index: Int) {
        if (countries.isEmpty()) {
            Toast.makeText(requireContext(), "No countries available", Toast.LENGTH_SHORT).show() // no data
            return
        }
        val names = countries.map { it.country_name } // list of names

        showSearchableListDialog(
            title = "Pick country",
            items = names,
            emptyHint = "No countries available"
        ) { chosenName ->
            if (chosenName == null) return@showSearchableListDialog // canceled
            val pos = names.indexOf(chosenName) // find selection
            if (pos < 0) return@showSearchableListDialog // not found
            val country = countries[pos] // country obj
            val assign = SlotAssignment(
                type = CalType.PUBLIC, // type public
                id = country.isoCode,  // keep iso code for api
                displayName = country.country_name // show only the country name
            )
            saveSlot(index, assign) // persist
            val list = adapter.currentList.toMutableList() // copy current
            list[index] = SlotUiModel.Populated(index, assign.displayName, "…") // show dots until loaded
            adapter.submitList(list) // post
            refreshSingleSlot(index) // compute next event
        }
    }
    //-------------------------------------------------
    //-----------------------------------------------------------------------------------------------

    //API NEXT HOLIDAY - compute the next date from this year and next
    //-----------------------------------------------------------------------------------------------
    private suspend fun fetchNextPublicHoliday(countryIso: String, today: LocalDate): String {
        val fmtOut = DateTimeFormatter.ofPattern("EEE, dd MMM") // nice format
        val years = listOf(today.year, today.year + 1) // cover year boundary

        return withContext(Dispatchers.IO) {
            val all = mutableListOf<Pair<String, LocalDate>>() // title date pairs
            for (y in years) {
                try {
                    val resp = ApiClient.api.getHolidays(apiKey, countryIso, y) // call api
                    val holidays = resp.response.holidays ?: emptyList() // safe
                    for (h in holidays) {
                        val iso = h.date?.iso ?: continue // skip if missing
                        val title = h.name ?: continue // skip if missing
                        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: continue // parse date
                        all += title to d // collect
                    }
                } catch (_: Exception) {
                    // ignore failed year
                }
            }
            val next = all.filter { it.second >= today }.minByOrNull { it.second } // pick soonest
            if (next == null) getString(R.string.no_upcoming) // nothing found
            else "${next.first} — ${next.second.format(fmtOut)}" // no Next prefix
        }
    }
    //-----------------------------------------------------------------------------------------------

    //CUSTOM NEXT EVENT - derive next from firebase list
    //-----------------------------------------------------------------------------------------------
    private suspend fun fetchNextCustomEvent(calendarId: String, today: LocalDate): String {
        return withContext(Dispatchers.IO) {
            var result: String = getString(R.string.no_upcoming) // default if none
            val latch = CompletableDeferred<Unit>() // simple bridge for callback
            FirebaseHolidayDbHelper.getAllHolidays(calendarId) { holidays ->
                try {
                    val mapped = holidays.mapNotNull { h ->
                        // try title then name
                        val title = runCatching {
                            val f = h.javaClass.getDeclaredField("title"); f.isAccessible = true; (f.get(h) as? String) ?: ""
                        }.getOrElse {
                            runCatching {
                                val f = h.javaClass.getDeclaredField("name"); f.isAccessible = true; (f.get(h) as? String) ?: ""
                            }.getOrDefault("")
                        }
                        // try date.iso then date then day
                        val dateStr = runCatching {
                            val df = h.javaClass.getDeclaredField("date"); df.isAccessible = true; val obj = df.get(h)
                            when (obj) {
                                is String -> obj // direct string
                                else -> {
                                    val isoF = obj?.javaClass?.getDeclaredField("iso") // nested iso
                                    isoF?.isAccessible = true
                                    (isoF?.get(obj) as? String) ?: ""
                                }
                            }
                        }.getOrElse {
                            runCatching {
                                val f = h.javaClass.getDeclaredField("day"); f.isAccessible = true; (f.get(h) as? String) ?: ""
                            }.getOrDefault("")
                        }
                        if (title.isBlank() || dateStr.isBlank()) null // skip broken rows
                        else {
                            val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@mapNotNull null // parse
                            title to d // pair
                        }
                    }
                    val next = mapped.filter { it.second >= today }.minByOrNull { it.second } // choose soonest
                    result = if (next == null) getString(R.string.no_upcoming) // none found
                    else "${next.first} — ${next.second.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))}" // no Next prefix
                } catch (_: Exception) {
                    result = getString(R.string.no_upcoming) // fallback
                } finally {
                    latch.complete(Unit) // release
                }
            }
            latch.await() // wait for callback
            result // return
        }
    }
    //-----------------------------------------------------------------------------------------------

    //CUSTOM PICKER - show searchable list for mine and shared
    //-----------------------------------------------------------------------------------------------
    private fun promptCustomListAndAssign(index: Int) {
        val myCalendars = getMyCustomCalendars() // my own
        val sharedCalendars = getSharedWithMeCalendars() // shared to me
        val all = (myCalendars + sharedCalendars).sorted() // merge
        showSearchableListDialog(
            title = "Choose custom calendar", // dialog title
            items = all, // items to show
            emptyHint = "No custom calendars yet" // empty state
        ) { chosenName ->
            if (chosenName == null) return@showSearchableListDialog // canceled
            val assign = SlotAssignment(
                type = CalType.CUSTOM, // custom type
                id = chosenName, // placeholder id same as name
                displayName = chosenName // card label
            )
            saveSlot(index, assign) // persist
            val list = adapter.currentList.toMutableList() // copy
            list[index] = SlotUiModel.Populated(index, assign.displayName, "…") // loading dots
            adapter.submitList(list) // post
            refreshSingleSlot(index) // fetch next
        }
    }

    //MY LISTS - placeholder funcs to be replaced later
    //-------------------------------------------------
    private fun getMyCustomCalendars(): List<String> {
        return emptyList() // later hook to firebase
    }
    private fun getSharedWithMeCalendars(): List<String> {
        return emptyList() // later hook to firebase
    }
    //-------------------------------------------------
    //-----------------------------------------------------------------------------------------------

    //SEARCHABLE DIALOG - simple dialog with search field and list
    //-----------------------------------------------------------------------------------------------
    private fun showSearchableListDialog(
        title: String,
        items: List<String>,
        emptyHint: String,
        onChoose: (String?) -> Unit
    ) {
        val ctx = requireContext() // context

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL // vertical stack
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), 0) // padding
        }

        val search = EditText(ctx).apply {
            hint = "Search…" // hint text
            inputType = InputType.TYPE_CLASS_TEXT // plain input
        }

        val emptyView = TextView(ctx).apply {
            text = emptyHint // empty message
            setPadding(0, dpToPx(24), 0, dpToPx(24)) // spacing
            gravity = Gravity.CENTER // center it
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE // toggle
        }

        val listView = ListView(ctx) // list control
        val data = items.toMutableList() // data source
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, data) // basic adapter
        listView.adapter = adapter // connect list

        //FILTER - updates the list as you type
        //-------------------------------------------------
        val watcher: TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty() // query
                val filtered = if (q.isEmpty()) items else items.filter { it.lowercase().contains(q) } // filter
                adapter.clear() // clear old
                adapter.addAll(filtered) // add new
                adapter.notifyDataSetChanged() // refresh
                emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE // toggle empty view
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        search.addTextChangedListener(watcher) // attach watcher
        //-------------------------------------------------

        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) // add search
        container.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) // add empty view
        container.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(400))) // add list

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title) // set title
            .setView(container) // set custom content
            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); onChoose(null) } // cancel action
            .create() // build dialog

        listView.setOnItemClickListener { _, _, pos, _ ->
            val chosen = adapter.getItem(pos) // get selection
            dialog.dismiss() // close dialog
            onChoose(chosen) // return choice
        }

        dialog.show() // show it
    }
    //-----------------------------------------------------------------------------------------------

    //HELPERS - dp to px and spacing for grid
    //-----------------------------------------------------------------------------------------------
    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density // density
        return (dp * density + 0.5f).toInt() // convert
    }

    private class GridSpacingDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.set(space, space, space, space) // even padding around each cell
        }
    }
    //-----------------------------------------------------------------------------------------------
}
