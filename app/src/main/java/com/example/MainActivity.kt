package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.repository.MessengerRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.PasswordUnlockScreen
import com.example.ui.auth.ProfileSetupScreen
import com.example.ui.auth.signOut
import com.example.ui.chat.ChatDetailScreen
import com.example.ui.chat.ChatListScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.people.PeopleScreen
import com.example.ui.profile.ProfileScreen
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AppContent()
            }
        }
    }
}

@Composable
fun AppContent() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose {
            Firebase.auth.removeAuthStateListener(listener)
        }
    }

    if (currentUser == null) {
        AuthScreen(
            onAuthSuccess = { currentUser = Firebase.auth.currentUser }
        )
    } else {
        val uid = currentUser!!.uid
        key(uid) {
            MainMessengerScreen(currentUserId = uid)
        }
    }
}

@Composable
fun MainMessengerScreen(currentUserId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val repository = remember { MessengerRepository(context) }

    val viewModel: ChatViewModel = viewModel(
        key = currentUserId,
        factory = ChatViewModel.Factory(repository, currentUserId)
    )

    val myProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val allUsers by viewModel.allUsers.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val activeChatUser by viewModel.activeChatUser.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()

    var isEditingProfile by rememberSaveable { mutableStateOf(false) }
    var isUnlocked by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // If profile has not been created yet (new user), show profile setup
    if (myProfile == null || myProfile?.displayName.isNullOrBlank() || isEditingProfile) {
        ProfileSetupScreen(
            initialProfile = myProfile,
            onSaveProfile = { name, username, bio, color, password ->
                viewModel.saveProfile(name, username, bio, color, password)
                isEditingProfile = false
                isUnlocked = true
            },
            onSignOut = {
                signOut(credentialManager, scope)
            }
        )
        return
    }

    // If user set an in-app password/PIN and hasn't unlocked yet
    val appPassword = myProfile?.appPassword ?: ""
    if (appPassword.isNotBlank() && !isUnlocked) {
        PasswordUnlockScreen(
            savedPassword = appPassword,
            userName = myProfile?.displayName ?: "User",
            onUnlocked = { isUnlocked = true },
            onSignOut = {
                signOut(credentialManager, scope)
            }
        )
        return
    }

    // Active full-screen chat screen
    if (activeConversationId != null && activeChatUser != null) {
        ChatDetailScreen(
            currentUserId = currentUserId,
            otherUser = activeChatUser!!,
            messages = activeMessages,
            activeCall = activeCall,
            onSendMessage = { text -> viewModel.sendMessage(text) },
            onToggleReaction = { msg, emoji -> viewModel.toggleReaction(msg, emoji) },
            onDeleteMessage = { msg -> viewModel.deleteMessage(msg) },
            onStartCall = { user, isVideo -> viewModel.startCall(user, isVideo) },
            onToggleMute = { viewModel.toggleMute() },
            onToggleSpeaker = { viewModel.toggleSpeaker() },
            onEndCall = { viewModel.endCall() },
            onBack = { viewModel.closeChat() }
        )
        return
    }

    // Main App Navigation Scaffold
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 0) Icons.Filled.Chat else Icons.Outlined.Chat,
                            contentDescription = "Chats"
                        )
                    },
                    label = { Text("Chats") },
                    modifier = Modifier.testTag("tab_chats")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 1) Icons.Filled.People else Icons.Outlined.People,
                            contentDescription = "People"
                        )
                    },
                    label = { Text("People") },
                    modifier = Modifier.testTag("tab_people")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 2) Icons.Filled.Person else Icons.Outlined.Person,
                            contentDescription = "Profile"
                        )
                    },
                    label = { Text("Me") },
                    modifier = Modifier.testTag("tab_profile")
                )
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatListScreen(
                    currentUserId = currentUserId,
                    conversations = conversations,
                    allUsers = allUsers,
                    myProfile = myProfile,
                    onOpenConversation = { conv, other -> viewModel.openConversation(conv, other) },
                    onStartChat = { other -> viewModel.startChatWithUser(other) },
                    onNavigateToPeople = { selectedTab = 1 }
                )
                1 -> PeopleScreen(
                    users = allUsers,
                    onStartChat = { other -> viewModel.startChatWithUser(other) }
                )
                2 -> ProfileScreen(
                    profile = myProfile,
                    onEditProfile = { isEditingProfile = true },
                    onUpdateStatus = { status -> viewModel.updateStatus(status) },
                    onLockApp = { isUnlocked = false },
                    onSignOut = { signOut(credentialManager, scope) }
                )
            }
        }
    }
}
