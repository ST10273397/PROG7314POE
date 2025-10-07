package com.example.prog7314progpoe.ui.placeholders

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314progpoe.R
import com.example.prog7314progpoe.api.ApiClient
import com.example.prog7314progpoe.api.Country
import com.example.prog7314progpoe.api.CountryResponse
import com.example.prog7314progpoe.database.holidays.FirebaseHolidayDbHelper
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.*
import java.time.format.DateTimeFormatter

//CALENDAR FRAGMENT - monthly view with source filters and day detail
//-----------------------------------------------------------------------------------------------
class CalendarsFragment : Fragment() {

    //STATE - month and selected sources
    //-----------------------------------------------------------------------------------------------
    private var currentMonth: YearMonth = YearMonth.now() // which month we show
    private var selectedDay: LocalDate = LocalDate.now() // selected day for the card

    // sources
    private val activeCountryIsos = mutableSetOf<String>() // public holiday countries
    private val activeCustomIds = mutableSetOf<String>() // custom calendar ids or names

    // data caches
    private var countries: List<Country> = emptyList() // cache countries list
    private val monthEvents = mutableMapOf<LocalDate, MutableList<EventItem>>() // events per day
    //-----------------------------------------------------------------------------------------------

    //UI - views
    //-----------------------------------------------------------------------------------------------
    private lateinit var txtMonth: TextView // month title
    private lateinit var btnPrev: ImageButton // go to prev month
    private lateinit var btnNext: ImageButton // go to next month
    private lateinit var btnPickSingle: Button // pick a single calendar
    private lateinit var btnPickFromDashboard: Button // pick from dashboard set
    private lateinit var grid: RecyclerView // 7 column grid
    private lateinit var dayCard: LinearLayout // container for day details
    private lateinit var dayCardTitle: TextView // title for selected day
    private lateinit var dayCardList: LinearLayout // list of events for selected day
    private lateinit var emptyDayText: TextView // empty state text
    //-----------------------------------------------------------------------------------------------

