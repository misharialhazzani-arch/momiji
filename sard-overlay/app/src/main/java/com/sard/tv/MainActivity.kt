package com.sard.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** The screen on the TV: pairing code, address, overlay permission, and a test button */
class MainActivity : Activity()
{
	private lateinit var prefs: Prefs
	private lateinit var codeView: TextView
	private lateinit var addressView: TextView
	private lateinit var permissionView: TextView
	private lateinit var permissionButton: Button
	private lateinit var clientView: TextView
	private lateinit var diagnosticView: TextView
	private val main = Handler(Looper.getMainLooper())
	private var font: Typeface = Typeface.DEFAULT_BOLD

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		prefs = Prefs(this)
		font = runCatching { Typeface.createFromAsset(assets, "tajawal_bold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
		setContentView(buildUi())
		OverlayService.start(this)
	}

	override fun onResume()
	{
		super.onResume()
		refresh()
		main.post(tick)
	}

	override fun onPause()
	{
		super.onPause()
		main.removeCallbacks(tick)
	}

	private val tick = object : Runnable
	{
		override fun run()
		{
			refreshClient()
			main.postDelayed(this, 2000)
		}
	}

	// ---------- UI ----------

	private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

	private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
		textSize = size
		setTextColor(color)
		typeface = if(bold) font else Typeface.DEFAULT
		gravity = Gravity.CENTER
		textDirection = View.TEXT_DIRECTION_RTL
	}

