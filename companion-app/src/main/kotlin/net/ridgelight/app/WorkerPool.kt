package net.ridgelight.app

import net.ridgelight.core.analysis.TaskRunner
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Fixed thread pool for the engine's terrain rays. */
class WorkerPool(override val parallelism: Int) : TaskRunner {
    private val executor = Executors.newFixedThreadPool(parallelism) { r ->
        Thread(r, "ridgelight-worker").apply { isDaemon = true }
    }

    override fun runAll(tasks: List<() -> Unit>) {
        // get() rethrows a task's exception and makes its writes visible here.
        executor.invokeAll(tasks.map { task -> Callable { task() } }).forEach { it.get() }
    }

    fun shutdown() = executor.shutdownNow()
}
