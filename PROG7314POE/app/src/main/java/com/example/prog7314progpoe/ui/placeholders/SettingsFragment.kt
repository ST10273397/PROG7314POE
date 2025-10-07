package com.example.prog7314progpoe.ui.settings

//SEGMENT imports - tools we need
//-----------------------------------------------------------------------------------------------
import android.content.Context // Context for prefs
import android.content.Intent // For navigation to Login
import android.os.Bundle // Fragment lifecycle
import android.view.View // View ref
import android.widget.Toast // quick toasts
import androidx.appcompat.app.AppCompatDelegate // toggling night mode
import androidx.fragment.app.Fragment // base fragment
import com.example.prog7314progpoe.R // resources
import com.example.prog7314progpoe.reglogin.LoginActivity // back to login
import com.google.android.material.button.MaterialButton // buttons
import com.google.android.material.dialog.MaterialAlertDialogBuilder // confirm dialogs
import com.google.android.material.materialswitch.MaterialSwitch // dark switch
import com.google.firebase.auth.FirebaseAuth // auth
//------------------------------------------------------------------------------------------

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    //SEGMENT constants and prefs - saved settings
    //-----------------------------------------------------------------------------------------------
    private val prefs by lazy { requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE) } // store small flags
    private val auth by lazy { FirebaseAuth.getInstance() } // Firebase auth
    private val KEY_DARK_MODE = "dark_mode" //Start
    //-----------------------------------------------------------------------------------------------

    //SEGMENT lifecycle - wire up the UI
    //-----------------------------------------------------------------------------------------------
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //SUB-SEGMENT find views - get references
        //-------------------------------------------------
        val switchDark = view.findViewById<MaterialSwitch>(R.id.switchDark) // dark toggle
        val btnLogout = view.findViewById<MaterialButton>(R.id.btnLogout) // logout button
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDeleteAccount) // delete btn
        val btnChangePw = view.findViewById<MaterialButton>(R.id.btnChangePassword) // change pw
        //-------------------------------------------------

        //SUB-SEGMENT init switch - reflect current mode
        //-------------------------------------------------
        val saved = prefs.getBoolean(KEY_DARK_MODE, false) // saved pref
        switchDark.isChecked = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> saved // follow saved if system or unknown
        }
        //-------------------------------------------------

        //SUB-SEGMENT toggle handler - flip theme live
        //-------------------------------------------------
        switchDark.setOnCheckedChangeListener { _, isChecked ->
            applyDarkMode(isChecked) // apply and persist
        }
        //-------------------------------------------------

        //SUB-SEGMENT logout - clear session and go to login
        //-------------------------------------------------
        btnLogout.setOnClickListener {
            auth.signOut() // sign out
            Toast.makeText(requireContext(), "Logged out", Toast.LENGTH_SHORT).show() //note
            goToLoginClearTask() // clear back stack
        }
        //-------------------------------------------------

        //SUB-SEGMENT delete account - confirm then attempt
        //-------------------------------------------------
        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete account")
                .setMessage("This cannot be undone")
                .setPositiveButton("Delete") { _, _ ->
                    val user = auth.currentUser // get user
                    if (user == null) {
                        Toast.makeText(requireContext(), "No user signed in", Toast.LENGTH_SHORT).show() //note
                        return@setPositiveButton
                    }
                    user.delete().addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Toast.makeText(requireContext(), "Account deleted", Toast.LENGTH_SHORT).show() // Start
                            goToLoginClearTask() // back to login
                        } else {
                            val msg = task.exception?.localizedMessage ?: "Delete failed"
                            // Firebase may require recent login for delete
                            Toast.makeText(requireContext(), "$msg. Re-login may be required", Toast.LENGTH_LONG).show() //note
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        //-------------------------------------------------

        //SUB-SEGMENT change password - send reset email
        //-------------------------------------------------
        btnChangePw.setOnClickListener {
            val email = auth.currentUser?.email
            if (email.isNullOrBlank()) {
                Toast.makeText(requireContext(), "No email on account", Toast.LENGTH_SHORT).show() //comment
                return@setOnClickListener
            }
            auth.sendPasswordResetEmail(email).addOnCompleteListener { t ->
                if (t.isSuccessful) {
                    Toast.makeText(requireContext(), "Reset email sent to $email", Toast.LENGTH_LONG).show() // Start
                } else {
                    val msg = t.exception?.localizedMessage ?: "Could not send reset email"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() // comment
                }
            }
        }
        //-------------------------------------------------
    }
    //-----------------------------------------------------------------------------------------------

    //SEGMENT helpers - theme + nav
    //-----------------------------------------------------------------------------------------------
    private fun applyDarkMode(enabled: Boolean) {
        // persist pref
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply() // save it

        // apply mode
        val mode = if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(mode) // flip theme live
    }

    private fun goToLoginClearTask() {
        //Navigate to login, clear back stack
        val i = Intent(requireContext(), LoginActivity::class.java) // intent
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) // flags
        startActivity(i) //start
        requireActivity().finish() // close current
    }
    //-----------------------------------------------------------------------------------------------
}
