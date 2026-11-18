package com.sard.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Keeps running in the background: draws the Arabic subtitle over everything on the TV
 * (other apps and the HDMI input), and receives the lines from the phone over Wi-Fi.
 */
class OverlayService : Service()
{
	companion object
	{
		private const val TAG = "SardOverlay"
		const val PORT = 47800
		const val ACTION_TEST = "com.sard.tv.TEST"
		const val ACTION_RESTYLE = "com.sard.tv.RESTYLE"
		const val EXTRA_SECONDS = "seconds"
		private const val CHANNEL = "sard"

		/** The running service, for the activity's status line */
		@Volatile var instance: OverlayService? = null
			private set

		fun start(context: Context, action: String? = null, seconds: Int = 0)
		{
			val intent = Intent(context, OverlayService::class.java).setAction(action).putExtra(EXTRA_SECONDS, seconds)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				context.startForegroundService(intent)
			else
				context.startService(intent)
		}
	}

	private val main = Handler(Looper.getMainLooper())
	private lateinit var prefs: Prefs
	private var windowManager: WindowManager? = null
	private var root: FrameLayout? = null
	private var textView: TextView? = null
	var server: SubtitleServer? = null
		private set
	/** Why the service couldn't run in the foreground, if it couldn't (shown on the TV screen) */
	@Volatile var foregroundError: String? = null
		private set
	private var nsd: NsdManager? = null
	private var nsdListener: NsdManager.RegistrationListener? = null

	/** When the phone was last heard from, for the activity's status line */
	@Volatile var lastClientMs = 0L
		private set

	private val hideRunnable = Runnable { showText(null) }

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate()
	{
		super.onCreate()
		instance = this
		prefs = Prefs(this)
		// The server must start even where the system refuses a foreground service
		try { goForeground() }
		catch(e: Exception) { foregroundError = "${e.javaClass.simpleName}: ${e.message}"; Log.e(TAG, "startForeground failed", e) }
		server = SubtitleServer(PORT, { prefs.pairingCode }, this::onCommand).also { it.start() }
		advertise()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		ensureOverlay()
		when(intent?.action)
		{
			ACTION_TEST -> showText(
				"كريتوس: يا ولد، انتبه لخطواتك. هذه ترجمة تجريبية من «سرد».",
				(intent.getIntExtra(EXTRA_SECONDS, 8)).coerceAtLeast(1) * 1000L)
			ACTION_RESTYLE -> applyStyle()
		}
		return START_STICKY
	}

	override fun onDestroy()
	{
		instance = null
		main.removeCallbacksAndMessages(null)
		server?.stop()
		nsdListener?.let { runCatching { nsd?.unregisterService(it) } }
		root?.let { runCatching { windowManager?.removeView(it) } }
		super.onDestroy()
	}

	// ---------- commands from the phone (called on server threads) ----------

	private fun onCommand(command: SubtitleServer.Command)
	{
		lastClientMs = System.currentTimeMillis()
		main.post {
			ensureOverlay()
			when(command)
			{
				is SubtitleServer.Command.Show -> showText(command.text, command.hideAfterMs)
				is SubtitleServer.Command.Clear -> showText(null)
				is SubtitleServer.Command.Style ->
				{
					val s = command.settings
					if(s.has("textSize")) prefs.textSizeSp = s.optInt("textSize")
					if(s.has("bottomMargin")) prefs.bottomMarginDp = s.optInt("bottomMargin")
					if(s.has("opacity")) prefs.backgroundOpacity = s.optInt("opacity")
					if(s.has("color")) runCatching { prefs.textColor = Color.parseColor(s.optString("color")) }
					applyStyle()
				}
			}
		}
	}

	// ---------- the overlay ----------

	fun canDrawOverlay() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

	private fun ensureOverlay()
	{
		if(root != null || !canDrawOverlay())
			return
		val wm = getSystemService(WINDOW_SERVICE) as WindowManager
		val tv = TextView(this).apply {
			typeface = runCatching { Typeface.createFromAsset(assets, "tajawal_bold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
			gravity = Gravity.CENTER
			textDirection = View.TEXT_DIRECTION_RTL
			layoutDirection = View.LAYOUT_DIRECTION_RTL
			setLineSpacing(0f, 1.15f)
			setShadowLayer(8f, 0f, 0f, Color.argb(230, 0, 0, 0))
			visibility = View.GONE
		}
		val frame = FrameLayout(this)
		frame.addView(tv, FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
			Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
		@Suppress("DEPRECATION")
		val type = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		else
			WindowManager.LayoutParams.TYPE_PHONE
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT).apply {
			gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
		}
		try
		{
			wm.addView(frame, params)
			windowManager = wm
			root = frame
			textView = tv
			applyStyle()
		}
		catch(e: Exception)
		{
			Log.e(TAG, "Couldn't add the overlay window", e)
		}
	}

	private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

	private fun applyStyle()
	{
		val tv = textView ?: return
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
		tv.setTextColor(prefs.textColor)
		tv.maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
		tv.setPadding(dp(22), dp(8), dp(22), dp(11))
		tv.background = GradientDrawable().apply {
			cornerRadius = dp(14).toFloat()
			setColor(Color.argb(prefs.backgroundOpacity * 255 / 100, 0, 0, 0))
		}
		(tv.layoutParams as? FrameLayout.LayoutParams)?.let {
			it.bottomMargin = dp(prefs.bottomMarginDp)
			tv.layoutParams = it
		}
	}

	/** Shows [text] (or hides the box for null), hiding it again after [hideAfterMs] if > 0 */
	fun showText(text: String?, hideAfterMs: Long = 0)
	{
		val tv = textView ?: return
		main.removeCallbacks(hideRunnable)
		if(text.isNullOrBlank())
		{
			tv.visibility = View.GONE
			return
		}
		tv.text = text
		tv.visibility = View.VISIBLE
		if(hideAfterMs > 0)
			main.postDelayed(hideRunnable, hideAfterMs)
	}

	// ---------- background service plumbing ----------

	private fun goForeground()
	{
		val nm = getSystemService(NotificationManager::class.java)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			nm.createNotificationChannel(NotificationChannel(CHANNEL, "سرد", NotificationManager.IMPORTANCE_MIN))
		@Suppress("DEPRECATION")
		val builder = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
		val notification = builder
			.setSmallIcon(android.R.drawable.ic_menu_info_details)
			.setContentTitle("سرد TV")
			.setContentText("جاهز لعرض الترجمة")
			.setOngoing(true)
			.build()
		if(Build.VERSION.SDK_INT >= 34)
			startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		else
			startForeground(1, notification)
	}

	/** Lets the phone app find the TV on the network (Bonjour, "_sard._tcp") */
	private fun advertise()
	{
		val info = NsdServiceInfo().apply {
			serviceName = "Sard TV"
			serviceType = "_sard._tcp"
			port = PORT
		}
		val listener = object : NsdManager.RegistrationListener
		{
			override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Advertised as ${info.serviceName}") }
			override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "Advertising failed: $error") }
			override fun onServiceUnregistered(info: NsdServiceInfo) {}
			override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
		}
		runCatching {
			nsd = getSystemService(NSD_SERVICE) as NsdManager
			nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
			nsdListener = listener
		}
	}
}
