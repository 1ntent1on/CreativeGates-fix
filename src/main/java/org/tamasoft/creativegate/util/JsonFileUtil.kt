package org.tamasoft.creativegate.util

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import kotlin.jvm.Throws


object JsonFileUtil {

    val jsonMapper = ObjectMapper()

    @Throws(IOException::class)
    fun <T> read(file: File, cls: Class<T>): T {
        return jsonMapper.readValue(file, cls)
    }

    @Throws(IOException::class)
    fun <T> write(file: File, value: T) {
        return jsonMapper.writeValue(file, value)
    }

    /** Zápis přes dočasný soubor a atomický přesun – při pádu zůstane starý soubor celý. */
    @Throws(IOException::class)
    fun <T> writeAtomic(file: File, value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        jsonMapper.writeValue(tmp, value)
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}