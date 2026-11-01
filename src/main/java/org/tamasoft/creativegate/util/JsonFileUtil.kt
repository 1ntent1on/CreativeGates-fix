package org.tamasoft.creativegate.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption


object JsonFileUtil {

    // Unknown keys are ignored so a config written by a newer version does not stop the plugin from loading.
    val jsonMapper: ObjectMapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Throws(IOException::class)
    fun <T> read(file: File, cls: Class<T>): T {
        return jsonMapper.readValue(file, cls)
    }

    @Throws(IOException::class)
    fun <T> write(file: File, value: T) {
        return jsonMapper.writeValue(file, value)
    }

    /** Serialises on the calling thread so the result can be written from another thread safely. */
    @Throws(IOException::class)
    fun toJson(value: Any?): String = jsonMapper.writeValueAsString(value)

    /** Writes to a temp file next to the target and moves it over, so a crash never leaves a half-written file. */
    @Throws(IOException::class)
    fun <T> writeAtomic(file: File, value: T) {
        writeAtomicJson(file, jsonMapper.writeValueAsString(value))
    }

    @Throws(IOException::class)
    fun writeAtomicJson(file: File, json: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
