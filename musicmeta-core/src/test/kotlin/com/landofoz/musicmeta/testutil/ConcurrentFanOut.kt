package com.landofoz.musicmeta.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs [work] once per index in `0 until threads`, each on `Dispatchers.Default`, all released
 * together by a spin barrier, and returns once every arm has finished.
 *
 * A barrier rather than a delay: the arms have to be inside [work] at the same instant for a
 * write-write race to be reachable at all, and no wall-clock wait makes that true — a test that
 * spends one asserts the runner's load instead (`docs/pitfalls.md` §38). Real threads for the same
 * reason: `runTest`'s virtual time serialises the race away.
 */
// InjectDispatcher: the races this reproduces exist only on real threads.
@Suppress("InjectDispatcher")
suspend fun fanOutOnRealThreads(threads: Int, work: suspend (thread: Int) -> Unit) {
    val ready = AtomicInteger()
    coroutineScope {
        for (thread in 0 until threads) {
            launch(Dispatchers.Default) {
                ready.incrementAndGet()
                while (ready.get() < threads) {
                    Thread.onSpinWait()
                }
                work(thread)
            }
        }
    }
}
