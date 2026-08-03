package kotexander.prostobackups

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.Deflater
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.*

object BackupManager {
    @Volatile
    private var isRunning = false

    // This must be called on the server thread
    fun start(source: CommandSourceStack) {
        val server = source.server

        if (isRunning) {
            source.sendFailure(Component.literal("A backup is already in progress!"))
            return
        }
        source.sendSuccess({ Component.literal("Starting backup...") }, true)

        // Save world
        val previousAutoSave = server.isAutoSave
        server.isAutoSave = false
        server.saveEverything(true, true, true)
        isRunning = true

        val worldDir = server.getWorldPath(LevelResource.ROOT)
        CompletableFuture.runAsync {
            val zipPath = newBackupName()
            createZipBackup(worldDir, zipPath)
        }.handleAsync({ _, exception ->
            server.isAutoSave = previousAutoSave
            isRunning = false

            if (exception != null) {
                val msg = exception.cause?.message ?: exception.message ?: "Unknown error"
                ProstoBackups.LOGGER.error("Backup failed", exception)
                source.sendFailure(Component.literal("Backup failed: $msg"))
            } else {
                source.sendSuccess({ Component.literal("Backup completed!") }, true)
            }
        }, server::execute)
    }

    // This must be called on the server thread
    fun isRunning(): Boolean = isRunning

    private fun newBackupName(): Path {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val backupsFolder = Path("backups")
        backupsFolder.createDirectories()
        val zipPath = backupsFolder / "world-$timestamp.zip"

        return zipPath
    }

    private fun createZipBackup(sourceDir: Path, destPath: Path) {
        try {
            ZipOutputStream(destPath.outputStream().buffered()).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)

                val files = sourceDir.walk().filter { it.isRegularFile() }.filter { it.name != "session.lock" }
                for (file in files) {
                    val relativePath = (Path("world") / file.relativeTo(sourceDir)).invariantSeparatorsPathString
                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        } catch (e: Exception) {
            destPath.deleteIfExists()
            throw e
        }
    }
}

