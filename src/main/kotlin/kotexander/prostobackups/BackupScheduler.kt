package kotexander.prostobackups

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import net.minecraft.server.MinecraftServer
import java.lang.Thread.sleep
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
object BackupScheduler {
    // Use AtomicLong since this is modified on the server thread but read/reset on a Coroutine worker thread
    private val ticksPlayedToday = AtomicLong(0)

    private val cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
    private val parser = CronParser(cronDefinition)
    private val cron = parser.parse("0 3 * * *")
    private val executionTime = ExecutionTime.forCron(cron)

    private var executor: ScheduledExecutorService? = null

    fun start(server: MinecraftServer) {
        stop()

        executor = Executors.newSingleThreadScheduledExecutor()
        scheduleNext(server)
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null

        ticksPlayedToday.store(0)
    }

    fun tick(server: MinecraftServer) {
        if (server.playerCount > 0) {
            ticksPlayedToday.incrementAndFetch()
        }
    }

    private fun scheduleNext(server: MinecraftServer) {
        val currentExecutor = executor ?: return
        if (currentExecutor.isShutdown) return

        val delay = getUntilNextRun()

        currentExecutor.schedule(
            {
                try {
                    if (getTimePlayedToday() > 10.minutes) {
                        ticksPlayedToday.store(0)
                        server.execute {
                            BackupManager.start(server.createCommandSourceStack())
                        }
                    }
                } catch (e: Exception) {
                    ProstoBackups.LOGGER.error("Error occurred during scheduled backup evaluation", e)
                } finally {
                    sleep(100) // there was an issue where 2 backups started for one cron job
                    scheduleNext(server)
                }
            },
            delay.inWholeMilliseconds, TimeUnit.MILLISECONDS,
        )
    }

    fun getTimePlayedToday(): Duration {
        return (ticksPlayedToday.load() / 20).seconds
    }

    fun getUntilNextRun(): Duration {
        val now = ZonedDateTime.now()
        val next = executionTime.nextExecution(now)

        return if (next.isPresent) {
            // round to nearest second
           java.time.Duration.between(now, next.get()).seconds.seconds
        } else {
            ProstoBackups.LOGGER.error("Cron calculation failed! Defaulting to 2 hours.")
            2.hours
        }
    }
}