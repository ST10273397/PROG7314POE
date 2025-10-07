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
import androidx.appcompat.app.AppCompatDelegate


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

        //SEGMENT theme init - apply saved dark mode before UI inflates
        //-----------------------------------------------------------------------------------------------
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE) // read saved prefs
        val enabled = prefs.getBoolean("dark_mode", false) // read flag
        val desired = if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO // pick mode
        if (AppCompatDelegate.getDefaultNightMode() != desired) {
            AppCompatDelegate.setDefaultNightMode(desired) // apply if different
        }
        //-----------------------------------------------------------------------------------------------

        super.onCreate(savedInstanceState)

        //SEGMENT firebase auth - init auth
        //-----------------------------------------------------------------------------------------------
        auth = Firebase.auth // set firebase auth
        //-----------------------------------------------------------------------------------------------

        //SEGMENT inflate UI - set the main layout
        //-----------------------------------------------------------------------------------------------
        setContentView(R.layout.activity_main) // inflate
        //-----------------------------------------------------------------------------------------------

        //SEGMENT view refs - grab buttons and rows
        //-----------------------------------------------------------------------------------------------
        val btnLogin = findViewById<Button>(R.id.btnLogin) // login button
        val btnSignUp = findViewById<Button>(R.id.btnSignUp) // sign up button
        val googleLoginBtn = findViewById<LinearLayout>(R.id.btnGoogleLogin) // google login row
        val googleSignUpBtn = findViewById<LinearLayout>(R.id.btnGoogleSignUp) // google signup row
        //-----------------------------------------------------------------------------------------------

        //SEGMENT simple nav - email login and register screens
        //-----------------------------------------------------------------------------------------------
        btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java)) // go to login
        }
        btnSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java)) // go to register
        }
        //-----------------------------------------------------------------------------------------------

        //SEGMENT google sign in - configure GSO and handlers
        //-----------------------------------------------------------------------------------------------
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // from google services
            .requestEmail() // request email
            .build() // build gso

        googleSignInClient = GoogleSignIn.getClient(this, gso) // create client

        //SUB-SEGMENT hook up both google rows to the same handler
        //-------------------------------------------------
        val startGoogle = {
            val signInIntent = googleSignInClient.signInIntent // intent
            startActivityForResult(signInIntent, RC_SIGN_IN) // launch
        }
        googleLoginBtn.setOnClickListener { startGoogle() } // tap to start
        googleSignUpBtn.setOnClickListener { startGoogle() } // same flow
        //-------------------------------------------------
        //-----------------------------------------------------------------------------------------------
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

}
