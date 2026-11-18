// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitles

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Arabic subtitles for any game with English subtitles, free and on the device:
 * copy the bottom of the stream picture → read the English text (ML Kit, on device)
 * → translate it (ML Kit on device right away, then Google's free web translation replaces it
 * when online translation is on) → [onText] shows the Arabic over the stream.
 *
 * Nothing but the English subtitle line leaves the device, and only with online translation on.
 */
class ArabicSubtitles(
	/** The view the video is drawn in right now, or null */
	private val streamView: () -> SurfaceView?,
	/** Called on the main thread: the Arabic line to show, or null to hide it */
	private val onText: (String?) -> Unit,
	/** Called on the main thread with short status messages for the player */
	private val onStatus: (Status) -> Unit
)
{
	enum class Status { MODEL_DOWNLOADING, MODEL_READY, MODEL_FAILED, CAPTURE_UNSUPPORTED }

	companion object
	{
		private const val TAG = "ArabicSubtitles"
		private const val FRAME_WIDTH = 960
		private const val FRAME_HEIGHT = 540
		private const val INTERVAL_MS = 250L
		private const val SIG_W = 64
		private const val SIG_H = 12
		private const val ONLINE_TIMEOUT_MS = 3000
	}

	/** Use Google's free web translation on top of the on-device one (better Arabic, needs internet) */
	@Volatile var online = true
	/** Where the subtitle area starts, as a fraction of the picture's height from the top */
	@Volatile var regionTop = 0.68f

	private val worker = HandlerThread("ArabicSubtitles").apply { start() }
	private val handler = Handler(worker.looper)
	private val workerExecutor = Executor { handler.post(it) }
	private val mainHandler = Handler(Looper.getMainLooper())
	private val onlineExecutor = Executors.newSingleThreadExecutor()

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(TranslatorOptions.Builder()
		.setSourceLanguage(TranslateLanguage.ENGLISH)
		.setTargetLanguage(TranslateLanguage.ARABIC)
		.build())

	// Only touched on the worker thread
	private val frame = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
	private var running = false
	private var busy = false
	private var modelState = 0 // 0 = not asked, 1 = downloading, 2 = ready, 3 = failed
	private var prevSig: IntArray? = null
	private var readSig: IntArray? = null
	private var stableFrames = 0
	private var lastEnglish = ""
	private var captureFailures = 0

	/** The English line on screen right now; results for older lines are dropped */
	@Volatile private var currentEnglish = ""

	/** Translations kept for repeated lines; the online one is kept over the on-device one */
	private class Entry(val arabic: String, val online: Boolean)
	private val cache = object : LinkedHashMap<String, Entry>(256, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 800
	}

	fun start() = handler.post {
		if(running)
			return@post
		running = true
		captureFailures = 0
		ensureModel()
		handler.post(tick)
	}

	fun stop() = handler.post {
		running = false
		handler.removeCallbacks(tick)
		lastEnglish = ""
		currentEnglish = ""
		prevSig = null
		readSig = null
		mainHandler.post { onText(null) }
	}

	fun release()
	{
		stop()
		handler.post {
			recognizer.close()
			translator.close()
			onlineExecutor.shutdownNow()
			worker.quitSafely()
		}
	}

	private val tick = object : Runnable
	{
		override fun run()
		{
			if(!running)
				return
			if(!busy)
				capture()
			handler.postDelayed(this, INTERVAL_MS)
		}
	}

	private fun ensureModel()
	{
		if(modelState == 1 || modelState == 2)
			return
		modelState = 1
		mainHandler.post { onStatus(Status.MODEL_DOWNLOADING) }
		// About 30 MB, once; after that translation works without internet
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener(workerExecutor) {
				modelState = 2
				mainHandler.post { onStatus(Status.MODEL_READY) }
			}
			.addOnFailureListener(workerExecutor) { e ->
				Log.w(TAG, "Translation model download failed", e)
				modelState = 3
				mainHandler.post { onStatus(Status.MODEL_FAILED) }
			}
	}

	// ---------- capture ----------

	private fun capture()
	{
		val view = streamView() ?: return
		if(!view.holder.surface.isValid)
			return
		busy = true
		try
		{
			// Scales the whole picture into the 960×540 bitmap, whatever the stream's resolution
			PixelCopy.request(view, frame, { result ->
				if(result == PixelCopy.SUCCESS)
				{
					captureFailures = 0
					onFrame()
				}
				else
				{
					busy = false
					if(++captureFailures == 20)
						mainHandler.post { onStatus(Status.CAPTURE_UNSUPPORTED) }
				}
			}, handler)
		}
		catch(e: IllegalArgumentException)
		{
			// The surface went away (stream ending, rotating)
			busy = false
		}
	}

	private fun onFrame()
	{
		val top = (FRAME_HEIGHT * regionTop.coerceIn(0.3f, 0.9f)).toInt()
		val left = (FRAME_WIDTH * 0.05f).toInt()
		val band = Bitmap.createBitmap(frame, left, top, FRAME_WIDTH - 2 * left, FRAME_HEIGHT - top - 4)
		val sig = signature(band)

		val prev = prevSig
		stableFrames = if(prev != null && difference(sig, prev) < 0.01f) stableFrames + 1 else 0
		prevSig = sig

		// Read once the area has held still for a frame (no half-faded or typing-in lines),
		// and only if it changed since the last read.
		val read = readSig
		if(stableFrames >= 1 && (read == null || difference(sig, read) > 0.02f))
		{
			readSig = sig
			recognize(band)
		}
		else
			busy = false
	}

	/** A tiny greyscale picture of the area, to notice when the subtitle changes */
	private fun signature(band: Bitmap): IntArray
	{
		val small = Bitmap.createScaledBitmap(band, SIG_W, SIG_H, true)
		val pixels = IntArray(SIG_W * SIG_H)
		small.getPixels(pixels, 0, SIG_W, 0, 0, SIG_W, SIG_H)
		small.recycle()
		for(i in pixels.indices)
		{
			val p = pixels[i]
			pixels[i] = ((p shr 16 and 0xff) * 299 + (p shr 8 and 0xff) * 587 + (p and 0xff) * 114) / 1000
		}
		return pixels
	}

	private fun difference(a: IntArray, b: IntArray): Float
	{
		var changed = 0
		for(i in a.indices)
			if(abs(a[i] - b[i]) > 40)
				changed++
		return changed.toFloat() / a.size
	}

	// ---------- reading ----------

	private fun recognize(band: Bitmap)
	{
		recognizer.process(InputImage.fromBitmap(band, 0))
			.addOnSuccessListener(workerExecutor) { onEnglish(joinLines(it)) }
			.addOnFailureListener(workerExecutor) { Log.w(TAG, "Text recognition failed", it) }
			.addOnCompleteListener(workerExecutor) { busy = false }
	}

	private fun joinLines(text: Text): String
	{
		val lines = text.textBlocks.flatMap { it.lines }
			.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
		return cleanEnglish(lines.joinToString(" ") { it.text })
	}

	private fun onEnglish(english: String)
	{
		if(!running)
			return
		if(english.isEmpty())
		{
			if(lastEnglish.isNotEmpty())
			{
				lastEnglish = ""
				currentEnglish = ""
				mainHandler.post { onText(null) }
			}
			return
		}
		if(similarity(english, lastEnglish) >= 0.9f)
			return
		lastEnglish = english
		currentEnglish = english
		translate(english)
	}

	// ---------- translating ----------

	private fun translate(english: String)
	{
		val cached = cache[english]
		if(cached != null)
		{
			show(english, cached.arabic)
			if(cached.online || !online)
				return
		}
		else if(modelState == 2)
		{
			translator.translate(english)
				.addOnSuccessListener(workerExecutor) { arabic ->
					// A faster online result may already be in
					if(cache[english]?.online != true)
					{
						cache[english] = Entry(arabic, false)
						show(english, arabic)
					}
				}
				.addOnFailureListener(workerExecutor) { Log.w(TAG, "On-device translation failed", it) }
		}
		else if(modelState == 3 && !online)
			ensureModel() // try the download again

		if(online)
		{
			onlineExecutor.execute {
				val arabic = try { translateOnline(english) } catch(e: Exception) { Log.w(TAG, "Online translation failed", e); null }
				if(arabic != null)
					handler.post {
						cache[english] = Entry(arabic, true)
						show(english, arabic)
					}
			}
		}
	}

	private fun show(english: String, arabic: String)
	{
		if(english != currentEnglish || arabic.isBlank())
			return
		mainHandler.post { if(english == currentEnglish) onText(arabic) }
	}

	/** Google's free web translation, the one its translate widgets use; no account or key */
	private fun translateOnline(english: String): String?
	{
		val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&q=" + Uri.encode(english))
		val connection = url.openConnection() as HttpURLConnection
		connection.connectTimeout = ONLINE_TIMEOUT_MS
		connection.readTimeout = ONLINE_TIMEOUT_MS
		try
		{
			if(connection.responseCode != 200)
				return null
			val body = connection.inputStream.bufferedReader().use { it.readText() }
			val sentences = JSONArray(body).getJSONArray(0)
			val out = StringBuilder()
			for(i in 0 until sentences.length())
				out.append(sentences.getJSONArray(i).optString(0, ""))
			return out.toString().trim().ifEmpty { null }
		}
		finally
		{
			connection.disconnect()
		}
	}
}

/** Tidies text read from the screen, or returns "" for reads that aren't a subtitle */
internal fun cleanEnglish(raw: String): String
{
	val text = raw.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?;:])"), "$1").trim()
	val letters = text.count { it in 'a'..'z' || it in 'A'..'Z' }
	// Single letters, button prompts and stray HUD numbers aren't subtitles
	return if(letters < 3) "" else text
}

/** How alike two lines are, 0..1, ignoring case (1 - normalized edit distance) */
internal fun similarity(a: String, b: String): Float
{
	if(a.isEmpty() && b.isEmpty())
		return 1f
	if(a.isEmpty() || b.isEmpty())
		return 0f
	val x = a.lowercase()
	val y = b.lowercase()
	var prev = IntArray(y.length + 1) { it }
	var cur = IntArray(y.length + 1)
	for(i in 1..x.length)
	{
		cur[0] = i
		for(j in 1..y.length)
			cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if(x[i - 1] == y[j - 1]) 0 else 1)
		val t = prev; prev = cur; cur = t
	}
	return 1f - prev[y.length].toFloat() / max(x.length, y.length)
}
