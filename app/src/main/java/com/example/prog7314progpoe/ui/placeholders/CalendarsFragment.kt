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

//CALENDAR FRAGMENT - monthly grid with pickers and a day card
//-----------------------------------------------------------------------------------------------
class CalendarsFragment : Fragment() {

    //STATE - month and chosen sources
    //-----------------------------------------------------------------------------------------------
    private var currentMonth: YearMonth = YearMonth.now() // which month we show
    private var selectedDay: LocalDate = LocalDate.now() // selected day for details

    private val activeCountryIsos = mutableSetOf<String>() // public holiday country isos
    private val activeCustomIds = mutableSetOf<String>() // custom calendar ids or names

    private var countries: List<Country> = emptyList() // cached countries
    private val monthEvents = mutableMapOf<LocalDate, MutableList<EventItem>>() // events per date
    //-----------------------------------------------------------------------------------------------

    //UI - top header grid and day card
    //-----------------------------------------------------------------------------------------------
    private lateinit var txtMonth: TextView // month label
    private lateinit var btnPrev: ImageButton // go left
    private lateinit var btnNext: ImageButton // go right
    private lateinit var btnPickSingle: Button // pick exactly one source
    private lateinit var btnPickFromDashboard: Button // pick many from dashboard
    private lateinit var grid: RecyclerView // month grid
    private lateinit var dayCard: LinearLayout // details card
    private lateinit var dayCardTitle: TextView // title in card
    private lateinit var dayCardList: LinearLayout // list of events in card
    private lateinit var emptyDayText: TextView // empty state text
    //-----------------------------------------------------------------------------------------------