	private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
		text = label
		textSize = 18f
		typeface = font
		isAllCaps = false
		setTextColor(Color.WHITE)
		isFocusable = true
		background = GradientDrawable().apply {
			cornerRadius = dp(12).toFloat()
			setColor(Color.parseColor("#2F6FED"))
		}
		setPadding(dp(24), dp(12), dp(24), dp(12))
		setOnFocusChangeListener { v, has -> v.alpha = if(has) 1f else 0.75f; v.scaleX = if(has) 1.05f else 1f; v.scaleY = v.scaleX }
		alpha = 0.75f
		setOnClickListener { onClick() }
	}

	private fun buildUi(): View
	{
		val column = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			layoutDirection = View.LAYOUT_DIRECTION_RTL
			setPadding(dp(48), dp(32), dp(48), dp(32))
		}
		fun gap(h: Int) = column.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
		fun add(v: View) = column.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

		add(text(34f, bold = true).apply { text = "سرد TV" })
		add(text(16f, Color.parseColor("#9AA0AA")).apply { text = "يعرض الترجمة العربية فوق اللعبة، وتصله من الجوال عبر الواي فاي" })
		gap(24)
		add(text(18f, Color.parseColor("#C7CBD3")).apply { text = "رمز الربط" })
		codeView = text(64f, bold = true).apply { letterSpacing = 0.3f }
		add(codeView)
		gap(8)
		addressView = text(18f, Color.parseColor("#C7CBD3"))
		add(addressView)
		gap(4)
		clientView = text(16f, Color.parseColor("#9AA0AA"))
		add(clientView)
		diagnosticView = text(14f, Color.parseColor("#9AA0AA"))
		add(diagnosticView)
		gap(20)
		permissionView = text(17f)
		add(permissionView)
		gap(10)
		permissionButton = button("السماح بالعرض فوق التطبيقات") { askPermission() }
		add(permissionButton)
		gap(12)
		add(button("تجربة: اعرض ترجمة لمدة دقيقة") {
			OverlayService.start(this, OverlayService.ACTION_TEST, 60)
			permissionView.text = "انتقل الآن إلى مدخل HDMI الخاص بالبلايستيشن. يجب أن تبقى الترجمة ظاهرة فوقه."
		})
		gap(12)
		add(button("رمز ربط جديد") { prefs.newPairingCode(); refresh() }.apply {
			background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#2C3039")) }
		})

		return ScrollView(this).apply {
			setBackgroundColor(Color.parseColor("#0E0F13"))
			isFillViewport = true
			addView(column)
		}
	}

	private fun refresh()
	{
		codeView.text = prefs.pairingCode
		val ip = localAddress()
		addressView.text = if(ip != null)
			"للتجربة من الجوال افتح:  http://$ip:${OverlayService.PORT}"
		else
			"التلفزيون غير متصل بالشبكة"
		val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
		permissionView.setTextColor(if(allowed) Color.parseColor("#3DDC84") else Color.parseColor("#FFB020"))
		permissionView.text = if(allowed)
			"✓ مسموح بالعرض فوق التطبيقات"
		else
			"⚠ يلزم السماح بالعرض فوق التطبيقات حتى تظهر الترجمة"
		permissionButton.visibility = if(allowed) View.GONE else View.VISIBLE
		if(allowed)
			OverlayService.start(this) // adds the overlay now that it may
		refreshClient()
		main.postDelayed({ runDiagnostics() }, 1200)
	}

	/** Checks that the server answers, from inside the TV and on its network address */
	private fun runDiagnostics()
	{
		val service = OverlayService.instance
		val serverStatus = service?.server?.status ?: "service not running"
		val fgError = service?.foregroundError
		val ip = localAddress()
		Thread {
			fun probe(host: String?): String
			{
				if(host == null) return "no address"
				return try
				{
					val c = java.net.URL("http://$host:${OverlayService.PORT}/api/info").openConnection() as java.net.HttpURLConnection
					c.connectTimeout = 1500
					c.readTimeout = 1500
					val code = c.responseCode
					c.disconnect()
					if(code == 200) "OK" else "HTTP $code"
				}
				catch(e: Exception) { e.javaClass.simpleName }
			}
			val local = probe("127.0.0.1")
			val lan = probe(ip)
			val good = local == "OK" && lan == "OK"
			main.post {
				diagnosticView.setTextColor(if(good) Color.parseColor("#3DDC84") else Color.parseColor("#FF6B6B"))
				diagnosticView.text = (if(good) "✓ الخادم يعمل ويمكن الوصول له من الشبكة" else "✗ مشكلة في الخادم") +
					"\nserver=$serverStatus · local=$local · lan=$lan" + (fgError?.let { "\nforeground=$it" } ?: "")
			}
		}.start()
	}

	private fun refreshClient()
	{
		val last = OverlayService.instance?.lastClientMs ?: 0L
		clientView.text = when
		{
			last == 0L -> "لم يتصل جوال بعد"
			System.currentTimeMillis() - last < 15_000 -> "✓ الجوال متصل الآن"
			else -> "آخر اتصال من الجوال قبل ${(System.currentTimeMillis() - last) / 60_000} دقيقة"
		}
	}

	private fun askPermission()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
			return
		val tries = listOf(
			Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
			Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
		for(intent in tries)
		{
			try
			{
				startActivity(intent)
				return
			}
			catch(e: ActivityNotFoundException) {}
		}
		// Many TVs hide this setting; it can still be granted from a computer
		permissionView.setTextColor(Color.parseColor("#FFB020"))
		permissionView.text = "هذا التلفزيون يخفي هذا الإعداد. فعّله من الكمبيوتر بالأمر:\n" +
			"adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
	}

	companion object
	{
		/** The TV's IPv4 address on the local network */
		fun localAddress(): String? = runCatching {
			NetworkInterface.getNetworkInterfaces().toList()
				.filter { it.isUp && !it.isLoopback }
				.flatMap { it.inetAddresses.toList() }
				.filterIsInstance<Inet4Address>()
				.firstOrNull { it.isSiteLocalAddress }?.hostAddress
		}.getOrNull()
	}
}

class BootReceiver : android.content.BroadcastReceiver()
{
	override fun onReceive(context: Context, intent: Intent)
	{
		if(intent.action == Intent.ACTION_BOOT_COMPLETED)
			runCatching { OverlayService.start(context) }
	}
}
