package com.example.prog7314progpoe

import android.content.ContentValues.TAG
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.example.prog7314progpoe.database.user.UserModel
import com.example.prog7314progpoe.reglogin.LoginActivity
import com.example.prog7314progpoe.reglogin.RegisterActivity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.database.FirebaseDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val credentialManager by lazy { CredentialManager.create(this) }

    // Google sign-in request
    private val googleIdOption by lazy {
        GetGoogleIdOption.Builder()
            .setServerClientId(getString(R.string.default_web_client_id)) // from google-services.json
            .setFilterByAuthorizedAccounts(false)
            .build()
    }

    // Credential Manager request
    private val request by lazy {
        GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private val RC_SIGN_IN = 9001 // Request code

    private val db = FirebaseDatabase
        .getInstance("https://chronosync-f3425-default-rtdb.europe-west1.firebasedatabase.app/")
        .getReference("users")

    override fun onCreate(savedInstanceState: Bundle?) {

        auth = Firebase.auth

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnSignUp = findViewById<Button>(R.id.btnSignUp)
        val googleLoginBtn = findViewById<LinearLayout>(R.id.btnGoogleLogin)
        val googleSignUpBtn = findViewById<LinearLayout>(R.id.btnGoogleSignUp)

        btnLogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

        btnSignUp.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        // Google Sign-In (login)
        googleLoginBtn.setOnClickListener {
            launchGoogleSignIn()
        }

        // Google Sign-Up (same flow, just semantic difference)
        googleSignUpBtn.setOnClickListener {
            launchGoogleSignIn()
        }

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // From Google Services JSON
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        googleLoginBtn.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                Toast.makeText(this, "Google sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchGoogleSignIn() {
        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(this@MainActivity, request)
                handleSignIn(result.credential)
            } catch (e: Exception) {
                Log.e(TAG, "Google sign-in failed: ${e.localizedMessage}")
                Toast.makeText(this@MainActivity, "Google sign-in failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Firebase auth with Google
    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "signInWithCredential:success")
                    val user = auth.currentUser
                    // Make sure user exists in your Realtime Database
                    if (user != null) {
                        createUserInDatabaseIfNotExists(user)
                    }
                    updateUI(user)
                } else {
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    updateUI(null)
                }
            }
    }

    // Handle Credential Manager result
    private fun handleSignIn(credential: Credential) {
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            firebaseAuthWithGoogle(googleIdTokenCredential.idToken)
        } else {
            Log.w(TAG, "Credential is not of type Google ID!")
        }
    }

    //This is just a stand in for a real method you can put in
    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            val intent = Intent(this, CalendarActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            Log.d(TAG, "User is null, staying on LoginActivity")
        }
    }

    fun createUserInDatabaseIfNotExists(user: FirebaseUser, onComplete: (() -> Unit)? = null) {
        db.child(user.uid).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                val newUser = UserModel(
                    userId = user.uid,
                    email = user.email ?: "",
                    firstName = user.displayName ?: "",
                    lastName = "", // Optional: parse from displayName if you want
                    location = "",
                    dateOfBirth = null
                )
                db.child(user.uid).setValue(newUser).addOnCompleteListener {
                    onComplete?.invoke()
                }
            } else {
                onComplete?.invoke()
            }
        }
    }

    /*
    Use this to assign the current user to what they have made.
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
    FirebaseCalendarDbHelper.insertCalendar(
    ownerId = currentUserId,
    title = "My Calendar",
    holidays = null
    ) {
        // Calendar created
    }
    */

}
