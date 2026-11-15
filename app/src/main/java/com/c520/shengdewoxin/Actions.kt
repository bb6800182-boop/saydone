package com.c520.shengdewoxin

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 一個動作的結果。intent 由面板在主執行緒送出；leave=true 會打開別的 App，面板隨即收起。
 * info=true 表示 text 本身就是答案（天氣、行事曆），要念它而不是 AI 的 say。
 * choices 有值時面板列出按鈕讓使用者點（同名聯絡人、前往授權）。
 */
class Res(
    val text: String,
    val speak: Boolean = false,
    val intent: Intent? = null,
    val leave: Boolean = false,
    val error: Boolean = false,
    val info: Boolean = false,
    val choices: List<Pair<String, Intent>> = emptyList(),
    val fallback: Intent? = null,
)

private fun JSONObject.str(k: String): String? = optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }

/** 執行動作。在背景執行緒呼叫（查聯絡人、天氣會用到資料庫或網路）。參數一律先驗證再用。 */
object Actions {
    private const val TAG = "Actions"
    private const val MAPS = "com.google.android.apps.maps"
    private const val YT = "com.google.android.youtube"
    private const val YTM = "com.google.android.apps.youtube.music"
    private const val SPOTIFY = "com.spotify.music"

    fun exec(c: Context, a: Act): Res = try {
        when (a.t) {
            "alarm" -> alarm(a.p)
            "timer" -> timer(a.p)
            "calendar" -> calendar(c, a.p)
            "open_app" -> openApp(c, a.p)
            "navigate" -> navigate(c, a.p)
            "call" -> call(c, a.p)
            "flashlight" -> flashlight(c, a.p)
            "volume" -> volume(c, a.p)
            "ringer" -> ringer(c, a.p)
            "brightness" -> brightness(c, a.p)
            "dnd" -> dnd(c, a.p)
            "media" -> media(c, a.p)
            "play" -> play(c, a.p)
            "settings" -> settings(a.p)
            "search" -> search(a.p)
            "weather" -> weather(c, a.p)
            else -> fail(L.t("還不會做「${a.t}」", "I can't do \"${a.t}\" yet"))
        }
    } catch (e: Exception) {
        Log.w(TAG, "exec ${a.t} failed", e)
        fail(L.t("執行失敗，請再試一次", "That didn't work. Please try again"))
    }

