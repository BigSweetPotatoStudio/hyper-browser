package com.dadigua.hyperbrowser.data

import android.util.AtomicFile
import java.io.File
import java.io.IOException

internal object AtomicFileWriter {
    @Synchronized
    fun writeText(file: File, value: String) {
        val parent = file.absoluteFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw IOException("Unable to create directory for ${file.absolutePath}")
        }

        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(output) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }
}
