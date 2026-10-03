package org.bolusai.next.profile

import org.bolusai.profile.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only wrapper around a synthetic SQLite profile database. It can fail reads or writes with a stable code and hold
 * a confirmation write before it starts or after it committed, so the UI can be observed while an operation is in
 * flight or after a commit whose answer never reached the screen. Every rule still runs in the real adapter.
 */
internal class ControlledProfileRepository(private val delegate: SqliteClinicalProfileRepository) : ClosableProfileRepository {
    @Volatile var readFailure: ProfileFailure? = null
    @Volatile var writeFailure: ProfileFailure? = null
    @Volatile var holdBeforeWrite: CountDownLatch? = null
    @Volatile var holdAfterCommit: CountDownLatch? = null
    @Volatile var committed = CountDownLatch(1)
    /** When set, every read signals [readStarted] and then waits here, so a pending read can be observed. */
    @Volatile var holdBeforeRead: CountDownLatch? = null
    @Volatile var readStarted = CountDownLatch(1)
    val requests = CopyOnWriteArrayList<ConfirmationRequest>()
    val reads = AtomicInteger()

    override fun readVersions(): ProfileRead {
        reads.incrementAndGet()
        return readFailure?.let { ProfileRead.Failed(it) } ?: delegate.readVersions()
    }

    override fun readRecord(): ProfileRecordRead {
        reads.incrementAndGet()
        holdBeforeRead?.let { hold ->
            readStarted.countDown()
            check(hold.await(20, TimeUnit.SECONDS))
        }
        return readFailure?.let { ProfileRecordRead.Failed(it) } ?: delegate.readRecord()
    }
    override fun save(write: ProfileWrite, createdAtEpochMs: Long, writer: String): ProfileSave =
        delegate.save(write, createdAtEpochMs, writer)

    override fun appendConfirmation(request: ConfirmationRequest, recordedAtEpochMs: Long, writer: String,
                                    zones: TimeZoneRules): ConfirmationWrite {
        requests.add(request)
        holdBeforeWrite?.let { check(it.await(20, TimeUnit.SECONDS)) }
        writeFailure?.let { return ConfirmationWrite.Failed(it) }
        val result = delegate.appendConfirmation(request, recordedAtEpochMs, writer, zones)
        committed.countDown()
        holdAfterCommit?.let { check(it.await(20, TimeUnit.SECONDS)) }
        return result
    }

    override fun close() = delegate.close()
}
