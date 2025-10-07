package com.example.prog7314progpoe.reglogin

import android.content.ContentValues.TAG
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.prog7314progpoe.CalendarActivity
import com.example.prog7314progpoe.R
import com.example.prog7314progpoe.database.user.FirebaseUserDbHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {

        auth = Firebase.auth

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val emailEt = findViewById<EditText>(R.id.etEmail)
        val passwordEt = findViewById<EditText>(R.id.etPassword)
        val loginBtn = findViewById<Button>(R.id.btnLogin)
        val signUpLink = findViewById<TextView>(R.id.tvSignUp)

        // Email/Password login
        loginBtn.setOnClickListener {
            val email = emailEt.text.toString().trim()
            val password = passwordEt.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            FirebaseUserDbHelper.loginUser(email, password) { success, message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                if (success) {
                    updateUI(auth.currentUser)
                }
            }
        }

        // Navigate to RegisterActivity
        signUpLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    /* Sign out completely - Can use this sign out in the settings.
    private fun signOut() {
        auth.signOut()
        lifecycleScope.launch {
            try {
                val clearRequest = ClearCredentialStateRequest()
                credentialManager.clearCredentialState(clearRequest)
                Toast.makeText(this@LoginActivity, "Signed out", Toast.LENGTH_SHORT).show()
                updateUI(null)
            } catch (e: ClearCredentialException) {
                Log.e(TAG, "Couldn't clear user credentials: ${e.localizedMessage}")
            }
        }
    }
    */

    //A stand in if you want to change the view based on the user
    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            val intent = Intent(this, CalendarActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            Log.d(TAG, "User is null, staying on LoginActivity")
        }
    }
}
