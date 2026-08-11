package dev.ilyaask.openir.transport

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Built-in IR blaster via the Bigme vendor kernel character device.
 *
 * Reverse-engineered from `libet_jni_io.so`: the native lib opens the first available of
 * `/dev/hxd_irda`, `/dev/ctrl`, `/dev/irremote` with O_RDWR and does plain `read`/`write` on the
 * fd (no ioctl). We mirror that: a single fd opened R/W, `write()` pushes a frame, `read()` pulls
 * a capture. On a char device `RandomAccessFile` length/pointer semantics don't apply, so `read`
 * does a blocking `FileInputStream.read` on the fd bounded by a thread-join timeout — the closest
 * pure-Java analogue to the native `read(fd, buf, n)`.
 *
 * Note: opening these nodes may require root on some firmware; permission errors are surfaced.
 */
class IoTransport : Transport {
    override val kind = TransportKind.IO

    private var raf: RandomAccessFile? = null
    private var fin: FileInputStream? = null
    private var fout: FileOutputStream? = null
    private var nodePath: String? = null

    private fun resolveNode(): String? =
        NODES.firstOrNull { File(it).exists() && File(it).canWrite() }
            ?: NODES.firstOrNull { File(it).exists() }

    override suspend fun open(): OpenResult {
        val path = resolveNode() ?: return OpenResult.Failure(
            "No vendor IR device node found (looked for ${NODES.joinToString()}). " +
                "Your device may not have a built-in IR LED."
        )
        return try {
            val f = RandomAccessFile(path, "rw")
            raf = f
            val fd = f.fd
            fin = FileInputStream(fd)
            fout = FileOutputStream(fd)
            nodePath = path
            Log.i(TAG, "Opened IR node $path")
            OpenResult.Success
        } catch (e: SecurityException) {
            OpenResult.Failure(selinuxMsg(path))
        } catch (e: Exception) {
            // FileNotFoundException wrapping EACCES lands here — also a SELinux denial.
            if (e.message?.contains("EACCES") == true) OpenResult.Failure(selinuxMsg(path))
            else OpenResult.Failure("Could not open $path: ${e.message}")
        }
    }

    private fun selinuxMsg(path: String) =
        "Permission denied opening $path. The Hibreak Pro IR node is gated by SELinux " +
            "(label ctrl_device); this build targets SDK 27 to run in the untrusted_app_27 " +
            "domain the vendor policy allows. If you still see this, the device likely needs " +
            "root (setenforce 0) or a system-app install to access the IR node."

    override suspend fun write(frame: ByteArray): Int = try {
        Log.i(TAG, "write: ${frame.size} B head=${frame.take(8).joinToString("") { "%02x".format(it) }}")
        fout?.write(frame); fout?.flush(); frame.size
    } catch (e: Exception) {
        Log.e(TAG, "write failed", e); -1
    }

    override suspend fun read(into: ByteArray, timeoutMs: Long): Int {
        val fis = fin ?: return -1
        var result = -1
        val reader = Thread {
            try { result = fis.read(into) } catch (e: Exception) { Log.e(TAG, "read failed", e); result = -1 }
        }.apply { isDaemon = true }
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            reader.interrupt() // best-effort; a blocked native read may not unblock until data arrives
            return 0
        }
        return result
    }

    override fun close() {
        try { fin?.close() } catch (_: Exception) {}
        try { fout?.close() } catch (_: Exception) {}
        try { raf?.close() } catch (_: Exception) {}
        fin = null; fout = null; raf = null
    }

    companion object {
        private const val TAG = "OpenIR/IoTransport"
        private val NODES = listOf("/dev/ctrl", "/dev/hxd_irda", "/dev/irremote")
    }
}
