package com.sard.tv

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * A tiny web server on the local network. The phone app sends it the Arabic lines;
 * opening http://<TV address>:47800 in a phone's browser gives a test page.
 *
 *   POST /api/subtitle {"code":"1234","text":"…","hideAfterMs":0}
 *   POST /api/clear    {"code":"1234"}
 *   POST /api/style    {"code":"1234","textSize":30,"bottomMargin":48,"opacity":80,"color":"#FFFFFF"}
 *   GET  /api/pair?code=1234   → {"ok":true} when the code is right
 */
class SubtitleServer(
	private val port: Int,
	/** The TV's current pairing code */
	private val pairingCode: () -> String,
	private val onCommand: (Command) -> Unit
)
{
	sealed class Command
	{
		class Show(val text: String, val hideAfterMs: Long) : Command()
		object Clear : Command()
		/** New look; only the keys that were sent are in [settings] */
		class Style(val settings: JSONObject) : Command()
	}

	companion object
	{
		private const val TAG = "SardServer"
		private const val MAX_BODY = 64 * 1024
	}

	private var socket: ServerSocket? = null
	private val pool = Executors.newCachedThreadPool()
	@Volatile private var running = false

	fun start()
	{
		running = true
		Thread({
			try
			{
				val server = ServerSocket()
				server.reuseAddress = true
				server.bind(InetSocketAddress(port))
				socket = server
				while(running)
				{
					val client = server.accept()
					pool.execute { handle(client) }
				}
			}
			catch(e: Exception)
			{
				if(running)
					Log.e(TAG, "Server stopped", e)
			}
		}, "SardServer").start()
	}

	fun stop()
	{
		running = false
		runCatching { socket?.close() }
		pool.shutdownNow()
	}

	private class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

	private fun handle(client: Socket)
	{
		client.use { s ->
			s.soTimeout = 5000
			try
			{
				val request = read(BufferedInputStream(s.getInputStream())) ?: return
				val (status, type, body) = route(request)
				val bytes = body.toByteArray(Charsets.UTF_8)
				val head = "HTTP/1.1 $status\r\n" +
					"Content-Type: $type\r\n" +
					"Content-Length: ${bytes.size}\r\n" +
					"Access-Control-Allow-Origin: *\r\n" +
					"Access-Control-Allow-Headers: Content-Type\r\n" +
					"Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
					"Cache-Control: no-store\r\n" +
					"Connection: close\r\n\r\n"
				val out = s.getOutputStream()
				out.write(head.toByteArray(Charsets.US_ASCII))
				out.write(bytes)
				out.flush()
			}
			catch(e: Exception)
			{
				Log.w(TAG, "Request failed", e)
			}
		}
	}

	private fun readLine(input: InputStream): String?
	{
		val buf = ByteArrayOutputStream()
		while(true)
		{
			val b = input.read()
			if(b < 0)
				return if(buf.size() == 0) null else buf.toString("UTF-8")
			if(b == '\n'.code)
				return buf.toString("UTF-8").trimEnd('\r')
			if(buf.size() > 8192)
				return null
			buf.write(b)
		}
	}

	private fun read(input: InputStream): Request?
	{
		val first = readLine(input) ?: return null
		val parts = first.split(" ")
		if(parts.size < 2)
			return null
		var length = 0
		while(true)
		{
			val line = readLine(input) ?: break
			if(line.isEmpty())
				break
			val i = line.indexOf(':')
			if(i > 0 && line.substring(0, i).trim().equals("Content-Length", ignoreCase = true))
				length = line.substring(i + 1).trim().toIntOrNull() ?: 0
		}
		length = length.coerceIn(0, MAX_BODY)
		val body = ByteArray(length)
		var read = 0
		while(read < length)
		{
			val n = input.read(body, read, length - read)
			if(n < 0)
				break
			read += n
		}
		val target = parts[1]
		val q = target.indexOf('?')
		val path = if(q >= 0) target.substring(0, q) else target
		val query = if(q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
		return Request(parts[0].uppercase(), path, query, String(body, 0, read, Charsets.UTF_8))
	}

	private fun parseQuery(q: String) = q.split("&").mapNotNull {
		val i = it.indexOf('=')
		if(i <= 0) null else java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
	}.toMap()

	private fun json(ok: Boolean, extra: String = "") =
		Triple(if(ok) "200 OK" else "403 Forbidden", "application/json; charset=utf-8",
			"{\"ok\":$ok${if(extra.isNotEmpty()) ",$extra" else ""}}")

	private fun route(r: Request): Triple<String, String, String>
	{
		if(r.method == "OPTIONS")
			return Triple("204 No Content", "text/plain", "")
		if(r.method == "GET" && (r.path == "/" || r.path == "/index.html"))
			return Triple("200 OK", "text/html; charset=utf-8", WebPage.HTML)
		if(r.method == "GET" && r.path == "/api/info")
			return Triple("200 OK", "application/json", "{\"app\":\"sard-tv\",\"version\":1}")
		if(r.method == "GET" && r.path == "/api/pair")
			return json(r.query["code"] == pairingCode())

		if(r.method != "POST")
			return Triple("404 Not Found", "text/plain", "not found")
		val body = runCatching { JSONObject(r.body.ifBlank { "{}" }) }.getOrNull()
			?: return Triple("400 Bad Request", "text/plain", "bad json")
		if(body.optString("code") != pairingCode())
			return json(false, "\"error\":\"wrong code\"")

		when(r.path)
		{
			"/api/subtitle" ->
			{
				val text = body.optString("text").take(500)
				onCommand(if(text.isBlank()) Command.Clear else Command.Show(text, body.optLong("hideAfterMs", 0)))
			}
			"/api/clear" -> onCommand(Command.Clear)
			"/api/style" -> onCommand(Command.Style(body))
			else -> return Triple("404 Not Found", "text/plain", "not found")
		}
		return json(true)
	}
}