    /** 動作的中文說明（情境儲存時列出「會做什麼」）。 */
    fun describe(a: Act): String {
        val p = a.p
        val q = p.optString("q")
        return when (a.t) {
            "alarm" -> L.t("鬧鐘 ${Quick.hm(p.optInt("h"), p.optInt("m"))}", "Alarm at ${Quick.hm(p.optInt("h"), p.optInt("m"))}")
            "timer" -> L.t("計時 ${Quick.dur(p.optInt("sec"))}", "Timer for ${Quick.dur(p.optInt("sec"))}")
            "calendar" -> L.t("行事曆：${p.optString("title")}", "Calendar: ${p.optString("title")}")
            "open_app" -> L.t("打開 ${p.optJSONArray("names")?.optString(0) ?: p.optString("name")}", "Open ${p.optJSONArray("names")?.optString(0) ?: p.optString("name")}")
            "navigate" -> L.t("導航到 $q", "Navigate to $q")
            "call" -> L.t("打給 ${p.optString("name")}", "Call ${p.optString("name")}")
            "flashlight" -> when {
                !p.has("on") -> L.t("切換手電筒", "Toggle the flashlight")
                p.optBoolean("on") -> L.t("打開手電筒", "Turn on the flashlight")
                else -> L.t("關掉手電筒", "Turn off the flashlight")
            }
            "volume" -> {
                val s = when (p.optString("stream")) {
                    "ring" -> L.t("鈴聲", "Ring")
                    "alarm" -> L.t("鬧鐘", "Alarm")
                    else -> L.t("媒體", "Media")
                }
                when {
                    p.has("mute") -> if (p.optBoolean("mute")) L.t("${s}靜音", "$s muted") else L.t("取消${s}靜音", "$s unmuted")
                    p.has("level") -> L.t("${s}音量 ${p.optInt("level")}%", "$s volume ${p.optInt("level")}%")
                    p.optInt("delta", 1) > 0 -> L.t("${s}音量調大", "$s volume up")
                    else -> L.t("${s}音量調小", "$s volume down")
                }
            }
            "ringer" -> when (p.optString("mode")) {
                "silent" -> L.t("切到靜音模式", "Switch to silent mode")
                "vibrate" -> L.t("切到震動模式", "Switch to vibrate mode")
                else -> L.t("切到響鈴模式", "Switch to ring mode")
            }
            "brightness" -> when {
                p.has("level") -> L.t("亮度 ${p.optInt("level")}%", "Brightness ${p.optInt("level")}%")
                p.optInt("delta", 1) > 0 -> L.t("螢幕調亮", "Screen brighter")
                else -> L.t("螢幕調暗", "Screen dimmer")
            }
            "dnd" -> if (p.optBoolean("on", true)) L.t("開啟勿擾", "Do Not Disturb on") else L.t("關閉勿擾", "Do Not Disturb off")
            "media" -> when (p.optString("cmd")) {
                "pause" -> L.t("暫停播放", "Pause")
                "next" -> L.t("下一首", "Next song")
                "prev" -> L.t("上一首", "Previous song")
                else -> L.t("播放音樂", "Play music")
            }
            "play" -> when {
                p.optBoolean("liked") -> L.t("播放喜歡的歌", "Play liked songs")
                q.isBlank() -> L.t("播放音樂", "Play music")
                else -> L.t("播放「$q」", "Play \"$q\"")
            }
            "settings" -> L.t("打開設定頁", "Open a settings page")
            "search" -> L.t("搜尋「$q」", "Search for \"$q\"")
            "weather" -> L.t("查天氣", "Check the weather")
            else -> a.t
        }
    }

    private fun fail(msg: String, picks: List<Pair<String, Intent>> = emptyList()) =
        Res(msg, speak = true, error = true, choices = picks)

    private fun needPerm(c: Context, name: String) =
        fail(L.t("需要「$name」權限，請到聲得我心設定授權", "This needs the $name permission. Allow it in Say & Done settings"), listOf(L.t("開啟聲得我心設定", "Open Say & Done settings") to Intent(c, MainActivity::class.java)))

    private fun has(c: Context, perm: String) = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun alarm(p: JSONObject): Res {
        val h = p.optInt("h", -1)
        val m = p.optInt("m", 0)
        if (h !in 0..23 || m !in 0..59) return fail(L.t("時間沒聽清楚，請再說一次", "I didn't catch the time. Please say it again"))
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        p.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return Res(L.t("${Quick.hm(h, m)}的鬧鐘設好了", "Alarm set for ${Quick.hm(h, m)}"), speak = true, intent = i)
    }

    private fun timer(p: JSONObject): Res {
        val sec = p.optInt("sec", 0)
        if (sec !in 1..86_400) return fail(L.t("計時長度沒聽清楚，請再說一次", "I didn't catch how long. Please say it again"))
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        p.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return Res(L.t("計時${Quick.dur(sec)}開始", "Timer started: ${Quick.dur(sec)}"), speak = true, intent = i)
    }