    //COROUTINES - scope for async work
    //-----------------------------------------------------------------------------------------------
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob()) // ui scope
    //-----------------------------------------------------------------------------------------------

    //PREFS - reuse dashboard selections
    //-----------------------------------------------------------------------------------------------
    private val prefs by lazy {
        requireContext().getSharedPreferences("dashboard_slots", Context.MODE_PRIVATE) // same prefs
    }
    //-----------------------------------------------------------------------------------------------

    //MODELS - tiny event holder and kind
    //-----------------------------------------------------------------------------------------------
    private data class EventItem(
        val date: LocalDate, // day only
        val title: String, // event title
        val sourceLabel: String, // ex South Africa or Custom X
        val sourceKind: SourceKind // public or custom
    )

    private enum class SourceKind { PUBLIC, CUSTOM } // two kinds
    //-----------------------------------------------------------------------------------------------

    //LIFECYCLE - inflate and wire
    //-----------------------------------------------------------------------------------------------
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_calendars_month, container, false) // inflate layout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        //BIND VIEWS - grab handles
        //-----------------------------------------------------------------------------------------------
        txtMonth = view.findViewById(R.id.txtMonth) // month text
        btnPrev = view.findViewById(R.id.btnPrev) // prev button
        btnNext = view.findViewById(R.id.btnNext) // next button
        btnPickSingle = view.findViewById(R.id.btnPickSingle) // single picker
        btnPickFromDashboard = view.findViewById(R.id.btnPickFromDashboard) // multi picker
        grid = view.findViewById(R.id.calendarGrid) // month grid
        dayCard = view.findViewById(R.id.dayCard) // details card
        dayCardTitle = view.findViewById(R.id.dayCardTitle) // card title
        dayCardList = view.findViewById(R.id.dayCardList) // card list
        emptyDayText = view.findViewById(R.id.emptyDayText) // empty text
        //-----------------------------------------------------------------------------------------------

        //BOTTOM INSET PADDING - keep the day card above the bottom nav
        //-----------------------------------------------------------------------------------------------
        val root = view as ViewGroup // root container
        val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav) // bottom nav ref

        //SUB-SEGMENT - apply both system bar and bottom nav height
        //-------------------------------------------------
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom // system bar size
            val navH = bottomNav?.height ?: 0 // bottom nav height
            val extra = dp(16) // a little breathing room
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, sysBottom + navH + extra) // apply bottom pad
            insets // return
        }
        //-------------------------------------------------
        //-----------------------------------------------------------------------------------------------


        //GRID SETUP - 7 columns with equal gaps
        //-----------------------------------------------------------------------------------------------
        grid.layoutManager = GridLayoutManager(requireContext(), 7) // 7 days per week
        grid.adapter = DaysAdapter { clicked -> onDayClicked(clicked) } // click handler
        grid.addItemDecoration(EqualGapDecoration(dp(4))) // spacing
        grid.setHasFixedSize(true) // fixed cell height
        //-----------------------------------------------------------------------------------------------

        //MONTH NAV - move back or forward
        //-----------------------------------------------------------------------------------------------
        btnPrev.setOnClickListener {
            currentMonth = currentMonth.minusMonths(1) // go back
            refreshMonth() // reload month
        }
        btnNext.setOnClickListener {
            currentMonth = currentMonth.plusMonths(1) // go forward
            refreshMonth() // reload month
        }
        //-----------------------------------------------------------------------------------------------

        //PICKERS - single vs from dashboard
        //-----------------------------------------------------------------------------------------------
        btnPickSingle.setOnClickListener { onPickSingleCalendar() } // open all
        btnPickFromDashboard.setOnClickListener { onPickFromDashboard() } // open dashboard
        //-----------------------------------------------------------------------------------------------

        //INITIAL STATE - reset selections and draw
        //-----------------------------------------------------------------------------------------------
        selectedDay = LocalDate.now() // start on today
        activeCountryIsos.clear() // no sources yet
        activeCustomIds.clear() // no sources yet
        refreshMonth() // draw and load
        //-----------------------------------------------------------------------------------------------
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.coroutineContext.cancelChildren() // stop pending work
    }
    //-----------------------------------------------------------------------------------------------

    //DAY CLICK - select and show details
    //-----------------------------------------------------------------------------------------------
    private fun onDayClicked(day: LocalDate) {
        selectedDay = day // set selection
        renderDayCard() // update card
        grid.adapter?.notifyDataSetChanged() // redraw for highlight
    }
    //-----------------------------------------------------------------------------------------------

    //REFRESH MONTH - rebuild grid load events and render card
    //-----------------------------------------------------------------------------------------------
    private fun refreshMonth() {
        txtMonth.text = currentMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")) // set title
        renderGrid() // compute days
        fetchMonthEvents() // load events
        renderDayCard() // show day
    }
    //-----------------------------------------------------------------------------------------------

    //GRID RENDER - compute full weeks with lead and trail days
    //-----------------------------------------------------------------------------------------------
    private fun renderGrid() {
        val firstOfMonth = currentMonth.atDay(1) // first day of month
        val firstDow = firstOfMonth.dayOfWeek.value % 7 // sunday index as 0
        val daysInMonth = currentMonth.lengthOfMonth() // count

        val cells = mutableListOf<LocalDate>() // output list
        //SUB-SEGMENT - leading from previous month
        //-------------------------------------------------
        for (i in 0 until firstDow) {
            cells.add(firstOfMonth.minusDays((firstDow - i).toLong())) // push prev days
        }
        //-------------------------------------------------
        //SUB-SEGMENT - current month days
        //-------------------------------------------------
        for (d in 1..daysInMonth) {
            cells.add(currentMonth.atDay(d)) // push each day
        }
        //-------------------------------------------------
        //SUB-SEGMENT - trailing to complete the last week
        //-------------------------------------------------
        while (cells.size % 7 != 0) {
            val last = cells.last()
            cells.add(last.plusDays(1)) // push next month days
        }
        //-------------------------------------------------

        (grid.adapter as DaysAdapter).submit(cells) // send to adapter
    }
    //-----------------------------------------------------------------------------------------------

    //FETCH EVENTS - fill monthEvents map from active sources
    //-----------------------------------------------------------------------------------------------
    private fun fetchMonthEvents() {
        monthEvents.clear() // reset

        val start = currentMonth.atDay(1) // window start
        val end = currentMonth.atEndOfMonth() // window end
        val yearList = listOf(start.year, start.year + 1) // cover wrap over new year

        uiScope.launch {
            //PUBLIC HOLIDAYS - iterate active countries and fetch
            //-------------------------------------------------
            for (iso in activeCountryIsos) {
                for (y in yearList) {
                    try {
                        val resp = withContext(Dispatchers.IO) { ApiClient.api.getHolidays(API_KEY, iso, y) } // call api
                        val hols = resp.response.holidays ?: emptyList() // get list
                        hols.forEach { h ->
                            val isoDate = h.date?.iso ?: return@forEach // skip if no date
                            val d = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return@forEach // parse
                            if (!d.isBefore(start) && !d.isAfter(end)) { // in month window
                                val title = h.name ?: "Holiday" // fallback title
                                monthEvents.getOrPut(d) { mutableListOf() }
                                    .add(EventItem(d, title, resolveCountryName(iso), SourceKind.PUBLIC)) // add item
                            }
                        }
                    } catch (_: Exception) {
                        // ignore one year fail
                    }
                }
            }
            //-------------------------------------------------

            //CUSTOM CALENDARS - iterate active custom ids and fetch
            //-------------------------------------------------
            for (cid in activeCustomIds) {
                val latch = CompletableDeferred<Unit>() // wait for callback
                FirebaseHolidayDbHelper.getAllHolidays(cid) { list ->
                    try {
                        list.forEach { h ->
                            val title = tryField(h, "title") ?: tryField(h, "name") ?: "Event" // title guess
                            val dateStr = tryDateIso(h) ?: tryField(h, "day") // iso or day string
                            val d = runCatching { LocalDate.parse(dateStr ?: "") }.getOrNull() // parse or null
                            if (d != null && !d.isBefore(start) && !d.isAfter(end)) { // in month
                                monthEvents.getOrPut(d) { mutableListOf() }
                                    .add(EventItem(d, title, cid, SourceKind.CUSTOM)) // add item
                            }
                        }
                    } catch (_: Exception) {
                        // ignore this calendar on fail
                    } finally {
                        latch.complete(Unit) // release
                    }
                }
                latch.await() // wait per custom calendar
            }
            //-------------------------------------------------

            grid.adapter?.notifyDataSetChanged() // redraw tags
            renderDayCard() // refresh card
        }
    }
    //-----------------------------------------------------------------------------------------------

    //DAY CARD - show all events for the selected day
    //-----------------------------------------------------------------------------------------------
    private fun renderDayCard() {
        val items = monthEvents[selectedDay].orEmpty() // list for day
        dayCardTitle.text = selectedDay.format(DateTimeFormatter.ofPattern("EEE, dd MMM")) // header
        dayCard.visibility = View.VISIBLE // make sure visible

        dayCardList.removeAllViews() // wipe
        if (items.isEmpty()) {
            emptyDayText.isVisible = true // show empty
        } else {
            emptyDayText.isVisible = false // hide empty
            items.sortedBy { it.title }.forEach { ev -> // sort by name
                dayCardList.addView(makeEventRow(ev)) // add row
            }
        }
    }
    //-----------------------------------------------------------------------------------------------

    //PICK SINGLE - choose one calendar from full list
    //-----------------------------------------------------------------------------------------------
    private fun onPickSingleCalendar() {
        //SUB-SEGMENT - load countries if empty
        //-------------------------------------------------
        if (countries.isEmpty()) {
            ApiClient.api.getLocations(API_KEY).enqueue(object : Callback<CountryResponse> {
                override fun onResponse(call: Call<CountryResponse>, response: Response<CountryResponse>) {
                    countries = response.body()?.response?.countries ?: emptyList() // cache
                    showSinglePicker() // open dialog
                }
                override fun onFailure(call: Call<CountryResponse>, t: Throwable) {
                    countries = emptyList() // none
                    showSinglePicker() // still open customs only
                }
            })
        } else {
            showSinglePicker() // already have list
        }
        //-------------------------------------------------
    }

    private fun showSinglePicker() {
        val countryNames = countries.map { it.country_name } // visible names
        val countryIsos = countries.map { it.isoCode } // ids

        val customs = getAllCustomNames() // custom list later

        val all = mutableListOf<String>() // merged view list
        val types = mutableListOf<SourceKind>() // parallel kinds
        val ids = mutableListOf<String>() // parallel ids

        countryNames.forEachIndexed { i, name ->
            all += name // add name
            types += SourceKind.PUBLIC // mark kind
            ids += countryIsos[i] // iso id
        }
        customs.forEach { name ->
            all += name // add custom
            types += SourceKind.CUSTOM // kind
            ids += name // use name as id placeholder
        }

        showSearchableListDialog(
            title = "Pick calendar",
            items = all,
            emptyHint = "No calendars found"
        ) { chosen ->
            if (chosen == null) return@showSearchableListDialog // canceled
            val idx = all.indexOf(chosen) // find selection
            if (idx < 0) return@showSearchableListDialog // not found
            activeCountryIsos.clear() // reset
            activeCustomIds.clear() // reset
            when (types[idx]) {
                SourceKind.PUBLIC -> activeCountryIsos += ids[idx] // set public
                SourceKind.CUSTOM -> activeCustomIds += ids[idx] // set custom
            }
            refreshMonth() // reload data
        }
    }
    //-----------------------------------------------------------------------------------------------

    //PICK FROM DASHBOARD - choose multiple saved slots
    //-----------------------------------------------------------------------------------------------
    private fun onPickFromDashboard() {
        val slots = readDashboardSlots() // load
        if (slots.isEmpty()) {
            Toast.makeText(requireContext(), "No dashboard calendars to choose", Toast.LENGTH_SHORT).show() // toast
            return
        }
        val names = slots.map { it.displayName } // visible text
        val checked = BooleanArray(slots.size) { i ->
            val s = slots[i]
            (s.kind == SourceKind.PUBLIC && activeCountryIsos.contains(s.id)) ||
                    (s.kind == SourceKind.CUSTOM && activeCustomIds.contains(s.id))
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Pick from dashboard") // dialog title
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked // update
            }
            .setPositiveButton("Apply") { d, _ ->
                activeCountryIsos.clear() // wipe
                activeCustomIds.clear() // wipe
                checked.forEachIndexed { i, flag ->
                    if (flag) {
                        val s = slots[i]
                        if (s.kind == SourceKind.PUBLIC) activeCountryIsos += s.id else activeCustomIds += s.id // assign
                    }
                }
                refreshMonth() // refresh
                d.dismiss() // close
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    //-----------------------------------------------------------------------------------------------

    //HELPERS - read dashboard slots stored in prefs
    //-----------------------------------------------------------------------------------------------
    private data class SlotRef(val id: String, val displayName: String, val kind: SourceKind) // tiny ref

    private fun readDashboardSlots(): List<SlotRef> {
        val list = mutableListOf<SlotRef>() // output
        for (i in 0 until 8) { // eight slots
            val type = prefs.getString("type_$i", null) ?: continue // skip empty
            val id = prefs.getString("id_$i", null) ?: continue // skip
            val name = prefs.getString("name_$i", null) ?: continue // skip
            val kind = if (type == "PUBLIC") SourceKind.PUBLIC else SourceKind.CUSTOM // map
            list += SlotRef(id, name, kind) // add
        }
        return list // done
    }
    //-----------------------------------------------------------------------------------------------

    //UI FACTORY - build one row for the day card
    //-----------------------------------------------------------------------------------------------
    private fun makeEventRow(ev: EventItem): View {
        val ctx = requireContext() // ctx
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL // row
            setPadding(dp(12), dp(8), dp(12), dp(8)) // padding
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tag = TextView(ctx).apply {
            text = when (ev.sourceKind) {
                SourceKind.PUBLIC -> shortTag(ev.sourceLabel) // SA etc
                SourceKind.CUSTOM -> "C" // custom
            }
            // try optional rounded bg
            val tagBgId = resources.getIdentifier("bg_tag_round", "drawable", requireContext().packageName) // lookup
            if (tagBgId != 0) setBackgroundResource(tagBgId) // set if exists
            setPadding(dp(8), dp(4), dp(8), dp(4)) // pad
            setTextColor(android.graphics.Color.parseColor("#1565C0")) // blue text
        }

        val title = TextView(ctx).apply {
            text = "  ${ev.title}" // space then text
            textSize = 16f // size
        }

        row.addView(tag) // add badge
        row.addView(title) // add title
        return row // return view
    }
    //-----------------------------------------------------------------------------------------------

    //ADAPTER - month grid with 7 columns
    //-----------------------------------------------------------------------------------------------
    private inner class DaysAdapter(val onClick: (LocalDate) -> Unit) :
        RecyclerView.Adapter<DayVH>() {

        private val days = mutableListOf<LocalDate>() // visible days

        fun submit(list: List<LocalDate>) {
            days.clear() // reset
            days.addAll(list) // copy list
            notifyDataSetChanged() // redraw
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_day_cell, parent, false) // inflate cell
            return DayVH(v) // holder
        }

        override fun onBindViewHolder(holder: DayVH, position: Int) {
            holder.bind(days[position]) // bind item
        }

        override fun getItemCount(): Int = days.size // count
    }

    private inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txtDay: TextView = v.findViewById(R.id.txtDay) // number
        private val txtTag: TextView = v.findViewById(R.id.txtTag) // tiny tag

        fun bind(day: LocalDate) {
            //DAY NUMBER - text for the cell
            //-----------------------------------------------------------------------------------------------
            txtDay.text = day.dayOfMonth.toString() // set label

            //SELECTION AND TODAY - change look for selected and today
            //-----------------------------------------------------------------------------------------------
            val isToday = day == LocalDate.now() // today flag
            val isSel = day == selectedDay // selected flag

            itemView.isSelected = isSel // selector handles rounded bg

            //SUB-SEGMENT - today color tweak
            //-------------------------------------------------
            if (isToday) {
                txtDay.setTextColor(android.graphics.Color.parseColor("#1565C0")) // blue for today
            } else {
                txtDay.setTextColor(android.graphics.Color.parseColor("#000000")) // black
            }
            //-------------------------------------------------

            //FADE OTHER MONTH - dim out non current month days
            //-----------------------------------------------------------------------------------------------
            val inMonth = day.month == currentMonth.month // same month
            txtDay.alpha = if (inMonth) 1f else 0.4f // dim

            //TAG - show two letter source and plus if more than one
            //-----------------------------------------------------------------------------------------------
            val evs = monthEvents[day].orEmpty() // events list
            if (evs.isNotEmpty()) {
                val first = evs.first() // first for tag
                val base = when (first.sourceKind) {
                    SourceKind.PUBLIC -> shortTag(first.sourceLabel) // SA etc
                    SourceKind.CUSTOM -> "C" // custom
                }
                val tagTextStr = if (evs.size > 1) "$base+" else base // add plus if many
                txtTag.text = tagTextStr // set
                txtTag.setTextColor(android.graphics.Color.parseColor("#1565C0")) // blue color
                txtTag.visibility = View.VISIBLE // show
            } else {
                txtTag.visibility = View.GONE // hide
            }

            //CLICK - select this day
            //-----------------------------------------------------------------------------------------------
            itemView.setOnClickListener { onDayClicked(day) } // click listener
        }
    }
    //-----------------------------------------------------------------------------------------------

    //UTILS - tag text and reflection helpers
    //-----------------------------------------------------------------------------------------------
    private fun shortTag(name: String): String {
        val s = name.trim() // trim
        return s.take(2).uppercase() // two letters
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt() // dp to px

    private fun resolveCountryName(iso: String): String {
        val c = countries.firstOrNull { it.isoCode == iso } // find
        return c?.country_name ?: iso // name or iso
    }

    private fun tryField(obj: Any, field: String): String? {
        return try {
            val f = obj.javaClass.getDeclaredField(field) // field
            f.isAccessible = true // open
            f.get(obj) as? String // value
        } catch (_: Exception) { null }
    }

    private fun tryDateIso(obj: Any): String? {
        return try {
            val df = obj.javaClass.getDeclaredField("date") // date field
            df.isAccessible = true // open
            val v = df.get(obj) // get value
            when (v) {
                is String -> v // direct string
                else -> {
                    val isoF = v?.javaClass?.getDeclaredField("iso") // nested iso
                    isoF?.isAccessible = true // open
                    isoF?.get(v) as? String // value
                }
            }
        } catch (_: Exception) { null }
    }
    //-----------------------------------------------------------------------------------------------

    //CUSTOM LISTS - placeholder names for all custom calendars
    //-----------------------------------------------------------------------------------------------
    private fun getAllCustomNames(): List<String> {
        // later this will come from firebase based on user and sharing
        return emptyList() // nothing yet
    }
    //-----------------------------------------------------------------------------------------------

    //SEARCHABLE DIALOG - generic list with search box
    //-----------------------------------------------------------------------------------------------
    private fun showSearchableListDialog(
        title: String,
        items: List<String>,
        emptyHint: String,
        onChoose: (String?) -> Unit
    ) {
        val ctx = requireContext() // ctx

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL // stack
            setPadding(dp(16), dp(8), dp(16), 0) // padding
        }

        val search = EditText(ctx).apply {
            hint = "Search…" // hint
            inputType = InputType.TYPE_CLASS_TEXT // text
        }

        val emptyView = TextView(ctx).apply {
            text = emptyHint // message
            setPadding(0, dp(24), 0, dp(24)) // pad
            gravity = android.view.Gravity.CENTER // center
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE // toggle
        }

        val listView = ListView(ctx) // list view
        val data = items.toMutableList() // initial data
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, data) // adapter
        listView.adapter = adapter // bind

        //FILTER - live update on type
        //-------------------------------------------------
        val watcher: TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty() // query
                val filtered = if (q.isEmpty()) items else items.filter { it.lowercase().contains(q) } // filter
                adapter.clear() // clear
                adapter.addAll(filtered) // add
                adapter.notifyDataSetChanged() // refresh
                emptyView.isVisible = filtered.isEmpty() // toggle
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
            .setView(container) // set content
            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); onChoose(null) } // cancel
            .create() // build

        listView.setOnItemClickListener { _, _, pos, _ ->
            val chosen = adapter.getItem(pos) // pick item
            dialog.dismiss() // close dialog
            onChoose(chosen) // callback
        }

        dialog.show() // show dialog
    }
    //-----------------------------------------------------------------------------------------------

    //EQUAL GAP - simple spacing decorator for grid
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
