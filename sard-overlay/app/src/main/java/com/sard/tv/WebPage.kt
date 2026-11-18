package com.sard.tv

/** The test page served at http://<TV address>:47800 — send subtitles to the TV from any phone browser */
object WebPage
{
	// No "$" in the page: this is a Kotlin string
	const val HTML = """<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>سرد TV — تجربة</title>
<style>
  :root { color-scheme: dark; }
  body { font-family: -apple-system, system-ui, sans-serif; background: #0e0f13; color: #f2f2f2; margin: 0; padding: 20px; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  p.sub { color: #9aa0aa; margin: 0 0 18px; font-size: 14px; }
  .card { background: #181a20; border-radius: 14px; padding: 16px; margin-bottom: 14px; }
  label { display: block; font-size: 14px; color: #c7cbd3; margin: 10px 0 6px; }
  input, textarea { width: 100%; box-sizing: border-box; font-size: 17px; padding: 12px; border-radius: 10px; border: 1px solid #2c3039; background: #0e0f13; color: #fff; }
  textarea { min-height: 84px; resize: vertical; }
  input[type=range] { padding: 0; }
  .row { display: flex; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
  button { flex: 1; min-width: 120px; font-size: 16px; padding: 13px; border: 0; border-radius: 10px; background: #2f6fed; color: #fff; font-weight: 600; }
  button.alt { background: #2c3039; }
  .chip { flex: none; min-width: 0; font-size: 14px; padding: 9px 12px; background: #23262e; font-weight: 500; }
  #status { margin-top: 10px; font-size: 14px; min-height: 20px; }
  .ok { color: #3ddc84; } .err { color: #ff6b6b; }
</style>
</head>
<body>
  <h1>سرد TV</h1>
  <p class="sub">جرّب الترجمة على التلفزيون. افتح هذه الصفحة من جوالك وأنت على نفس شبكة الواي فاي.</p>

  <div class="card">
    <label for="code">رمز الربط (الظاهر على التلفزيون)</label>
    <input id="code" inputmode="numeric" maxlength="4" placeholder="1234">
    <div id="status"></div>
  </div>

  <div class="card">
    <label for="text">النص</label>
    <textarea id="text">كريتوس: يا ولد، انتبه لخطواتك. الثلج هنا لا يرحم الضعفاء.</textarea>
    <div class="row">
      <button onclick="send()">اعرض على التلفزيون</button>
      <button class="alt" onclick="clearText()">إخفاء</button>
    </div>
    <div class="row">
      <button class="chip" onclick="sample(0)">مثال ١</button>
      <button class="chip" onclick="sample(1)">مثال ٢</button>
      <button class="chip" onclick="sample(2)">سطران</button>
      <button class="chip" onclick="demo()">تجربة متتالية</button>
    </div>
  </div>

  <div class="card">
    <label for="size">حجم الخط: <span id="sizeV"></span></label>
    <input type="range" id="size" min="16" max="60" value="30" oninput="sv()" onchange="style_()">
    <label for="pos">الارتفاع عن أسفل الشاشة: <span id="posV"></span></label>
    <input type="range" id="pos" min="0" max="400" value="48" oninput="sv()" onchange="style_()">
    <label for="op">قتامة الخلفية: <span id="opV"></span></label>
    <input type="range" id="op" min="0" max="100" value="80" oninput="sv()" onchange="style_()">
  </div>

<script>
  var samples = [
    "أتريوس: أبي، هل تسمع ذلك الصوت؟",
    "كريتوس: ابقَ خلفي. مهما حدث، لا تترك يدي.",
    "يجب أن نصل إلى قمة الجبل قبل أن يحلّ الظلام،\nوإلا فلن نجد طريق العودة."
  ];
  function el(id) { return document.getElementById(id); }
  function status(msg, ok) { var s = el("status"); s.textContent = msg; s.className = ok ? "ok" : "err"; }
  function code() { return el("code").value.trim(); }
  try { el("code").value = localStorage.getItem("sardCode") || ""; } catch (e) {}
  el("code").addEventListener("input", function () {
    try { localStorage.setItem("sardCode", code()); } catch (e) {}
    if (code().length === 4) check();
  });
  function post(path, data) {
    data.code = code();
    return fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) })
      .then(function (r) { return r.json(); })
      .then(function (j) { if (j.ok) status("✓ تم", true); else status("رمز الربط غير صحيح", false); return j; })
      .catch(function () { status("تعذّر الاتصال بالتلفزيون", false); });
  }
  function check() {
    fetch("/api/pair?code=" + encodeURIComponent(code())).then(function (r) { return r.json(); })
      .then(function (j) { status(j.ok ? "✓ مربوط بالتلفزيون" : "رمز الربط غير صحيح", j.ok); });
  }
  function send() { post("/api/subtitle", { text: el("text").value }); }
  function clearText() { post("/api/clear", {}); }
  function sample(i) { el("text").value = samples[i]; send(); }
  function demo() {
    var i = 0;
    (function next() {
      if (i >= samples.length) { setTimeout(clearText, 2500); return; }
      el("text").value = samples[i++]; send(); setTimeout(next, 2500);
    })();
  }
  function sv() { el("sizeV").textContent = el("size").value; el("posV").textContent = el("pos").value; el("opV").textContent = el("op").value + "٪"; }
  function style_() { post("/api/style", { textSize: +el("size").value, bottomMargin: +el("pos").value, opacity: +el("op").value }); }
  sv();
  if (code().length === 4) check();
</script>
</body>
</html>"""
}
