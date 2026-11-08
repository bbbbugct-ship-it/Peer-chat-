package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.UserProfile
import com.example.data.util.OperationType
import com.example.data.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MessengerRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    fun getCurrentUserId(): String? = auth.currentUser?.uid

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Messenger.")
    }

    // --- User Profile ---

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) snapshot.toObject(UserProfile::class.java) else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser != null) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveUserProfile(
        displayName: String,
        username: String,
        bio: String,
        avatarColor: String,
        appPassword: String,
        status: String = "online"
    ) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val payload = mutableMapOf<String, Any>(
            "userId" to uid,
            "displayName" to displayName.trim(),
            "username" to username.trim().lowercase(),
            "bio" to bio.trim(),
            "avatarColor" to avatarColor,
            "appPassword" to appPassword,
            "status" to status,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        // Check if existing document already has createdAt
        val existing = try { docRef.get().await() } catch (e: Exception) { null }
        if (existing == null || !existing.exists()) {
            payload["createdAt"] = FieldValue.serverTimestamp()
        }

        try {
            docRef.set(payload, SetOptions.merge()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    fun observeAllUsers(): Flow<List<UserProfile>> = flow {
        val uid = requireUserId()
        val path = "users"
        emitAll(
            db.collection("users")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(UserProfile::class.java)
                        .filter { it.userId != uid && it.displayName.isNotBlank() }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser != null) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    // --- Conversations ---

    fun observeConversations(): Flow<List<Conversation>> = flow {
        val uid = requireUserId()
        val path = "conversations"
        emitAll(
            db.collection("conversations")
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Conversation::class.java)
                        .sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getOrCreateConversation(
        myProfile: UserProfile,
        otherUser: UserProfile
    ): String {
        val myUid = requireUserId()
        val otherUid = otherUser.userId
        val convId = if (myUid < otherUid) "${myUid}_$otherUid" else "${otherUid}_$myUid"
        val docRef = db.collection("conversations").document(convId)

        val doc = try { docRef.get().await() } catch (e: Exception) { null }
        if (doc == null || !doc.exists()) {
            val payload = mapOf(
                "participantIds" to listOf(myUid, otherUid),
                "participantNames" to mapOf(
                    myUid to myProfile.displayName.ifBlank { "You" },
                    otherUid to otherUser.displayName.ifBlank { "Friend" }
                ),
                "lastMessage" to "Started conversation",
                "lastMessageSenderId" to myUid,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                docRef.set(payload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, docRef.path)
                throw e
            }
        }
        return convId
    }

    // --- Messages ---

    fun observeMessages(conversationId: String): Flow<List<Message>> = flow {
        val uid = requireUserId()
        val path = "conversations/$conversationId/messages"
        emitAll(
            db.collection("conversations").document(conversationId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot -> snapshot.toObjects(Message::class.java) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun sendMessage(
        conversationId: String,
        otherUserId: String,
        senderName: String,
        text: String
    ) {
        val uid = requireUserId()
        require(text.isNotBlank()) { "Message text cannot be blank" }
        val messagesRef = db.collection("conversations").document(conversationId).collection("messages")
        val newMsgRef = messagesRef.document()

        val payload = mapOf(
            "conversationId" to conversationId,
            "senderId" to uid,
            "senderName" to senderName,
            "participantIds" to listOf(uid, otherUserId).distinct(),
            "text" to text.trim(),
            "reactions" to emptyMap<String, String>(),
            "createdAt" to FieldValue.serverTimestamp()
        )

        try {
            newMsgRef.set(payload).await()
            // Update conversation last message snippet
            db.collection("conversations").document(conversationId).update(
                mapOf(
                    "lastMessage" to text.trim(),
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, newMsgRef.path)
            throw e
        }
    }

    suspend fun toggleReaction(
        conversationId: String,
        messageId: String,
        currentReactions: Map<String, String>,
        emoji: String
    ) {
        val uid = requireUserId()
        val msgRef = db.collection("conversations").document(conversationId)
            .collection("messages").document(messageId)

        val updatedReactions = currentReactions.toMutableMap()
        if (updatedReactions[uid] == emoji) {
            updatedReactions.remove(uid)
        } else {
            updatedReactions[uid] = emoji
        }

        try {
            msgRef.update("reactions", updatedReactions).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            throw e
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String) {
        val uid = requireUserId()
        val msgRef = db.collection("conversations").document(conversationId)
            .collection("messages").document(messageId)
        try {
            msgRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, msgRef.path)
            throw e
        }
    }
}
