package com.example.prog7314progpoe.ui.sharing

//SEGMENT imports - basics
//-----------------------------------------------------------------------------------------------
import android.os.Bundle // lifecycle
import android.view.LayoutInflater
import android.view.View // view
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment // base
import androidx.navigation.fragment.findNavController
import com.example.prog7314progpoe.R // resources
import com.example.prog7314progpoe.database.user.UserModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

//------------------------------------------------------------------------------------------

class ShareCalendarFragment(private val calendarId: String) : Fragment() {

    private lateinit var shareSpinner: Spinner
    private lateinit var shareBtn: Button
    private var userList: MutableList<UserModel> = mutableListOf()
    private lateinit var db: DatabaseReference

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_shared, container, false)

        shareSpinner = view.findViewById(R.id.spn_ShareWith)
        shareBtn = view.findViewById(R.id.btn_Share)

        db = FirebaseDatabase.getInstance().reference.child("users")
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return view

        loadUsersForSharing(currentUserId)

        shareBtn.setOnClickListener {
            val selectedPos = shareSpinner.selectedItemPosition
            if (selectedPos < 0 || selectedPos >= userList.size) return@setOnClickListener

            val selectedUser = userList[selectedPos]
            shareCalendarWithUser(calendarId, selectedUser.userId)
            Toast.makeText(requireContext(), "Calendar shared with ${selectedUser.firstName}", Toast.LENGTH_SHORT).show()
        }

        return view
    }

    private fun loadUsersForSharing(currentUserId: String) {
        db.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                userList.clear()
                snapshot.children.forEach { child ->
                    val user = child.getValue(UserModel::class.java)
                    if (user != null && user.userId != currentUserId) {
                        userList.add(user)
                    }
                }

                // Fill spinner with names
                val names = userList.map { it.firstName }
                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    names
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                shareSpinner.adapter = adapter
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun shareCalendarWithUser(calendarId: String, userId: String) {
        val ref = FirebaseDatabase.getInstance().reference
        ref.child("calendars").child(calendarId).child("sharedWith").child(userId).setValue(true)
        ref.child("user_calendars").child(userId).child(calendarId).setValue(true)
    }
}
