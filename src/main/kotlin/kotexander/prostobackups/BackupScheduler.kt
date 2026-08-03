package kotexander.prostobackups

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import net.minecraft.server.MinecraftServer
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object BackupScheduler {
    private val ticksPlayedToday = AtomicLong(0)

    private val cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
    private val parser = CronParser(cronDefinition)
    private val cron = parser.parse("0 3 * * *")
    private val executionTime = ExecutionTime.forCron(cron)

    @Volatile
    private var executor: ScheduledExecutorService? = null

    fun start(server: MinecraftServer) {
        stop()
        executor = Executors.newSingleThreadScheduledExecutor()
        scheduleNext(server)
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        ticksPlayedToday.set(0)
    }

    fun tick(server: MinecraftServer) {
        if (server.playerCount > 0) {
            ticksPlayedToday.incrementAndGet()
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
                        ticksPlayedToday.set(0)
                        server.execute {
                            BackupManager.start(server.createCommandSourceStack())
                        }
                    }
                } catch (_: InterruptedException) {
                    // Task was cancelled during shutdown; clean exit
                } catch (e: Exception) {
                    ProstoBackups.LOGGER.error("Error occurred during scheduled backup evaluation", e)
                } finally {
                    if (executor?.isShutdown == false) {
                        scheduleNext(server)
                    }
                }
            },
            delay.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
    }

    fun getTimePlayedToday(): Duration {
        return (ticksPlayedToday.get() / 20).seconds
    }

    fun getUntilNextRun(): Duration {
        val now = ZonedDateTime.now().plusSeconds(1) // avoid getting next as 0ms
        val next = executionTime.nextExecution(now)

        return if (next.isPresent) {
            // round to nearest second
            java.time.Duration.between(ZonedDateTime.now(), next.get()).toSeconds().seconds
        } else {
            ProstoBackups.LOGGER.error("Cron calculation failed! Defaulting to 2 hours.")
            2.hours
        }
    }
}