package kotexander.prostobackups

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread
import java.nio.file.Path
import kotlin.io.path.*

@OptIn(ExperimentalAtomicApi::class)
object BackupManager {
    private val isBackupRunning = AtomicBoolean(false)

    fun startBackup(source: CommandSourceStack) {
        val server = source.server

        if (!isBackupRunning.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("A backup is already in progress!"))
            return
        }

        server.execute {
            source.sendSuccess({ Component.literal("Starting backup...") }, true)
            server.isAutoSave = false

            val saveSuccess = server.saveEverything(false, true, true)
            if (!saveSuccess) {
                source.sendFailure(Component.literal("Failed to save the world before backup!"))
                server.isAutoSave = true
                isBackupRunning.store(false)
                return@execute
            }

            val worldDir = Path(server.getWorldPath(LevelResource.ROOT).toString())

            thread(name = "Backup thread") {
                try {
                    val zipPath = createZipBackup(worldDir)
                    source.sendSuccess({ Component.literal("Backup completed: ${zipPath.name}") }, true)
                } catch (e: Exception) {
                    e.printStackTrace()
                    source.sendFailure(Component.literal("Backup failed: ${e.message}"))
                } finally {
                    server.execute {
                        server.isAutoSave = true
                        isBackupRunning.store(false)
                    }
                }
            }
        }
    }

    fun createZipBackup(sourceDir: Path): Path {
        val backupsFolder = Path("backups")
        backupsFolder.createDirectories()

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val zipFile = backupsFolder / "world-$timestamp.zip"

        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            sourceDir.walk()
                .filter { it.isRegularFile() }
                .filter { it.name != "session.lock" }
                .forEach { file ->
                    // Gets the relative path and automatically standardizes slashes
                    val relativePath = file.relativeTo(sourceDir).invariantSeparatorsPathString

                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }

        return zipFile
    }
}