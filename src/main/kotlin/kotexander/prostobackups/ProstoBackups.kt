package kotexander.prostobackups

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import org.slf4j.LoggerFactory

object ProstoBackups : ModInitializer {
    const val MOD_ID: String = "prostobackups"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("backup")
                    .requires { source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER) }
                    .executes { context ->
                        BackupManager.start(context.source)
                        1
                    }.then(
                        Commands.literal("seeNext").executes { context ->
                            context.source.sendSuccess(
                                { Component.literal("Next backup is in ${BackupScheduler.getUntilNextRun()}") },
                                false
                            )
                            1
                        }
                    ).then(Commands.literal("timePlayed").executes { context ->
                        context.source.sendSuccess(
                            { Component.literal("Time played today: ${BackupScheduler.getTimePlayedToday()}") },
                            false
                        )
                        1
                    })
            )
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            BackupScheduler.tick(server)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            BackupScheduler.start(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            BackupScheduler.stop()
        }
    }
}
