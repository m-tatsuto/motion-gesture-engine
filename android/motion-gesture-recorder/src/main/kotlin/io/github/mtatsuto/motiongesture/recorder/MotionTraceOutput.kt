package io.github.mtatsuto.motiongesture.recorder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

enum class MotionTraceWriteDisposition {
    WRITTEN,
    BACKPRESSURED,
}

interface MotionTraceOutput {
    val temporaryPath: Path?
    val destinationPath: Path?

    fun start()
    fun write(data: ByteArray): MotionTraceWriteDisposition
    fun commit(): Path?
    fun abortPreservingPartial()
}

/** Streams to a same-directory temporary file and commits with an atomic move. */
class AtomicFileMotionTraceOutput(
    override val destinationPath: Path,
) : MotionTraceOutput {
    override var temporaryPath: Path? = null
        private set

    private var channel: FileChannel? = null
    private var committed = false

    override fun start() {
        check(channel == null && !committed) { "output is not idle" }
        require(destinationPath.fileName.toString().endsWith(".mge.jsonl")) {
            "destination must end in .mge.jsonl"
        }
        require(Files.notExists(destinationPath)) { "destination already exists" }
        val parent = requireNotNull(destinationPath.toAbsolutePath().parent)
        require(Files.isDirectory(parent)) { "destination parent directory does not exist" }
        val temporary = Files.createTempFile(
            parent,
            ".${destinationPath.fileName}.${UUID.randomUUID()}.",
            ".partial",
        )
        temporaryPath = temporary
        channel = FileChannel.open(temporary, StandardOpenOption.WRITE)
    }

    override fun write(data: ByteArray): MotionTraceWriteDisposition {
        val output = checkNotNull(channel) { "output is not started" }
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) output.write(buffer)
        return MotionTraceWriteDisposition.WRITTEN
    }

    override fun commit(): Path? {
        val output = checkNotNull(channel) { "output is not started" }
        val temporary = checkNotNull(temporaryPath)
        output.force(true)
        output.close()
        channel = null
        Files.move(temporary, destinationPath, StandardCopyOption.ATOMIC_MOVE)
        committed = true
        return destinationPath
    }

    override fun abortPreservingPartial() {
        runCatching { channel?.force(true) }
        runCatching { channel?.close() }
        channel = null
    }
}
