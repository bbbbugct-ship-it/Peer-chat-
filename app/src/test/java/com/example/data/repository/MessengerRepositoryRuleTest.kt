package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessengerRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun unauthenticatedUser_failsToWriteProfile() = runBlocking {
    val repository = MessengerRepository(firestore, auth)
    try {
      repository.saveUserProfile(
        displayName = "Anonymous",
        username = "anon",
        bio = "test",
        avatarColor = "#0084FF",
        appPassword = ""
      )
      fail("Expected exception for unauthenticated user")
    } catch (e: Exception) {
      assertTrue(e is IllegalStateException)
    }
  }

  @Test
  fun authenticatedUser_canSaveAndReadOwnProfile() = runBlocking {
    val uid = signInTestUser("alice_rule@test.com")
    val repository = MessengerRepository(firestore, auth)

    repository.saveUserProfile(
      displayName = "Alice Wonderland",
      username = "alicew",
      bio = "Curiouser and curiouser",
      avatarColor = "#0084FF",
      appPassword = "pin1234"
    )

    val doc = firestore.collection("users").document(uid).get().await()
    assertTrue(doc.exists())
    assertEquals("Alice Wonderland", doc.getString("displayName"))
    assertEquals("alicew", doc.getString("username"))
  }

  @Test
  fun conversation_participantsCanCreateAndMessage() = runBlocking {
    val aliceUid = signInTestUser("alice_chat@test.com")
    val repositoryAlice = MessengerRepository(firestore, auth)

    val bobUid = "bob_target_uid"
    val otherProfile = UserProfile(
      userId = bobUid,
      displayName = "Bob",
      username = "bobj"
    )
    val myProfile = UserProfile(
      userId = aliceUid,
      displayName = "Alice",
      username = "alicec"
    )

    val convId = repositoryAlice.getOrCreateConversation(myProfile, otherProfile)
    assertNotNull(convId)

    repositoryAlice.sendMessage(convId, bobUid, "Alice", "Hello from Alice!")

    val messages = firestore.collection("conversations").document(convId)
      .collection("messages").get().await()
    assertEquals(1, messages.size())
    assertEquals("Hello from Alice!", messages.documents[0].getString("text"))
  }
}