    private fun calendar(c: Context, p: JSONObject): Res {
        if (!has(c, Manifest.permission.WRITE_CALENDAR) || !has(c, Manifest.permission.READ_CALENDAR)) return needPerm(c, L.t("行事曆", "calendar"))
        val title = p.str("title") ?: L.t("提醒", "Reminder")
        val start = runCatching { LocalDateTime.parse(p.str("start")!!.replace(' ', 'T')) }.getOrNull()
            ?: return fail(L.t("日期時間沒聽清楚，請再說一次", "I didn't catch the date and time. Please say it again"))
        val min = p.optInt("min", 30).coerceIn(5, 24 * 60)
        val calId = calendarId(c) ?: return fail(L.t("找不到可以寫入的行事曆", "No calendar to write to"))
        val zone = ZoneId.systemDefault()
        val begin = start.atZone(zone).toInstant().toEpochMilli()
        val ev = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, begin + min * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ev) ?: return fail(L.t("寫入行事曆失敗", "Couldn't write to the calendar"))
        c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
            put(CalendarContract.Reminders.MINUTES, 0)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        val md = L.t("${start.monthValue}月${start.dayOfMonth}日${Quick.hm(start.hour, start.minute)}", "${start.monthValue}/${start.dayOfMonth} ${Quick.hm(start.hour, start.minute)}")
        return Res(L.t("已加到行事曆：$md $title", "Added to your calendar: $md $title"), speak = true, info = true)
    }

    /** 主要的 Google 行事曆優先，其次任何可寫入的。 */
    private fun calendarId(c: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val sel = "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var best: Long? = null
        var bestRank = -1
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val rank = (if (cur.getInt(1) == 1) 2 else 0) + (if (cur.getString(2) == "com.google") 1 else 0)
                if (rank > bestRank) {
                    bestRank = rank
                    best = cur.getLong(0)
                }
            }
        }
        return best
    }

    private fun openApp(c: Context, p: JSONObject): Res {
        val names = ArrayList<String>()
        p.optJSONArray("names")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { names.add(it) }
        }
        p.str("name")?.let { names.add(it) }
        if (names.isEmpty()) return fail(L.t("要打開哪個 App？", "Which app?"))
        val hit = Apps.find(c, names)
        if (hit == null || hit.second < 60) return fail(L.t("找不到「${names.first()}」這個 App", "I can't find an app called \"${names.first()}\""))
        return Res(L.t("打開${hit.first.label}", "Opening ${hit.first.label}"), intent = Apps.launchIntent(hit.first), leave = true)
    }

    private fun navigate(c: Context, p: JSONObject): Res {
        val q = p.str("q") ?: return fail(L.t("要去哪裡？", "Where to?"))
        val i = if (installed(c, MAPS)) {
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q))).setPackage(MAPS)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))
        }
        return Res(L.t("導航到$q", "Navigating to $q"), intent = i, leave = true)
    }

    private fun call(c: Context, p: JSONObject): Res {
        val name = p.str("name") ?: return fail(L.t("要打給誰？", "Who should I call?"))
        val digits = name.replace(Regex("[\\s-]"), "")
        if (digits.matches(Regex("\\+?\\d{3,15}"))) return Res(L.t("撥打 $digits", "Calling $digits"), intent = dial(c, digits), leave = true)
        if (!has(c, Manifest.permission.READ_CONTACTS)) return needPerm(c, L.t("聯絡人", "contacts"))
        val hits = Contacts.find(c, name)
        if (hits.isEmpty()) return fail(L.t("找不到聯絡人「$name」", "I can't find a contact called \"$name\""))
        val top = hits[0]
        // 名字完全相同（或同音）而且只有一位才直接撥；其他列出來讓使用者點，避免打錯人
        val sure = top.score >= 95 && (hits.size == 1 || hits[1].score < top.score)
        if (sure) return Res(L.t("打給${top.name}", "Calling ${top.name}"), intent = dial(c, top.number), leave = true)
        val list = hits.take(4).map { "${it.name}　${it.number}" to dial(c, it.number) }
        return Res(L.t("要打給哪一位？", "Which one?"), speak = true, choices = list)
    }

    private fun dial(c: Context, number: String): Intent {
        val direct = has(c, Manifest.permission.CALL_PHONE)
        return Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    }

    private fun flashlight(c: Context, p: JSONObject): Res {
        val want = if (p.has("on")) p.optBoolean("on") else null
        return try {
            if (Torch.set(c, want)) Res(L.t("手電筒開了", "Flashlight on")) else Res(L.t("手電筒關了", "Flashlight off"))
        } catch (e: Exception) {
            Log.w(TAG, "torch", e)
            fail(L.t("手電筒現在不能用，相機可能正在使用", "The flashlight isn't available right now. The camera may be in use"))
        }
    }

    private fun volume(c: Context, p: JSONObject): Res {
        val am = c.getSystemService(AudioManager::class.java)
        val (stream, label) = when (p.optString("stream")) {
            "ring" -> AudioManager.STREAM_RING to L.t("鈴聲", "Ring")
            "alarm" -> AudioManager.STREAM_ALARM to L.t("鬧鐘", "Alarm")
            else -> AudioManager.STREAM_MUSIC to L.t("媒體", "Media")
        }
        val flags = AudioManager.FLAG_SHOW_UI
        val max = am.getStreamMaxVolume(stream)
        return try {
            when {
                p.has("mute") -> {
                    val mute = p.optBoolean("mute")
                    am.adjustStreamVolume(stream, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, flags)
                    Res(if (mute) L.t("${label}靜音", "$label muted") else L.t("取消${label}靜音", "$label unmuted"))
                }
                p.has("level") -> {
                    val lv = p.optInt("level").coerceIn(0, 100)
                    am.setStreamVolume(stream, (max * lv + 50) / 100, flags)
                    Res(L.t("${label}音量 $lv%", "$label volume $lv%"))
                }
                else -> {
                    val d = p.optInt("delta", 1).sign
                    val step = maxOf(1, (max * 15 + 50) / 100)
                    val v = (am.getStreamVolume(stream) + d * step).coerceIn(0, max)
                    am.setStreamVolume(stream, v, flags)
                    Res(L.t("${label}音量 ${v * 100 / max}%", "$label volume ${v * 100 / max}%"))
                }
            }
        } catch (e: SecurityException) {
            fail(L.t("勿擾模式中不能調整鈴聲，請先授權勿擾權限", "The ring volume can't be changed in Do Not Disturb. Allow Do Not Disturb access first"))
        }
    }

    /** 響鈴模式：響鈴、震動、靜音。切到或切出靜音要有勿擾權限（系統規定）。 */
    private fun ringer(c: Context, p: JSONObject): Res {
        val am = c.getSystemService(AudioManager::class.java)
        val (mode, label) = when (p.optString("mode")) {
            "silent" -> AudioManager.RINGER_MODE_SILENT to L.t("靜音模式", "silent mode")
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE to L.t("震動模式", "vibrate mode")
            else -> AudioManager.RINGER_MODE_NORMAL to L.t("響鈴模式", "ring mode")
        }
        return try {
            am.ringerMode = mode
            Res(L.t("切到$label", "Switched to $label"))
        } catch (e: SecurityException) {
            fail(L.t("需要「勿擾權限」才能切換$label", "Do Not Disturb access is needed to switch to $label"), listOf(L.t("前往授權", "Allow") to Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
        }
    }

    private fun brightness(c: Context, p: JSONObject): Res {
        if (!Settings.System.canWrite(c)) {
            return fail(
                L.t("需要「修改系統設定」權限才能調亮度", "\"Modify system settings\" is needed to change brightness"),
                listOf(L.t("前往授權", "Allow") to Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${c.packageName}"))),
            )
        }
        val cr = c.contentResolver
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        // 自動亮度開著時系統會蓋掉手動值，所以改成手動
        if (auto) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val now = toPct(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
        val pct = if (p.has("level")) {
            p.optInt("level").coerceIn(0, 100)
        } else {
            (now + p.optInt("delta", 1).sign * 20).coerceIn(0, 100)
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, fromPct(pct))
        return Res(L.t("亮度 $pct%", "Brightness $pct%") + if (auto) L.t("（已關閉自動亮度）", " (auto-brightness turned off)") else "")
    }

    // 系統亮度拉桿是感知曲線，用 2.2 次方換算，說 50% 才會接近拉桿一半
    private fun fromPct(p: Int) = (255 * (p / 100.0).pow(2.2)).roundToInt().coerceIn(1, 255)
    private fun toPct(v: Int) = (100 * (v / 255.0).pow(1 / 2.2)).roundToInt().coerceIn(0, 100)

    private fun dnd(c: Context, p: JSONObject): Res {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return fail(L.t("需要「勿擾權限」才能切換勿擾模式", "Do Not Disturb access is needed to switch Do Not Disturb"), listOf(L.t("前往授權", "Allow") to Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
        }
        val on = p.optBoolean("on", true)
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Res(if (on) L.t("勿擾模式開了", "Do Not Disturb is on") else L.t("勿擾模式關了", "Do Not Disturb is off"))
    }

    private fun media(c: Context, p: JSONObject): Res {
        val (code, label) = when (p.optString("cmd")) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to L.t("暫停", "Paused")
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to L.t("下一首", "Next song")
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to L.t("上一首", "Previous song")
            else -> KeyEvent.KEYCODE_MEDIA_PLAY to L.t("播放", "Playing")
        }
        val am = c.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return Res(label)
    }

    /**
     * 放音樂。沒指定歌：按播放鍵，接著上次的放。「喜歡的歌」：開 YT Music 的「喜歡的音樂」清單。
     * 指定歌名、歌手：有 YouTube 金鑰就先查到第一首，用歌曲連結開 YT Music（實測會直接播，播完自動接相似的歌）；
     * 沒金鑰或查不到，就用「搜尋並播放」指令——實測 YT Music 只會打開搜尋結果，要使用者點一下才放。
     */
    private fun play(c: Context, p: JSONObject): Res {
        if (p.optBoolean("liked")) return liked(c)
        val q = p.str("q") ?: return media(c, JSONObject().put("cmd", "play"))
        val app = p.str("app")?.lowercase().orEmpty()
        if (app.contains("youtube") && !app.contains("music")) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
            if (installed(c, YT)) i.setPackage(YT)
            return Res(L.t("在 YouTube 找「$q」", "Searching YouTube for \"$q\""), intent = i, leave = true)
        }
        if (!app.contains("spotify") && installed(c, YTM)) {
            val (id, why) = ytFirst(c, q)
            if (id != null) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$id")).setPackage(YTM)
                return Res(L.t("播放「$q」", "Playing \"$q\""), intent = i, leave = true)
            }
            // 面板開了別的 App 就收起來，原因用 Toast 讓使用者看得到
            why?.let { note(c, L.t("$it，先打開搜尋結果", "$it. Opening search results instead")) }
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q)
        val pkg = when {
            app.contains("spotify") && installed(c, SPOTIFY) -> SPOTIFY
            installed(c, YTM) -> YTM
            installed(c, SPOTIFY) -> SPOTIFY
            else -> null
        }
        pkg?.let { i.setPackage(it) }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q)))
        return Res(if (pkg == YTM) L.t("在 YT Music 找「$q」", "Searching YT Music for \"$q\"") else L.t("播放「$q」", "Playing \"$q\""), intent = i, leave = true, fallback = web)
    }

    /** 「播放我喜歡的歌」：YT Music 的「喜歡的音樂」清單，用連結打開會直接播（實測）。 */
    private fun liked(c: Context): Res {
        if (!installed(c, YTM)) return fail(L.t("要先裝 YT Music 才能播你喜歡的歌", "Install YT Music first to play your liked songs"))
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?list=LM")).setPackage(YTM)
        return Res(L.t("播放你喜歡的歌", "Playing your liked songs"), intent = i, leave = true)
    }

    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * 用 YouTube Data API 查第一支音樂影片：找到回 (影片 ID, null)，沒填金鑰回 (null, null)，查不到或出錯回 (null, 原因)。
     * 金鑰放標頭不放網址；金鑰若限制成「只給這個 App」，Google 看套件名稱＋簽章 SHA-1 這兩個標頭。
     */
    private fun ytFirst(c: Context, q: String): Pair<String?, String?> {
        val key = Prefs.ytKey(c)
        if (key.isEmpty()) return null to null
        return try {
            // 實測：type=video 也常把歌手頻道排第一，所以多拿幾筆、取第一支影片（一次查詢的額度一樣）
            val url = "https://www.googleapis.com/youtube/v3/search?part=id&type=video&videoCategoryId=10&maxResults=5&regionCode=TW&q=" +
                Uri.encode(q)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("X-Goog-Api-Key", key)
            conn.setRequestProperty("X-Android-Package", c.packageName)
            signSha1(c)?.let { conn.setRequestProperty("X-Android-Cert", it) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "YouTube search HTTP $code: ${body.take(300)}")
                return null to ytError(code, body)
            }
            val items = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("items")
            val id = (0 until (items?.length() ?: 0))
                .map { items?.optJSONObject(it)?.optJSONObject("id")?.optString("videoId").orEmpty() }
                .firstOrNull { videoId.matches(it) }
            if (id != null) id to null else null to L.t("YouTube 找不到「$q」", "YouTube has no result for \"$q\"")
        } catch (e: Exception) {
            Log.w(TAG, "YouTube search failed", e)
            null to L.t("連不上 YouTube", "Can't reach YouTube")
        }
    }

    /** YouTube API 的錯誤 → 使用者看得懂、知道去哪裡改的說法。 */
    private fun ytError(code: Int, body: String): String {
        val reason = runCatching {
            JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).optString("reason")
        }.getOrNull().orEmpty()
        return when {
            reason == "quotaExceeded" || reason == "dailyLimitExceeded" -> L.t("今天的 YouTube 免費查詢次數用完了", "Today's free YouTube lookups are used up")
            reason == "accessNotConfigured" -> L.t("這把金鑰還沒啟用 YouTube Data API", "YouTube Data API is not enabled for this key")
            code == 400 -> L.t("YouTube 金鑰無效", "The YouTube key is not valid")
            code == 403 -> L.t("YouTube 金鑰不能用在這個 App，請檢查金鑰的限制", "The YouTube key can't be used by this app. Check the key's restrictions")
            else -> L.t("YouTube 查詢失敗（$code）", "YouTube lookup failed ($code)")
        }
    }

    /** 這個 App 的簽章 SHA-1（大寫、不含冒號）。Google 金鑰限制成「Android 應用程式」時要對得上。 */
    fun signSha1(c: Context): String? = runCatching {
        val info = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    /** 從背景執行緒跳 Toast：面板打開別的 App 就收起，要讓使用者知道原因時用。 */
    private fun note(c: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(c.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun settings(p: JSONObject): Res {
        val (action, label) = when (p.optString("page")) {
            "wifi" -> Settings.Panel.ACTION_WIFI to "Wi-Fi"
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY to L.t("網路", "network ")
            "nfc" -> Settings.Panel.ACTION_NFC to "NFC"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to L.t("藍牙", "Bluetooth ")
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to L.t("螢幕", "display ")
            "sound" -> Settings.ACTION_SOUND_SETTINGS to L.t("音效", "sound ")
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY to L.t("電池", "battery ")
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to L.t("定位", "location ")
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS to L.t("應用程式", "app ")
            else -> Settings.ACTION_SETTINGS to ""
        }
        return Res(L.t("打開${label}設定", "Opening ${label}settings"), intent = Intent(action), leave = true, fallback = Intent(Settings.ACTION_SETTINGS))
    }

    private fun search(p: JSONObject): Res {
        val q = p.str("q") ?: return fail(L.t("要搜尋什麼？", "Search for what?"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        return Res(L.t("搜尋「$q」", "Searching for \"$q\""), intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q), leave = true, fallback = web)
    }

    private fun weather(c: Context, p: JSONObject): Res {
        val day = p.optInt("day", 0).coerceIn(0, 2)
        val place = p.str("place").orEmpty()
        var lat = p.optDouble("lat", Double.NaN)
        var lon = p.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            if (!has(c, Manifest.permission.ACCESS_COARSE_LOCATION)) return needPerm(c, L.t("位置", "location"))
            val loc = Place.current(c) ?: return fail(L.t("拿不到目前位置，請直接說地點，例如「台中明天天氣」", "I can't get your location. Say the place, for example \"weather in Tokyo tomorrow\""))
            lat = loc.latitude
            lon = loc.longitude
        }
        val url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f", lat, lon) +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val d = j.getJSONObject("daily")
        val hi = deg(d.getJSONArray("temperature_2m_max").optDouble(day))
        val lo = deg(d.getJSONArray("temperature_2m_min").optDouble(day))
        val rain = d.getJSONArray("precipitation_probability_max").optInt(day, -1)
        val sb = StringBuilder(place)
        if (day == 0) {
            val cur = j.getJSONObject("current")
            sb.append(L.t("現在${deg(cur.optDouble("temperature_2m"))}度，${wmo(cur.optInt("weather_code", -1))}，今天", "It's ${deg(cur.optDouble("temperature_2m"))}°C now, ${wmo(cur.optInt("weather_code", -1))}. Today "))
        } else {
            sb.append(if (day == 1) L.t("明天", "Tomorrow: ") else L.t("後天", "The day after tomorrow: ")).append(wmo(d.getJSONArray("weather_code").optInt(day, -1))).append(L.t("，", ", "))
        }
        sb.append(L.t("${lo}到${hi}度", "$lo to $hi°C"))
        if (rain >= 0) sb.append(L.t("，降雨機率$rain%", ", $rain% chance of rain"))
        return Res(sb.toString(), speak = true, info = true)
    }

    private fun deg(v: Double) = if (v.isNaN()) "?" else v.roundToInt().toString()

    /** WMO 天氣代碼 → 中文。 */
    private fun wmo(code: Int) = when (code) {
        0 -> L.t("晴天", "clear")
        1 -> L.t("大致晴朗", "mostly clear")
        2 -> L.t("多雲", "partly cloudy")
        3 -> L.t("陰天", "overcast")
        45, 48 -> L.t("有霧", "foggy")
        51, 53, 55, 56, 57 -> L.t("毛毛雨", "drizzle")
        61, 66 -> L.t("小雨", "light rain")
        63 -> L.t("下雨", "rain")
        65, 67 -> L.t("大雨", "heavy rain")
        71, 73, 75, 77 -> L.t("下雪", "snow")
        80, 81 -> L.t("陣雨", "showers")
        82 -> L.t("大陣雨", "heavy showers")
        85, 86 -> L.t("陣雪", "snow showers")
        95 -> L.t("雷雨", "thunderstorms")
        96, 99 -> L.t("雷雨夾冰雹", "thunderstorms with hail")
        else -> L.t("天氣不明", "unknown conditions")
    }
}

/** 手電筒：記住目前狀態，才能「切換」。 */
object Torch {
    @Volatile private var on = false
    private var id: String? = null
    private var ready = false

    @Synchronized
    fun init(c: Context) {
        if (ready) return
        val cm = c.applicationContext.getSystemService(CameraManager::class.java) ?: return
        id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) on = enabled
            }
        }, Handler(Looper.getMainLooper()))
        ready = true
    }

    /** want=null 表示切換；回傳之後的狀態。 */
    fun set(c: Context, want: Boolean?): Boolean {
        init(c)
        val cid = id ?: throw IllegalStateException(L.t("這支手機沒有閃光燈", "This phone has no flashlight"))
        val target = want ?: !on
        c.applicationContext.getSystemService(CameraManager::class.java).setTorchMode(cid, target)
        on = target
        return target
    }
}

/** 目前大概位置（只要粗略位置權限）：先用最近的紀錄，太舊才現抓。 */
object Place {
    fun current(c: Context): Location? {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val last = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3 * 3_600_000L) return last
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        val provider = if (runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        try {
            lm.getCurrentLocation(provider, null, Bg.pool) { loc ->
                fresh = loc
                latch.countDown()
            }
            latch.await(4, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("Place", "getCurrentLocation", e)
        }
        return fresh ?: last
    }
}
