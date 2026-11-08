const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const PROJECT_ID = "demo-no-project";
const DATABASE_ID = "ai-studio-android-peerchat-9dd241d1-87be-4ea2-8560-535a9776974b";

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      host: "127.0.0.1",
      port: 8085,
      rules: fs.readFileSync("firestore.rules", "utf8")
    }
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("unauthenticated user cannot read or write user profiles", async () => {
  const unauthedDb = testEnv.unauthenticatedContext().firestore({ databaseId: DATABASE_ID });
  const userRef = unauthedDb.collection("users").doc("alice");

  await assertFails(userRef.get());
  await assertFails(userRef.set({
    userId: "alice",
    displayName: "Alice",
    username: "alice_u",
    status: "online"
  }));
});

test("authenticated user can create own profile but not another user profile", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").firestore({ databaseId: DATABASE_ID });
  const aliceRef = aliceDb.collection("users").doc("alice");
  const bobRef = aliceDb.collection("users").doc("bob");

  await assertSucceeds(aliceRef.set({
    userId: "alice",
    displayName: "Alice Smith",
    username: "alice_s",
    status: "online"
  }));

  await assertFails(bobRef.set({
    userId: "bob",
    displayName: "Bob Jones",
    username: "bob_j",
    status: "online"
  }));
});

test("participant can create and read conversations and messages; non-participant is denied", async () => {
  const aliceDb = testEnv.authenticatedContext("alice").firestore({ databaseId: DATABASE_ID });
  const bobDb = testEnv.authenticatedContext("bob").firestore({ databaseId: DATABASE_ID });
  const charlieDb = testEnv.authenticatedContext("charlie").firestore({ databaseId: DATABASE_ID });

  const convRefAlice = aliceDb.collection("conversations").doc("alice_bob");
  const convRefCharlie = charlieDb.collection("conversations").doc("alice_bob");

  // Alice creates conversation with Bob
  await assertSucceeds(convRefAlice.set({
    participantIds: ["alice", "bob"],
    participantNames: { "alice": "Alice", "bob": "Bob" },
    lastMessage: "Hey Bob!",
    lastMessageSenderId: "alice"
  }));

  // Alice and Bob can read it
  await assertSucceeds(convRefAlice.get());
  const convRefBob = bobDb.collection("conversations").doc("alice_bob");
  await assertSucceeds(convRefBob.get());

  // Charlie cannot read or send message to this conversation
  await assertFails(convRefCharlie.get());

  const msgRefAlice = convRefAlice.collection("messages").doc("msg1");
  await assertSucceeds(msgRefAlice.set({
    conversationId: "alice_bob",
    senderId: "alice",
    senderName: "Alice",
    participantIds: ["alice", "bob"],
    text: "Hello Bob! How are you?"
  }));

  // Bob can read the message
  const msgRefBob = convRefBob.collection("messages").doc("msg1");
  await assertSucceeds(msgRefBob.get());

  // Charlie cannot read or write to messages
  const msgRefCharlie = convRefCharlie.collection("messages").doc("msg2");
  await assertFails(msgRefCharlie.get());
  await assertFails(msgRefCharlie.set({
    conversationId: "alice_bob",
    senderId: "charlie",
    senderName: "Charlie",
    participantIds: ["alice", "bob"],
    text: "Intruding!"
  }));
});
