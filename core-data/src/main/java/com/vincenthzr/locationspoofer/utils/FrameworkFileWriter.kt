package com.vincenthzr.locationspoofer.utils

import java.io.FileOutputStream

/** Remote file descriptors may open an existing file without O_TRUNC. */
internal fun overwriteFrameworkFile(output: FileOutputStream, payload: ByteArray) {
    output.channel.truncate(0)
    output.channel.position(0)
    output.write(payload)
}
