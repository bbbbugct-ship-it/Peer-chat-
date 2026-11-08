package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.UserProfile
import com.example.data.repository.MessengerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActiveCallState(
    val user: UserProfile,
    val isVideo: Boolean,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val isConnecting: Boolean = true
)

class ChatViewModel(
    private val repository: MessengerRepository,
    val currentUserId: String
) : ViewModel() {

    // Current user's profile
    val userProfile: StateFlow<UserProfile?> = repository.observeUserProfile(currentUserId)
        .catch { emit(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        )

    // All registered users (for discovery and start chat)
    val allUsers: StateFlow<List<UserProfile>> = repository.observeAllUsers()
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    // All active conversations
    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    // Selected active conversation
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _activeChatUser = MutableStateFlow<UserProfile?>(null)
    val activeChatUser: StateFlow<UserProfile?> = _activeChatUser.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<Message>>(emptyList())
    val activeMessages: StateFlow<List<Message>> = _activeMessages.asStateFlow()

    private var messagesJob: Job? = null

    // Calling simulation
    private val _activeCall = MutableStateFlow<ActiveCallState?>(null)
    val activeCall: StateFlow<ActiveCallState?> = _activeCall.asStateFlow()
    private var callTimerJob: Job? = null

    fun saveProfile(
        displayName: String,
        username: String,
        bio: String,
        avatarColor: String,
        appPassword: String
    ) {
        viewModelScope.launch {
            repository.saveUserProfile(
                displayName = displayName,
                username = username,
                bio = bio,
                avatarColor = avatarColor,
                appPassword = appPassword
            )
        }
    }

    fun updateStatus(status: String) {
        val current = userProfile.value ?: return
        viewModelScope.launch {
            repository.saveUserProfile(
                displayName = current.displayName,
                username = current.username,
                bio = current.bio,
                avatarColor = current.avatarColor,
                appPassword = current.appPassword,
                status = status
            )
        }
    }

    fun openConversation(conversation: Conversation, otherUser: UserProfile) {
        _activeConversationId.value = conversation.id
        _activeChatUser.value = otherUser
        observeConversationMessages(conversation.id)
    }

    fun startChatWithUser(otherUser: UserProfile) {
        val myProfile = userProfile.value ?: UserProfile(userId = currentUserId, displayName = "Me")
        viewModelScope.launch {
            val convId = repository.getOrCreateConversation(myProfile, otherUser)
            _activeConversationId.value = convId
            _activeChatUser.value = otherUser
            observeConversationMessages(convId)
        }
    }

    private fun observeConversationMessages(convId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            repository.observeMessages(convId)
                .catch { emit(emptyList()) }
                .collect { list ->
                    _activeMessages.value = list
                }
        }
    }

    fun closeChat() {
        messagesJob?.cancel()
        _activeConversationId.value = null
        _activeChatUser.value = null
        _activeMessages.value = emptyList()
    }

    fun sendMessage(text: String) {
        val convId = _activeConversationId.value ?: return
        val other = _activeChatUser.value ?: return
        val myName = userProfile.value?.displayName?.ifBlank { "User" } ?: "User"
        viewModelScope.launch {
            repository.sendMessage(
                conversationId = convId,
                otherUserId = other.userId,
                senderName = myName,
                text = text
            )
        }
    }

    fun toggleReaction(message: Message, emoji: String) {
        val convId = _activeConversationId.value ?: return
        viewModelScope.launch {
            repository.toggleReaction(
                conversationId = convId,
                messageId = message.id,
                currentReactions = message.reactions,
                emoji = emoji
            )
        }
    }

    fun deleteMessage(message: Message) {
        val convId = _activeConversationId.value ?: return
        viewModelScope.launch {
            repository.deleteMessage(convId, message.id)
        }
    }

    // Call Simulation
    fun startCall(otherUser: UserProfile, isVideo: Boolean) {
        _activeCall.value = ActiveCallState(user = otherUser, isVideo = isVideo)
        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            delay(1500) // simulated ring / connecting
            _activeCall.value = _activeCall.value?.copy(isConnecting = false)
            while (true) {
                delay(1000)
                val current = _activeCall.value ?: break
                _activeCall.value = current.copy(durationSeconds = current.durationSeconds + 1)
            }
        }
    }

    fun toggleMute() {
        val current = _activeCall.value ?: return
        _activeCall.value = current.copy(isMuted = !current.isMuted)
    }

    fun toggleSpeaker() {
        val current = _activeCall.value ?: return
        _activeCall.value = current.copy(isSpeaker = !current.isSpeaker)
    }

    fun endCall() {
        callTimerJob?.cancel()
        _activeCall.value = null
    }

    class Factory(
        private val repository: MessengerRepository,
        private val currentUserId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository, currentUserId) as T
        }
    }
}