    //COROUTINES - scope for background work
    //-----------------------------------------------------------------------------------------------
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob()) // ui scope
    //-----------------------------------------------------------------------------------------------

    //PREFS - reuse dashboard slots so users can quickly select them
    //-----------------------------------------------------------------------------------------------
    private val prefs by lazy {
        requireContext().getSharedPreferences("dashboard_slots", Context.MODE_PRIVATE) // same prefs
    }
    //-----------------------------------------------------------------------------------------------

    //MODELS - simple internal model for day events
    //-----------------------------------------------------------------------------------------------
    private data class EventItem( // tiny holder for an event
        val date: LocalDate, // event day only
        val title: String, // event name
        val sourceLabel: String, // ex South Africa or Custom X
        val sourceKind: SourceKind // public or custom
    )

    private enum class SourceKind { PUBLIC, CUSTOM } // two kinds
    //-----------------------------------------------------------------------------------------------

    //LIFECYCLE - inflate and wire
    //-----------------------------------------------------------------------------------------------
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_calendars_month, container, false) // layout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        //binds
        txtMonth = view.findViewById(R.id.txtMonth) // month text
        btnPrev = view.findViewById(R.id.btnPrev) // left arrow
        btnNext = view.findViewById(R.id.btnNext) // right arrow
        btnPickSingle = view.findViewById(R.id.btnPickSingle) // single picker
        btnPickFromDashboard = view.findViewById(R.id.btnPickFromDashboard) // multi picker
        grid = view.findViewById(R.id.calendarGrid) // grid
        dayCard = view.findViewById(R.id.dayCard) // day card
        dayCardTitle = view.findViewById(R.id.dayCardTitle) // card title
        dayCardList = view.findViewById(R.id.dayCardList) // list container
        emptyDayText = view.findViewById(R.id.emptyDayText) // empty text

        //grid setup
        grid.layoutManager = GridLayoutManager(requireContext(), 7) // 7 days of week
        grid.adapter = DaysAdapter { clicked -> onDayClicked(clicked) } // click handler
        grid.addItemDecoration(EqualGapDecoration(dp(4))) // spacing

        //month nav
        btnPrev.setOnClickListener {
            currentMonth = currentMonth.minusMonths(1) // go back a month
            refreshMonth() // reload
        }
        btnNext.setOnClickListener {
            currentMonth = currentMonth.plusMonths(1) // go forward a month
            refreshMonth() // reload
        }

        //pick one from full list
        btnPickSingle.setOnClickListener { onPickSingleCalendar() } // open list

        //pick many from dashboard
        btnPickFromDashboard.setOnClickListener { onPickFromDashboard() } // choose from slots

        //initial state
        //SUB-SEGMENT - start with todays month and preselect none
        //-------------------------------------------------
        selectedDay = LocalDate.now()
        activeCountryIsos.clear()
        activeCustomIds.clear()
        //-------------------------------------------------

        refreshMonth() // draw month and load events
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.coroutineContext.cancelChildren() // stop work
    }
    //-----------------------------------------------------------------------------------------------

    //ACTIONS - day click updates selection and card
    //-----------------------------------------------------------------------------------------------
    private fun onDayClicked(day: LocalDate) {
        selectedDay = day // set selected day
        renderDayCard() // update card
        grid.adapter?.notifyDataSetChanged() // redraw cells for highlight
    }
    //-----------------------------------------------------------------------------------------------

    //REFRESH MONTH - rebuild grid and reload events for sources
    //-----------------------------------------------------------------------------------------------
    private fun refreshMonth() {
        txtMonth.text = currentMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")) // month label
        renderGrid() // draw dates
        fetchMonthEvents() // load data
        renderDayCard() // build day card
    }
    //-----------------------------------------------------------------------------------------------

    //GRID RENDER - compute the days we show including leading blanks
    //-----------------------------------------------------------------------------------------------
    private fun renderGrid() {
        val firstOfMonth = currentMonth.atDay(1) // first day
        val firstDow = firstOfMonth.dayOfWeek.value % 7 // sunday as 0
        val daysInMonth = currentMonth.lengthOfMonth() // total days

        val cells = mutableListOf<LocalDate>() // visible days
        // leading blanks from previous month
        for (i in 0 until firstDow) {
            cells.add(firstOfMonth.minusDays((firstDow - i).toLong())) // fill prev
        }
        // this month
        for (d in 1..daysInMonth) {
            cells.add(currentMonth.atDay(d)) // add each day
        }
        // trailing to fill full weeks
        while (cells.size % 7 != 0) {
            val last = cells.last()
            cells.add(last.plusDays(1)) // next month
        }

        (grid.adapter as DaysAdapter).submit(cells) // post to adapter
    }
    //-----------------------------------------------------------------------------------------------

    //FETCH EVENTS - populate monthEvents from active sources
    //-----------------------------------------------------------------------------------------------
    private fun fetchMonthEvents() {
        monthEvents.clear() // reset map

        val start = currentMonth.atDay(1) // start day
        val end = currentMonth.atEndOfMonth() // last day
        val yearList = listOf(start.year, start.year + 1) // cover year wrap

        uiScope.launch {
            //PUBLIC HOLIDAYS - for each active iso fetch both years then filter
            //-------------------------------------------------
            for (iso in activeCountryIsos) {
                for (y in yearList) {
                    try {
                        val resp = withContext(Dispatchers.IO) { ApiClient.api.getHolidays(API_KEY, iso, y) }
                        val hols = resp.response.holidays ?: emptyList()
                        hols.forEach { h ->
                            val isoDate = h.date?.iso ?: return@forEach // skip if no date
                            val d = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return@forEach // parse
                            if (!d.isBefore(start) && !d.isAfter(end)) {
                                val title = h.name ?: "Holiday" // title fallback
                                monthEvents.getOrPut(d) { mutableListOf() }
                                    .add(EventItem(d, title, resolveCountryName(iso), SourceKind.PUBLIC)) // add
                            }
                        }
                    } catch (_: Exception) {
                        // ignore fetch issue
                    }
                }
            }
            //-------------------------------------------------

            //CUSTOM CALENDARS - load all and filter by month
            //-------------------------------------------------
            for (cid in activeCustomIds) {
                val latch = CompletableDeferred<Unit>() // wait for callback
                FirebaseHolidayDbHelper.getAllHolidays(cid) { list ->
                    try {
                        list.forEach { h ->
                            val title = tryField(h, "title") ?: tryField(h, "name") ?: "Event" // title
                            val dateStr =
                                tryDateIso(h) ?: tryField(h, "day") // iso or day
                            val d = runCatching { LocalDate.parse(dateStr ?: "") }.getOrNull() // parse or null
                            if (d != null && !d.isBefore(start) && !d.isAfter(end)) {
                                monthEvents.getOrPut(d) { mutableListOf() }
                                    .add(EventItem(d, title, cid, SourceKind.CUSTOM)) // mark
                            }
                        }
                    } catch (_: Exception) { }
                    finally { latch.complete(Unit) }
                }
                latch.await() // wait per calendar
            }
            //-------------------------------------------------

            //after loads update grid and card
            grid.adapter?.notifyDataSetChanged() // redraw dots and tags
            renderDayCard() // refresh details
        }
    }
    //-----------------------------------------------------------------------------------------------

    //DAY CARD - show all events for the selected day
    //-----------------------------------------------------------------------------------------------
    private fun renderDayCard() {
        val items = monthEvents[selectedDay].orEmpty() // events for day
        dayCardTitle.text = selectedDay.format(DateTimeFormatter.ofPattern("EEE, dd MMM")) // pretty date

        dayCardList.removeAllViews() // clear list
        if (items.isEmpty()) {
            emptyDayText.isVisible = true // show empty
        } else {
            emptyDayText.isVisible = false // hide empty
            items.sortedBy { it.title }.forEach { ev ->
                dayCardList.addView(makeEventRow(ev)) // add each
            }
        }
    }
    //-----------------------------------------------------------------------------------------------

    //PICK SINGLE - choose one calendar from the full list then clear others
    //-----------------------------------------------------------------------------------------------
    private fun onPickSingleCalendar() {
        //SUB-SEGMENT - load countries first if needed
        //-------------------------------------------------
        if (countries.isEmpty()) {
            ApiClient.api.getLocations(API_KEY).enqueue(object : Callback<CountryResponse> {
                override fun onResponse(call: Call<CountryResponse>, response: Response<CountryResponse>) {
                    countries = response.body()?.response?.countries ?: emptyList() // cache
                    showSinglePicker() // open
                }
                override fun onFailure(call: Call<CountryResponse>, t: Throwable) {
                    countries = emptyList() // keep empty
                    showSinglePicker() // still open with customs only
                }
            })
        } else {
            showSinglePicker() // already loaded
        }
        //-------------------------------------------------
    }

    private fun showSinglePicker() {
        val countryNames = countries.map { it.country_name } // display
        val countryIsos = countries.map { it.isoCode } // ids

        val customs = getAllCustomNames() // from backend later

        val all = mutableListOf<String>() // merged list
        val types = mutableListOf<SourceKind>() // parallel types
        val ids = mutableListOf<String>() // parallel ids

        countryNames.forEachIndexed { i, name ->
            all += name
            types += SourceKind.PUBLIC
            ids += countryIsos[i]
        }
        customs.forEach { name ->
            all += name
            types += SourceKind.CUSTOM
            ids += name // placeholder id
        }

        showSearchableListDialog(
            title = "Pick calendar",
            items = all,
            emptyHint = "No calendars found"
        ) { chosen ->
            if (chosen == null) return@showSearchableListDialog
            val idx = all.indexOf(chosen)
            if (idx < 0) return@showSearchableListDialog
            activeCountryIsos.clear()
            activeCustomIds.clear()
            when (types[idx]) {
                SourceKind.PUBLIC -> activeCountryIsos += ids[idx]
                SourceKind.CUSTOM -> activeCustomIds += ids[idx]
            }
            refreshMonth() // reload data
        }
    }
    //-----------------------------------------------------------------------------------------------

    //PICK FROM DASHBOARD - choose any number of your dashboard assignments
    //-----------------------------------------------------------------------------------------------
    private fun onPickFromDashboard() {
        val slots = readDashboardSlots() // read saved slots
        if (slots.isEmpty()) {
            Toast.makeText(requireContext(), "No dashboard calendars to choose", Toast.LENGTH_SHORT).show() // message
            return
        }
        val names = slots.map { it.displayName } // show names
        val checked = BooleanArray(slots.size) { i ->
            val s = slots[i]
            (s.kind == SourceKind.PUBLIC && activeCountryIsos.contains(s.id)) ||
                    (s.kind == SourceKind.CUSTOM && activeCustomIds.contains(s.id))
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Pick from dashboard")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked // update temp
            }
            .setPositiveButton("Apply") { d, _ ->
                activeCountryIsos.clear()
                activeCustomIds.clear()
                checked.forEachIndexed { i, flag ->
                    if (flag) {
                        val s = slots[i]
                        if (s.kind == SourceKind.PUBLIC) activeCountryIsos += s.id else activeCustomIds += s.id
                    }
                }
                refreshMonth() // requery month
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    //-----------------------------------------------------------------------------------------------

    //HELPERS - read dashboard slots from prefs
    //-----------------------------------------------------------------------------------------------
    private data class SlotRef(val id: String, val displayName: String, val kind: SourceKind) // slot ref

    private fun readDashboardSlots(): List<SlotRef> {
        val list = mutableListOf<SlotRef>() // output
        for (i in 0 until 8) { // dashboard uses 8
            val type = prefs.getString("type_$i", null) ?: continue
            val id = prefs.getString("id_$i", null) ?: continue
            val name = prefs.getString("name_$i", null) ?: continue
            val kind = if (type == "PUBLIC") SourceKind.PUBLIC else SourceKind.CUSTOM
            list += SlotRef(id, name, kind) // add
        }
        return list // return list
    }
    //-----------------------------------------------------------------------------------------------

    //UI FACTORIES - build an event row for the day card
    //-----------------------------------------------------------------------------------------------
    private fun makeEventRow(ev: EventItem): View {
        val ctx = requireContext() // context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL // row
            setPadding(dp(12), dp(8), dp(12), dp(8)) // padding
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tag = TextView(ctx).apply {
            text = when (ev.sourceKind) {
                SourceKind.PUBLIC -> shortTag(ev.sourceLabel) // two letter for country
                SourceKind.CUSTOM -> "C" // custom tag
            }
            // if the optional bg does not exist just skip it
            val tagBgId = resources.getIdentifier("bg_tag_round", "drawable", requireContext().packageName)
            if (tagBgId != 0) setBackgroundResource(tagBgId) // set badge bg if present
            setPadding(dp(8), dp(4), dp(8), dp(4)) // pad
        }

        val title = TextView(ctx).apply {
            text = "  ${ev.title}" // space then title
            textSize = 16f // size
        }

        row.addView(tag) // add tag
        row.addView(title) // add title
        return row // done
    }
    //-----------------------------------------------------------------------------------------------

    //ADAPTER - month grid 7 columns
    //-----------------------------------------------------------------------------------------------
    private inner class DaysAdapter(val onClick: (LocalDate) -> Unit) :
        RecyclerView.Adapter<DayVH>() {

        private val days = mutableListOf<LocalDate>() // visible days

        fun submit(list: List<LocalDate>) {
            days.clear() // reset
            days.addAll(list) // copy
            notifyDataSetChanged() // redraw
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_day_cell, parent, false) // inflate
            return DayVH(v) // holder
        }

        override fun onBindViewHolder(holder: DayVH, position: Int) {
            holder.bind(days[position]) // bind
        }

        override fun getItemCount(): Int = days.size // size
    }

    private inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txtDay: TextView = v.findViewById(R.id.txtDay) // day number
        private val tagText: TextView = v.findViewById(R.id.txtTag) // tiny tag

        fun bind(day: LocalDate) {
            txtDay.text = day.dayOfMonth.toString() // label

            // highlight today
            val isToday = day == LocalDate.now()
            itemView.isSelected = isToday // selector drives bg

            // fade days not in this month
            val inMonth = day.month == currentMonth.month
            txtDay.alpha = if (inMonth) 1f else 0.4f // dim out

            // show tag for first event source if any
            val evs = monthEvents[day].orEmpty() // list
            if (evs.isNotEmpty()) {
                val first = evs.first()
                tagText.isVisible = true // show tag
                tagText.text = when (first.sourceKind) {
                    SourceKind.PUBLIC -> shortTag(first.sourceLabel) // two letters
                    SourceKind.CUSTOM -> "C" // custom
                }
            } else {
                tagText.isVisible = false // hide tag
            }

            itemView.setOnClickListener { onDayClicked(day) } // click
        }
    }
    //-----------------------------------------------------------------------------------------------

    //UTILS - pickers lists and helpers
    //-----------------------------------------------------------------------------------------------
    private fun shortTag(name: String): String {
        val s = name.trim()
        return s.take(2).uppercase() // two letters
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt() // dp to px

    private fun resolveCountryName(iso: String): String {
        val c = countries.firstOrNull { it.isoCode == iso }
        return c?.country_name ?: iso // name or iso
    }

    private fun tryField(obj: Any, field: String): String? {
        return try {
            val f = obj.javaClass.getDeclaredField(field)
            f.isAccessible = true
            f.get(obj) as? String
        } catch (_: Exception) { null }
    }

    private fun tryDateIso(obj: Any): String? {
        return try {
            val df = obj.javaClass.getDeclaredField("date")
            df.isAccessible = true
            val v = df.get(obj)
            when (v) {
                is String -> v
                else -> {
                    val isoF = v?.javaClass?.getDeclaredField("iso")
                    isoF?.isAccessible = true
                    isoF?.get(v) as? String
                }
            }
        } catch (_: Exception) { null }
    }

    private fun getAllCustomNames(): List<String> {
        // later replace with firebase query for all customs user can view
        return emptyList() // none for now
    }
    //-----------------------------------------------------------------------------------------------

    //SEARCHABLE DIALOG - simple dialog with search and list
    //-----------------------------------------------------------------------------------------------
    private fun showSearchableListDialog(
        title: String,
        items: List<String>,
        emptyHint: String,
        onChoose: (String?) -> Unit
    ) {
        val ctx = requireContext() // context

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL // stack
            setPadding(dp(16), dp(8), dp(16), 0) // padding
        }

        val search = EditText(ctx).apply {
            hint = "Search…" // hint
            inputType = InputType.TYPE_CLASS_TEXT // text
        }

        val emptyView = TextView(ctx).apply {
            text = emptyHint // empty text
            setPadding(0, dp(24), 0, dp(24)) // pad
            gravity = android.view.Gravity.CENTER // center
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE // toggle
        }

        val listView = ListView(ctx) // list
        val data = items.toMutableList() // initial data
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, data) // adapter
        listView.adapter = adapter // bind

        //FILTER - live search
        //-------------------------------------------------
        val watcher: TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty() // query
                val filtered = if (q.isEmpty()) items else items.filter { it.lowercase().contains(q) } // filter
                adapter.clear() // clear
                adapter.addAll(filtered) // add
                adapter.notifyDataSetChanged() // update
                emptyView.isVisible = filtered.isEmpty() // toggle empty
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        search.addTextChangedListener(watcher) // attach
        //-------------------------------------------------

        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) // add search
        container.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) // add empty
        container.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400))) // add list

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title) // title
            .setView(container) // content
            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); onChoose(null) } // cancel
            .create() // build

        listView.setOnItemClickListener { _, _, pos, _ ->
            val chosen = adapter.getItem(pos) // pick
            dialog.dismiss() // close
            onChoose(chosen) // callback
        }

        dialog.show() // show
    }
    //-----------------------------------------------------------------------------------------------

    //EQUAL GAP - simple spacing decorator
    //-----------------------------------------------------------------------------------------------
    private class EqualGapDecoration(private val px: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.set(px, px, px, px) // even gaps
        }
    }
    //-----------------------------------------------------------------------------------------------

    companion object {
        private const val API_KEY = "ZRjKfqyaZbAy9ZaKFHdmudPaFuN2hEPI" // same key as before
    }
}
//-----------------------------------------------------------------------------------------------
