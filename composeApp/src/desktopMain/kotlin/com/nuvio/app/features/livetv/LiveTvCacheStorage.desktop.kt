package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal actual object LiveTvCacheStorage {
    private val directory: Path by lazy {
        DesktopStorage.cacheDir.resolve("livetv-cache").also { Files.createDirectories(it) }
    }

    actual fun read(name: String): String? {
        val file = directory.resolve(name)
        return if (Files.exists(file)) runCatching { Files.readString(file) }.getOrNull() else null
    }

    actual fun write(name: String, value: String) {
        runCatching {
            val target = directory.resolve(name)
            val temp = directory.resolve("$name.tmp")
            Files.writeString(temp, value)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    actual fun remove(name: String) {
        runCatching { Files.deleteIfExists(directory.resolve(name)) }
    }
}
