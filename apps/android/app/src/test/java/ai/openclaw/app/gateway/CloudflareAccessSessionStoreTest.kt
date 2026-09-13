package ai.openclaw.app.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudflareAccessSessionStoreTest {
  private val application = CloudflareAccessTestTokens.application

  private class Storage {
    val values = mutableMapOf<CloudflareAccessOrigin, String>()
    val events = mutableListOf<String>()
    var saveSucceeds = true
    var deleteSucceeds = true
    val persistence =
      CloudflareAccessSessionStore.Persistence(
        load = { values[it] },
        save = { origin, value ->
          events += "save"
          if (saveSucceeds) values[origin] = value
          saveSucceeds
        },
        delete = {
          events += "delete"
          if (deleteSucceeds) values.remove(it)
          deleteSucceeds
        },
      )
  }

  @Test fun concurrentRolesShareOneAttemptAndRetireBeforePublish() =
    runTest {
      val storage = Storage()
      val grant = CompletableDeferred<CloudflareAccessSession>()
      var attempts = 0
      val store =
        CloudflareAccessSessionStore(backgroundScope, storage.persistence, authenticate = { _, _ ->
          attempts++
          grant.await()
        }, retireTransports = { storage.events += "retire" })
      val first = store.signIn(application) {}
      val second = store.signIn(application.copy()) {}
      assertSame(first, second)
      runCurrent()
      assertEquals(1, attempts)
      assertEquals(CloudflareAccessSessionStore.State.SigningIn, store.state(application.origin))
      grant.complete(CloudflareAccessTestTokens.session())
      val snapshot = first.await()
      assertEquals(listOf("retire", "delete", "save"), storage.events)
      assertSame(snapshot, store.snapshot(application.origin))
      assertEquals(CloudflareAccessSessionStore.State.Authenticated, store.state(application.origin))
    }

  @Test fun differentApplicationsReplaceTheAttemptWithoutAcceptingItsLateGrant() =
    runTest {
      for (unconfined in listOf(false, true)) {
        for (differentIssuer in listOf(false, true)) {
          val replacement =
            if (differentIssuer) {
              application.copy(issuer = CloudflareAccessJWT.issuer("other.cloudflareaccess.com"))
            } else {
              application.copy(audience = "other-audience")
            }
          val storage = Storage()
          val firstGrant = CompletableDeferred<CloudflareAccessSession>()
          val secondGrant = CompletableDeferred<CloudflareAccessSession>()
          val owner = SupervisorJob()
          val scope = CoroutineScope(owner + if (unconfined) Dispatchers.Unconfined else StandardTestDispatcher(testScheduler))
          val applications = mutableListOf<CloudflareAccessApplication>()
          val firstSession = CloudflareAccessTestTokens.session()
          val secondSession = sessionFor(replacement)
          val store =
            CloudflareAccessSessionStore(scope, storage.persistence, authenticate = { selected, _ ->
              applications += selected
              if (selected == application) {
                withContext(NonCancellable) { firstGrant.await() }
              } else {
                assertEquals(replacement, selected)
                secondGrant.await()
              }
            }, retireTransports = { storage.events += "retire" })
          try {
            val first = store.signIn(application) {}
            runCurrent()
            assertEquals(listOf(application), applications)
            val second = store.signIn(replacement) {}
            assertNotSame(first, second)
            assertTrue(first.isCancelled)
            assertSame(second, store.signIn(replacement.copy()) {})
            runCurrent()
            assertEquals(listOf(application, replacement), applications)
            secondGrant.complete(secondSession)
            val snapshot = second.await()
            assertEquals(replacement, snapshot.session.application)

            // A ignores cancellation until after B commits. Its exact attempt ID
            // must fence both persistence and terminal cleanup when it returns.
            firstGrant.complete(firstSession)
            assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
            assertSame(snapshot, store.snapshot(application.origin))
            assertEquals(CloudflareAccessSessionStore.State.Authenticated, store.state(application.origin))
            assertEquals(listOf("retire", "delete", "save"), storage.events)
            val persisted = CloudflareAccessSession.decode(checkNotNull(storage.values[application.origin]))
            assertEquals(replacement, persisted.application)
            assertEquals(secondSession.subject, persisted.subject)
          } finally {
            firstGrant.complete(firstSession)
            secondGrant.complete(secondSession)
            owner.cancelAndJoin()
          }
        }
      }
    }

  @Test fun differentApplicationBeforeDispatchDoesNotStartOrRetainTheOldTransfer() =
    runTest {
      val replacement = application.copy(audience = "other-audience")
      val session = sessionFor(replacement)
      val grant = CompletableDeferred<CloudflareAccessSession>()
      val applications = mutableListOf<CloudflareAccessApplication>()
      val storage = Storage()
      val store =
        CloudflareAccessSessionStore(backgroundScope, storage.persistence, authenticate = { selected, _ ->
          applications += selected
          grant.await()
        }, retireTransports = { storage.events += "retire" })
      val first = store.signIn(application) {}
      val second = store.signIn(replacement) {}
      try {
        assertNotSame(first, second)
        assertTrue(first.isCancelled)
        assertTrue(applications.isEmpty())
        runCurrent()
        assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(replacement), applications)
        assertSame(second, store.signIn(replacement.copy()) {})
        grant.complete(session)
        val snapshot = second.await()
        assertSame(snapshot, store.snapshot(application.origin))
        assertEquals(listOf("retire", "delete", "save"), storage.events)
      } finally {
        grant.complete(session)
        first.cancelAndJoin()
        second.cancelAndJoin()
      }
    }

  private fun sessionFor(application: CloudflareAccessApplication): CloudflareAccessSession {
    val subject = "replacement-subject"
    val expires = System.currentTimeMillis() / 1000.0 + 3600
    val claims =
      JsonObject(
        CloudflareAccessTestTokens.claims(subject, expires) +
          mapOf(
            "iss" to JsonPrimitive(application.issuer.toString()),
            "aud" to JsonArray(listOf(JsonPrimitive(application.audience))),
          ),
      )
    return CloudflareAccessSession(application, subject, expires, CloudflareAccessTestTokens.token(claims))
  }

  @Test fun grantExpiringDuringRetirementIsNeverPersistedOrPublished() =
    runTest {
      val storage = Storage()
      val retirement = CompletableDeferred<Unit>()
      var now = 1000.0
      supervisorScope {
        val store = CloudflareAccessSessionStore(this, storage.persistence, authenticate = { _, _ -> CloudflareAccessTestTokens.session(expires = 1001.0) }, now = { now }, retireTransports = { retirement.await() })
        val attempt = store.signIn(application) {}
        runCurrent()
        now = 1001.0
        retirement.complete(Unit)
        assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CloudflareAccessException)
        assertFalse("save" in storage.events)
        assertNull(store.snapshot(application.origin))
        assertEquals(CloudflareAccessSessionStore.State.ReauthenticationRequired, store.state(application.origin))
      }
    }

  @Test fun forgetCannotBeUndoneByLateTransferCompletion() =
    runTest {
      val storage = Storage()
      val grant = CompletableDeferred<CloudflareAccessSession>()
      val store = CloudflareAccessSessionStore(backgroundScope, storage.persistence, authenticate = { _, _ -> withContext(NonCancellable) { grant.await() } }, retireTransports = {})
      val attempt = store.signIn(application) {}
      runCurrent()
      store.forget(application.origin).task.await()
      grant.complete(CloudflareAccessTestTokens.session())
      assertTrue(runCatching { attempt.await() }.isFailure)
      assertNull(store.snapshot(application.origin))
      assertEquals(CloudflareAccessSessionStore.State.SignedOut, store.state(application.origin))
      assertFalse("save" in storage.events)
    }

  @Test fun oldSocketFailureCannotInvalidateDifferentAccountRenewal() =
    runTest {
      val storage = Storage()
      var subject = "first-subject"
      val store = CloudflareAccessSessionStore(backgroundScope, storage.persistence, authenticate = { _, _ -> CloudflareAccessTestTokens.session(subject) }, retireTransports = {})
      val first = store.signIn(application) {}.await()
      subject = "second-subject"
      val second = store.signIn(application) {}.await()
      store.requireReauthentication(application.origin, first.revision)?.task?.await()
      assertEquals("second-subject", store.snapshot(application.origin)?.session?.subject)
      assertTrue(second.revision > first.revision)
      store.requireReauthentication(application.origin, second.revision)?.task?.await()
      assertNull(store.snapshot(application.origin))
      assertFalse(storage.values.containsKey(application.origin))
    }

  @Test fun restartRestoresOnlyUnexpiredExactAuthorityAndStorageFailureIsVisible() =
    runTest {
      val storage = Storage()
      storage.values[application.origin] = CloudflareAccessTestTokens.session(expires = 1001.0).encode()
      var now = 1000.0
      val store = CloudflareAccessSessionStore(backgroundScope, storage.persistence, now = { now }, retireTransports = {})
      assertNotNull(store.snapshot(application.origin))
      assertNull(store.snapshot(CloudflareAccessOrigin.from("https://gateway.example.test")))
      now = 1001.0
      assertNull(store.snapshot(application.origin))
      runCurrent()
      assertFalse(storage.values.containsKey(application.origin))
      storage.saveSucceeds = false
      supervisorScope {
        val failing = CloudflareAccessSessionStore(this, storage.persistence, authenticate = { _, _ -> CloudflareAccessTestTokens.session() }, retireTransports = {})
        assertTrue(runCatching { failing.signIn(application) {}.await() }.isFailure)
        assertNull(failing.snapshot(application.origin))
      }
    }

  @Test fun cancelledAttemptDoesNotPublishWhileRetirementFinishes() =
    runTest {
      val storage = Storage()
      val retirement = CompletableDeferred<Unit>()
      val store = CloudflareAccessSessionStore(backgroundScope, storage.persistence, authenticate = { _, _ -> CloudflareAccessTestTokens.session() }, retireTransports = { retirement.await() })
      val attempt = store.signIn(application) {}
      runCurrent()
      store.cancelSignIn(application.origin)
      retirement.complete(Unit)
      assertTrue(runCatching { attempt.await() }.isFailure)
      assertFalse("save" in storage.events)
      assertNull(store.snapshot(application.origin))
    }

  @Test fun failedTransportRetirementCannotDeleteOrPublishAReplacementGrant() =
    runTest {
      val storage = Storage()
      val encoded = CloudflareAccessTestTokens.session().encode()
      storage.values[application.origin] = encoded
      supervisorScope {
        val failure = java.io.IOException("test-only retirement failure")
        val store = CloudflareAccessSessionStore(this, storage.persistence, authenticate = { _, _ -> CloudflareAccessTestTokens.session("replacement") }, retireTransports = { throw failure })
        assertNotNull(store.snapshot(application.origin))
        val thrown = checkNotNull(runCatching { store.signIn(application) {}.await() }.exceptionOrNull())
        assertEquals(failure.javaClass, thrown.javaClass)
        assertEquals(failure.message, thrown.message)
        assertSame(failure, generateSequence(thrown) { it.cause }.last())
        assertNull(store.snapshot(application.origin))
        assertEquals(encoded, storage.values[application.origin])
        assertTrue(storage.events.isEmpty())
        assertEquals(CloudflareAccessSessionStore.State.ReauthenticationRequired, store.state(application.origin))
      }
    }

  @Test fun failedDeletionRetiresAdmissionAndReportsFailureWithoutRewritingPersistence() =
    runTest {
      val storage = Storage()
      val encoded = CloudflareAccessTestTokens.session().encode()
      storage.values[application.origin] = encoded
      storage.deleteSucceeds = false
      supervisorScope {
        val store = CloudflareAccessSessionStore(this, storage.persistence, retireTransports = { storage.events += "retire" })
        assertNotNull(store.snapshot(application.origin))
        val checkpoint = store.admissionCheckpoint()
        val deletion = store.forget(application.origin)
        assertTrue(runCatching { store.requireAdmission(application.origin, checkpoint) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { deletion.task.await() }.exceptionOrNull() is CloudflareAccessException)
        assertTrue(runCatching { store.requireAdmission(application.origin, checkpoint) }.exceptionOrNull() is CancellationException)
        assertNull(store.snapshot(application.origin))
        assertEquals(CloudflareAccessSessionStore.State.SignedOut, store.state(application.origin))
        assertEquals(encoded, storage.values[application.origin])
        assertEquals(listOf("retire", "delete"), storage.events)
      }
    }

  @Test fun queuedAdmissionRemainsRevokedAfterRenewalAndDoesNotBlockAnotherOrigin() =
    runTest {
      val storage = Storage()
      val gate = CompletableDeferred<Unit>()
      val store =
        CloudflareAccessSessionStore(
          backgroundScope,
          storage.persistence,
          authenticate = { _, _ -> CloudflareAccessTestTokens.session() },
          retireTransports = { gate.await() },
        )
      val old = store.admissionCheckpoint()
      val retirement = store.forget(application.origin)
      assertTrue(runCatching { store.requireAdmission(application.origin, old) }.exceptionOrNull() is CancellationException)
      store.requireAdmission(CloudflareAccessOrigin.from("https://other.example.test"), old)
      assertNull(store.snapshot(application.origin))
      gate.complete(Unit)
      retirement.task.await()
      val fresh = store.admissionCheckpoint()
      val grant = store.signIn(application, fresh) {}.await()
      assertTrue(runCatching { store.signIn(application, old) {} }.exceptionOrNull() is CancellationException)
      assertTrue(runCatching { store.withCurrentSnapshot(application.origin, grant.revision, old) { it } }.exceptionOrNull() is CancellationException)
      assertSame(grant, store.withCurrentSnapshot(application.origin, grant.revision, fresh) { it })
    }

  @Test fun unconfinedStartCancellationAndRetirementRunOutsideStoreMonitor() =
    runTest {
      val effects = mutableListOf<Boolean>()
      val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      val gate = CompletableDeferred<CloudflareAccessSession>()
      lateinit var store: CloudflareAccessSessionStore

      fun recordMonitorAvailability() {
        val read = java.util.concurrent.FutureTask { store.admissionCheckpoint() }
        val thread = Thread(read)
        thread.start()
        effects += runCatching { read.get(1, java.util.concurrent.TimeUnit.SECONDS) }.isSuccess
      }
      store =
        CloudflareAccessSessionStore(
          ownedScope,
          Storage().persistence,
          authenticate = { _, _ ->
            recordMonitorAvailability()
            gate.await()
          },
          retireTransports = { recordMonitorAvailability() },
        )
      try {
        val attempt = store.signIn(application) {}
        attempt.invokeOnCompletion { recordMonitorAvailability() }
        store.cancelSignIn(application.origin)
        store.forget(application.origin).task.await()
        assertEquals(listOf(true, true, true), effects)
      } finally {
        gate.complete(CloudflareAccessTestTokens.session())
        ownedScope.cancel()
      }
    }
}
