package kotexander.prostobackups

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.*
import kotlinx.datetime.*
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import kotlin.time.Clock

object BackupScheduler {
    private var scope: CoroutineScope? = null

    // Use AtomicLong since this is modified on the server thread but read/reset on a Coroutine worker thread
    private val ticksPlayedToday = AtomicLong(0)

    init {
        ServerTickEvents.END_SERVER_TICK.register { tickServer ->
            if (tickServer.playerList.playerCount > 0) {
                ticksPlayedToday.incrementAndGet()
            }
        }
    }

    fun start(server: MinecraftServer) {
        stop()

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope?.launch {
            while (isActive) {
                val delayDuration = getDurationUntilTargetTime(targetHour = 3, targetMinute = 0)
                delay(delayDuration)

                val minutesPlayed = ticksPlayedToday.get() / 1200

                if (minutesPlayed > 10) {
                    ticksPlayedToday.set(0)
                    BackupManager.startBackup(server.createCommandSourceStack())
                }
            }
        }
    }

    fun stop() {
        scope?.cancel() // Gracefully cancels all coroutines
        scope = null
        ticksPlayedToday.set(0)
    }

    private fun getDurationUntilTargetTime(targetHour: Int, targetMinute: Int): Duration {
        val tz = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val localNow = now.toLocalDateTime(tz)

        val targetTime = LocalTime(targetHour, targetMinute)

        // Determine the target date based on whether the time has already passed today
        val targetDate = if (localNow.time >= targetTime) {
            localNow.date.plus(DatePeriod(days = 1))
        } else {
            localNow.date
        }

        val targetInstant = targetDate.atTime(targetTime).toInstant(tz)

        return targetInstant - now
    }
}