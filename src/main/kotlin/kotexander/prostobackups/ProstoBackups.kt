package kotexander.prostobackups

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.commands.Commands
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.Permissions
import org.slf4j.LoggerFactory

object ProstoBackups : ModInitializer {
	const val MOD_ID: String = "prostobackups"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		CommandRegistrationCallback.EVENT.register { dispatcher, context, selection ->
			dispatcher.register(
				Commands.literal("backup")
					.requires { source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
					.executes { context ->
						val source = context.source;
						BackupManager.startBackup(source);
						1
					})
		}

		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			BackupScheduler.start(server)
		}

		ServerLifecycleEvents.SERVER_STOPPING.register { server ->
			BackupScheduler.stop()
		}
	}
}
