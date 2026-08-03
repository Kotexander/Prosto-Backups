package kotexander.prostobackups

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.Deflater
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.*

@OptIn(ExperimentalAtomicApi::class)
object BackupManager {
    private val isBackupRunning = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)

    fun start(source: CommandSourceStack) {
        val server = source.server

        if (!isBackupRunning.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("A backup is already in progress!"))
            return
        }

        source.sendSuccess({ Component.literal("Starting backup...") }, true)
        server.isAutoSave = false

        // Save world
        val saveSuccess = server.saveEverything(false, true, true)
        if (!saveSuccess) {
            source.sendFailure(Component.literal("Failed to save the world before backup."))
            server.isAutoSave = true
            isBackupRunning.store(false)
            return
        }

        // Get world folder
        val worldDir = server.getWorldPath(LevelResource.ROOT)

        // Name backup file
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val backupsFolder = Path("backups")
        val zipFile = backupsFolder / "world-$timestamp.zip"
        backupsFolder.createDirectories()

        thread(name = "Backup thread") {
            try {
                val ret = createZipBackup(worldDir, zipFile)
                if (!ret) {
                    ProstoBackups.LOGGER.warn("Backup was interrupted.")
                }
                server.execute {
                    if (ret) {
                        source.sendSuccess({ Component.literal("Backup completed!") }, true)
                    } else {
                        source.sendFailure(Component.literal("Backup was interrupted."))
                    }
                }
            } catch (e: Exception) {
                server.execute {
                    source.sendFailure(Component.literal("Backup failed: ${e.message}"))
                }
            } finally {
                server.execute {
                    server.isAutoSave = true
                    shouldStop.store(false)
                    isBackupRunning.store(false)
                }
            }
        }
    }

    private fun createZipBackup(sourceDir: Path, destPath: Path): Boolean {
        var isCancelled = false
        try {
            val buffer = ByteArray(8192)
            ZipOutputStream(destPath.outputStream()).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                val files = sourceDir.walk()
                    .filter { it.isRegularFile() }
                    .filter { it.name != "session.lock" }
                    .filter { !it.startsWith(destPath) }

                for (file in files) {
                    // Gets the relative path and automatically standardizes slashes
                    val relativePath = (Path("world") / file.relativeTo(sourceDir)).invariantSeparatorsPathString

                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { input ->
                        while (true) {
                            // check if we should stop and cleanup
                            if (shouldStop.load()) {
                                isCancelled = true
                                return@use
                            }

                            val read = input.read(buffer)
                            if (read == -1) break
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
            }
        } catch (e: Exception) {
            destPath.deleteIfExists()
            throw e
        }

        if (isCancelled || shouldStop.load()) {
            destPath.deleteIfExists()
            return false
        }

        return true
    }

    fun cancel() {
        shouldStop.store(true)
    }
}

