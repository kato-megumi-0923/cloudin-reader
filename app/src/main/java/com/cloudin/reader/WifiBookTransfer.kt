package com.cloudin.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A short-lived HTTP endpoint bound to the tablet's LAN address. */
internal class WifiBookTransfer private constructor(
    private val context: Context,
    private val listener: ServerSocket,
    private val directory: Uri,
    private val directoryName: String,
    private val onResult: (Receipt) -> Unit,
    private val onFinish: () -> Unit
) : AutoCloseable {
    data class Receipt(val name: String, val state: String)

    private val host = "${listener.inetAddress.hostAddress}:${listener.localPort}"
    val address = "http://$host"
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(6))
    private val saveLock = Any()
    private val page = context.resources.openRawResource(R.raw.wifi_book_transfer).use { it.readBytes() }

    fun start() {
        Thread({
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                clients.add(client)
                try {
                    workers.execute {
                        try { client.use(::handle) } catch (_: IOException) { }
                        finally { clients.remove(client) }
                    }
                } catch (_: RejectedExecutionException) {
                    clients.remove(client)
                    runCatching { client.close() }
                }
            }
        }, "Cloudin-Wifi-Accept").start()
    }

    override fun close() {
        runCatching { listener.close() }
        workers.shutdownNow()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val request = input.readHttpLine()?.split(' ') ?: return
        if (request.size != 3 || request[2] !in setOf("HTTP/1.1", "HTTP/1.0")) {
            client.reply(400, "Bad Request")
            return
        }
        val headers = mutableMapOf<String, String>()
        var endOfHeaders = false
        repeat(40) {
            if (!endOfHeaders) {
                val line = input.readHttpLine() ?: throw EOFException()
                if (line.isEmpty()) endOfHeaders = true
                else {
                    val colon = line.indexOf(':')
                    if (colon < 1) throw IOException("Invalid header")
                    headers[line.substring(0, colon).lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
                }
            }
        }
        if (!endOfHeaders || headers["host"] != host || headers["transfer-encoding"] != null) {
            client.reply(400, "Bad Request")
            return
        }
        val origin = headers["origin"]
        val localPost = headers["x-cloudin-transfer"] == "1" && (origin == null || origin == address)
        when {
            request[0] == "GET" && request[1] == "/" -> client.reply(200, "OK", page, "text/html; charset=utf-8")
            request[0] == "GET" && request[1] == "/destination" -> {
                client.json(JSONObject().put("name", directoryName))
            }
            request[0] == "POST" && localPost && request[1] == "/upload" -> {
                val name = Uri.decode(headers["x-book-name"].orEmpty()).substringAfterLast('/').substringAfterLast('\\')
                val length = headers["content-length"]?.toLongOrNull()
                when {
                    name.length !in 1..255 || name.any { it.isISOControl() } ||
                        name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("txt", "epub") ->
                        client.reply(400, "Bad Request")
                    length == null || length <= 0 -> client.reply(400, "Bad Request")
                    length > MAX_UPLOAD_BYTES -> client.reply(413, "Payload Too Large")
                    else -> receive(client, input, length, name)
                }
            }
            request[0] == "POST" && localPost && request[1] == "/finish" -> {
                client.json(JSONObject().put("finished", true))
                onFinish()
            }
            else -> client.reply(404, "Not Found")
        }
    }

    private fun receive(client: Socket, input: BufferedInputStream, length: Long, name: String) {
        val state = try {
            val temp = File.createTempFile("wifi-book-", ".part", context.cacheDir)
            try {
                temp.outputStream().buffered().use { output ->
                    var remaining = length
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (read < 0) throw EOFException()
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                synchronized(saveLock) {
                    if (saveFile(temp, name)) importBook(temp, name) else "duplicate"
                }
            } finally { temp.delete() }
        } catch (_: Exception) {
            "failed"
        }
        onResult(Receipt(name, state))
        client.json(JSONObject().put("state", state))
    }

    private fun saveFile(temp: File, name: String): Boolean {
        if (listener.isClosed) throw IOException("Transfer stopped")
        val resolver = context.contentResolver
        val id = DocumentsContract.getTreeDocumentId(directory)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, id)
        val names = resolver.query(
            children, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE),
            null, null, null
        )
            ?: throw IOException("Unable to read destination")
        names.use {
            while (it.moveToNext()) {
                if (name.equals(it.getString(0), ignoreCase = true)) {
                    if (it.getString(2) == Document.MIME_TYPE_DIR) return false
                    val existing = DocumentsContract.buildDocumentUriUsingTree(directory, it.getString(1))
                    return hasSameContents(temp, existing)
                }
            }
        }

        if (listener.isClosed) throw IOException("Transfer stopped")
        val parent = DocumentsContract.buildDocumentUriUsingTree(directory, id)
        val mime = if (name.endsWith(".epub", ignoreCase = true)) "application/epub+zip" else "text/plain"
        val file = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("Unable to create file")
        try {
            val output = resolver.openOutputStream(file, "w") ?: throw IOException("Unable to open file")
            output.use { temp.inputStream().use { input -> input.copyTo(output, 64 * 1024) } }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, file) }
            throw error
        }
        return true
    }

    private fun hasSameContents(temp: File, existing: Uri): Boolean {
        val saved = context.contentResolver.openInputStream(existing) ?: throw IOException("Unable to read file")
        return DataInputStream(saved.buffered()).use { stored ->
            temp.inputStream().use { uploaded ->
                val expected = ByteArray(64 * 1024)
                val actual = ByteArray(expected.size)
                try {
                    while (true) {
                        val count = uploaded.read(expected)
                        if (count < 0) break
                        stored.readFully(actual, 0, count)
                        for (index in 0 until count) if (actual[index] != expected[index]) return false
                    }
                    stored.read() == -1
                } catch (_: EOFException) {
                    false
                }
            }
        }
    }

    private fun importBook(temp: File, name: String): String = try {
        var grouped = false
        val result = BookStore.import(context, Uri.fromFile(temp), name, MAX_EXPANDED_BYTES) { book ->
            grouped = GroupStore.groupImportedBooks(context, directoryName, listOf(book.id)) != null
        }
        if (result != ImportResult.Failed && grouped) {
            "added"
        } else {
            "import_failed"
        }
    } catch (_: Exception) {
        "import_failed"
    }

    private fun BufferedInputStream.readHttpLine(): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < 8192) {
            val next = read()
            if (next < 0) return null
            if (next == '\n'.code) return line.toString("US-ASCII").removeSuffix("\r")
            line.write(next)
        }
        throw IOException("Header line too long")
    }

    private fun Socket.json(data: JSONObject) =
        reply(200, "OK", data.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    private fun Socket.reply(code: Int, reason: String, body: ByteArray = byteArrayOf(), type: String = "text/plain") {
        val output = getOutputStream()
        output.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 128L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
        fun open(
            context: Context,
            directory: Uri,
            directoryName: String,
            onResult: (Receipt) -> Unit,
            onFinish: () -> Unit
        ): WifiBookTransfer? {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = manager.activeNetwork
            val ip = manager.allNetworks.sortedBy { it != activeNetwork }.asSequence()
                .filter { network ->
                    val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
                    // A VPN can report the transport of its underlying Wi-Fi or Ethernet network.
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                }
                .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress } ?: return null
            val socket = ServerSocket()
            try {
                try { socket.bind(InetSocketAddress(ip, 8787)) }
                catch (_: java.net.BindException) { socket.bind(InetSocketAddress(ip, 0)) }
                return WifiBookTransfer(context.applicationContext, socket, directory, directoryName, onResult, onFinish)
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }
    }
}
