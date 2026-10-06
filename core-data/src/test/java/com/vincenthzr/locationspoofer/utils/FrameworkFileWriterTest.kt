package com.vincenthzr.locationspoofer.utils

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrameworkFileWriterTest {
    @Test fun shorterSnapshotReplacesNonTruncatingRemoteDescriptor() {
        val file = File.createTempFile("framework-snapshot", ".json")
        try {
            file.writeText(JSONObject().put("payload", "old".repeat(4096)).toString())
            val replacement = JSONObject().put("payload", "新的配置").toString().toByteArray(Charsets.UTF_8)
            RandomAccessFile(file, "rw").use { remote ->
                // Exercise both a non-truncating open and an existing descriptor offset.
                remote.seek(19)
                FileOutputStream(remote.fd).use { overwriteFrameworkFile(it, replacement) }
            }
            assertArrayEquals(replacement, file.readBytes())
            assertEquals("新的配置", JSONObject(file.readText()).getString("payload"))
        } finally {
            file.delete()
        }
    }
}
