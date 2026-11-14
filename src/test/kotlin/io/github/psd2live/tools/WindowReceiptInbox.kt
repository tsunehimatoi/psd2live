package io.github.psd2live.tools

import io.github.psd2live.util.PresentationReceipt
import kotlinx.coroutines.channels.Channel

/** Separates already delivered setup frames from a measured activation, without filtering late deliveries. */
internal class WindowReceiptInbox(private val clock: () -> Long = System::nanoTime) {
    data class Received(val sequence: Long, val receivedAtNanos: Long, val receipt: PresentationReceipt)
    data class Boundary(val atNanos: Long, val received: List<Received>)
    private val lock = Any()
    private val channel = Channel<Received>(Channel.UNLIMITED)
    private val all = ArrayList<Received>()

    fun record(receipt: PresentationReceipt) = synchronized(lock) {
        val received = Received(all.size.toLong() + 1, clock(), receipt)
        all += received
        check(channel.trySend(received).isSuccess)
    }

    /** Called just before the explicit refresh pair. Arrivals after this cut stay in the measured queue. */
    fun beforeActivation(): Boundary = synchronized(lock) {
        val before = ArrayList<Received>()
        while (true) before += channel.tryReceive().getOrNull() ?: break
        Boundary(clock(), before)
    }

    suspend fun receive(): Received = channel.receive()
    fun extra(): Received? = channel.tryReceive().getOrNull()
    fun raw(): List<Received> = synchronized(lock) { all.toList() }
}
