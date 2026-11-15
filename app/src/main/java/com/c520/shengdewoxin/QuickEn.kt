package com.c520.shengdewoxin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 英文的常用指令規則（0.4.0）：手機語言不是中文時，Quick.match 會交給這裡。
 * 動作和中文版（Quick.matchZh）一樣，只是句型和念出來的話是英文；判斷不了的回 null，交給 AI。
 */
object QuickEn {
    private val small = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private const val W = "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|" +
        "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
    /** 一個數字：阿拉伯數字，或一到兩個英文數字（twenty five、twenty-five）。 */
    private const val N = "(?:\\d+|(?:$W)(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?)"

    private val lead = Regex("^(?:(?:hey|ok|okay|please|can you|could you|would you|will you|i need you to|i want you to)[, ]+)+")
    private val tail = Regex("(?:[, ]+(?:please|thanks|thank you|for me|now|right now))+$")
    private val durRe = Regex("\\b($N|an?|half an?)\\s*(?:and a half\\s*)?(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b(\\s*and a half)?")
    private val halfBefore = Regex("\\b($N|an?) and a half (hours?|hrs?|minutes?|mins?)\\b")
    private val clockRe = Regex(
        "\\b(?:(half past|quarter past|quarter to)\\s+)?($N)(?:[:.](\\d{2})|\\s+($N))?(?:\\s*(a\\.?m\\.?|p\\.?m\\.?|o'?clock))?" +
            "(?:\\s+(in the morning|in the afternoon|in the evening|at night|tonight))?"
    )
    private val dateWord = Regex(
        "day after tomorrow|next week|next month|\\b(?:mon|tues|wednes|thurs|fri|satur|sun)day\\b|\\bon the \\d+|" +
            "\\b(?:january|february|march|april|may|june|july|august|september|october|november|december)\\b"
    )

    /** "25"、"twenty five"、"twenty-five"、"seven" → 數字；不是數字回 null。 */
    fun num(raw: String): Int? {
        val s = raw.trim().lowercase()
        s.toIntOrNull()?.let { return it }
        if (s == "a" || s == "an") return 1
        val parts = s.split(' ', '-').filter { it.isNotEmpty() }
        return when (parts.size) {
            1 -> small.indexOf(parts[0]).takeIf { it >= 0 } ?: tens[parts[0]]
            2 -> {
                val t = tens[parts[0]] ?: return null
                val u = small.indexOf(parts[1]).takeIf { it in 1..9 } ?: return null
                t + u
            }
            else -> null
        }
    }

    /** 小寫、拿掉標點（保留時間的冒號和小數點）與開頭結尾的客套話。 */
    fun clean(s: String): String {
        val t = s.lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("[,!?，。！？]"), " ").replace(Regex("\\.(?!\\d|m\\b)"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return t.replace(lead, "").replace(tail, "").trim()
    }

    /** 句子裡的時間長度（秒）："an hour and a half"、"5 minutes 30 seconds"、"half an hour"。 */
    fun seconds(t: String): Int? {
        var sec = 0.0
        val s = halfBefore.replace(t) { "${it.groupValues[1]} ${it.groupValues[2]} and a half" }
        for (m in durRe.findAll(s)) {
            val n = m.groupValues[1]
            val unit = m.groupValues[2]
            val base = when {
                unit.startsWith("h") -> 3600
                unit.startsWith("m") -> 60
                else -> 1
            }
            val v = if (n.startsWith("half")) 0.5 else (num(n) ?: continue) + (if (m.groupValues[3].isNotEmpty()) 0.5 else 0.0)
            sec += v * base
        }
        return sec.toInt().takeIf { it > 0 }
    }

    /** 句子裡的時刻（24 小時制）："7:30 pm"、"half past six"、"seven in the evening"、"noon"。 */
    fun clock(t: String): Pair<Int, Int>? {
        if (Regex("\\bnoon\\b|\\bmidday\\b").containsMatchIn(t)) return 12 to 0
        if (Regex("\\bmidnight\\b").containsMatchIn(t)) return 0 to 0
        // 找第一個像時刻的：有冒號、有 am/pm、有 o'clock／half past，或前面是 at／for
        for (m in clockRe.findAll(t)) {
            val (pre, hs, colon, spaced, ampm, daypart) = m.destructured
            val before = t.substring(0, m.range.first).trimEnd()
            val marked = pre.isNotEmpty() || colon.isNotEmpty() || ampm.isNotEmpty() || daypart.isNotEmpty() ||
                before.endsWith(" at") || before.endsWith(" for") || before == "at" || before.endsWith(" me")
            if (!marked) continue
            var h = num(hs) ?: continue
            var min = when {
                colon.isNotEmpty() -> colon.toInt()
                spaced.isNotEmpty() -> num(spaced) ?: continue
                else -> 0
            }
            when (pre) {
                "half past" -> min = 30
                "quarter past" -> min = 15
                "quarter to" -> { min = 45; h = if (h == 0) 23 else h - 1 }
            }
            val pm = ampm.startsWith("p") || daypart == "in the afternoon" || daypart == "in the evening" || daypart == "at night" || daypart == "tonight"
            val am = ampm.startsWith("a") || daypart == "in the morning"
            if (pm && h in 1..11) h += 12
            if (am && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return h to min
        }
        return null
    }

    /** 念給人聽的時刻："7:30 AM"。 */
    fun hm(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12" + (if (m > 0) ":%02d".format(m) else "") + if (h < 12) " AM" else " PM"
    }

    /** 念給人聽的長度："1 hour 30 minutes"。 */
    fun dur(sec: Int): String {
        fun unit(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return listOfNotNull(
            if (h > 0) unit(h, "hour") else null,
            if (m > 0) unit(m, "minute") else null,
            if (s > 0) unit(s, "second") else null,
        ).joinToString(" ")
    }

    private fun has(t: String, re: String) = Regex(re).containsMatchIn(t)

    fun match(c: Context, raw: String): Plan? {
        val t = clean(raw)
        if (t.isEmpty()) return null
        return rules(t) ?: openApp(c, t)
    }

    /** 不需要查手機上有哪些 App 的規則（單元測試直接測這個）；t 是 clean() 過的句子。 */
    fun rules(t: String): Plan? {
        // 現在幾點、今天幾號：本機直接回答
        if (t.length <= 28 && !has(t, "alarm|timer|remind|wake|tomorrow|yesterday|next |last ")) {
            val now = LocalDateTime.now()
            if (has(t, "what time|what's the time|the time now|current time|^time$")) return Plan("It's ${hm(now.hour, now.minute)}", emptyList())
            if (has(t, "what day|what's the date|what is the date|today's date|what date|^date$")) {
                return Plan("Today is " + now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)), emptyList())
            }
        }

        // 天氣（沒說地點）：用手機位置直接查；有說地點的交給 AI
        Regex(
            "^(?:(?:what's|what is|how's|how is|check|tell me) )?(?:the )?(?:weather|forecast|temperature)(?: like| forecast)?(?: for)?(?: (today|now|right now|tomorrow|the day after tomorrow))?$|" +
                "^(?:will|is) it (?:going to )?(?:rain|raining|be rainy)(?: (today|tomorrow|the day after tomorrow))?$|" +
                "^do i need (?:an umbrella|a jacket|a coat)(?: (today|tomorrow))?$|^how (?:hot|cold|warm) is it(?: (today|outside|tomorrow))?$|" +
                "^(today|tomorrow)'s weather$"
        ).find(t)?.let { m ->
            val day = when {
                m.value.contains("day after tomorrow") -> 2
                m.value.contains("tomorrow") -> 1
                else -> 0
            }
            return Plan.of("", "weather", JSONObject().put("day", day))
        }

        // 計時："timer for 5 minutes"、"count down 30 seconds"、"remind me in ten minutes to turn off the stove"
        val timerWord = has(t, "\\btimer\\b|count ?down")
        val laterWord = has(t, "\\bin $N|\\bin an?\\b|\\bin half\\b") && has(t, "remind me|wake me|tell me|alert me")
        if ((timerWord || laterWord) && !dateWord.containsMatchIn(t)) {
            seconds(t)?.let { sec ->
                val p = JSONObject().put("sec", sec)
                (Regex("remind me (?:in .+? )?to (.+?)(?: in $N.*| in an? .*| in half .*)?$").find(t))?.let { p.put("label", it.groupValues[1]) }
                return Plan.of(if (laterWord && !timerWord) "OK, I'll remind you in ${dur(sec)}" else "OK, timer for ${dur(sec)}", "timer", p)
            }
        }

        // 鬧鐘："wake me at 7 tomorrow morning"、"set an alarm for 6:30"；有說日期的交給 AI 寫行事曆
        if (has(t, "\\balarm\\b|wake me") && !dateWord.containsMatchIn(t)) {
            clock(t)?.let { (h, m) ->
                return Plan.of("OK, I'll wake you at ${hm(h, m)}", "alarm", JSONObject().put("h", h).put("m", m))
            }
        }

        // 手電筒
        if (has(t, "flashlight|flash light|\\btorch\\b")) {
            val p = JSONObject()
            when {
                has(t, "\\boff\\b") -> p.put("on", false)
                has(t, "\\bon\\b") -> p.put("on", true)
            }
            return Plan.of("", "flashlight", p)
        }

        // 勿擾
        if (has(t, "do not disturb|don't disturb|\\bdnd\\b")) {
            return Plan.of("", "dnd", JSONObject().put("on", !has(t, "\\boff\\b|disable|stop|end|cancel|exit|leave")))
        }

        // 響鈴模式（手機的靜音／震動）
        if (!has(t, "music|media|video|song")) {
            if (has(t, "^unmute(?: my| the)?(?: phone)?$|sound (?:back )?on|ring(?:er)? mode|(?:turn off|cancel|exit|leave|disable) (?:silent|vibrate|vibration|mute)|not silent")) {
                return Plan(
                    "",
                    listOf(
                        Act("ringer", JSONObject().put("mode", "normal")),
                        Act("volume", JSONObject().put("stream", "media").put("mute", false)),
                    ),
                )
            }
            if (has(t, "vibrat")) return Plan.of("", "ringer", JSONObject().put("mode", "vibrate"))
            if (Regex("^(?:(?:turn on|switch to|go|go to|set) )?(?:silent|silent mode|mute|mute (?:my |the )?phone|silence (?:my |the )?phone)$").matches(t)) {
                return Plan.of("", "ringer", JSONObject().put("mode", "silent"))
            }
        }

        // 播放控制（整句完全相同才算）
        when (t) {
            "pause", "pause music", "pause the music", "stop music", "stop the music", "stop playing", "stop" -> return media("pause")
            "play", "resume", "continue", "resume music", "resume the music", "keep playing", "start playing" -> return media("play")
            "next", "next song", "next track", "skip", "skip song", "skip this song", "skip this" -> return media("next")
            "previous", "previous song", "previous track", "last song", "go back" -> return media("prev")
        }

        music(t)?.let { return it }

        // 音量
        if (has(t, "volume|louder|quieter|\\bmute\\b|unmute|turn it (?:up|down)") && !has(t, "\\bcall\\b")) {
            val stream = when {
                has(t, "ring|notification") -> "ring"
                has(t, "alarm") -> "alarm"
                else -> "media"
            }
            val p = JSONObject().put("stream", stream)
            val lv = Regex("volume (?:to |at |level )?($N)\\s*(%|percent)?").find(t)
            when {
                has(t, "unmute") -> p.put("mute", false)
                has(t, "\\bmute\\b") -> p.put("mute", true)
                lv != null -> p.put("level", (num(lv.groupValues[1]) ?: return null).coerceIn(0, 100))
                has(t, "\\bmax|maximum|all the way up|\\bfull\\b") -> p.put("level", 100)
                has(t, "\\bmin\\b|minimum|all the way down") -> p.put("level", 0)
                has(t, "\\bup\\b|louder|increase|raise|higher") -> p.put("delta", 1)
                has(t, "\\bdown\\b|quieter|lower|decrease|reduce|softer") -> p.put("delta", -1)
                else -> return null
            }
            return Plan.of("", "volume", p)
        }

        // 螢幕亮度
        if (has(t, "brightness|brighter|dimmer|\\bdim\\b|too bright|too dark")) {
            val p = JSONObject()
            val lv = Regex("brightness (?:to |at |level )?($N)\\s*(%|percent)?").find(t)
            when {
                lv != null -> p.put("level", (num(lv.groupValues[1]) ?: return null).coerceIn(0, 100))
                has(t, "\\bmax|maximum|all the way up|\\bfull\\b|brightest") -> p.put("level", 100)
                has(t, "\\bmin\\b|minimum|all the way down|dimmest|lowest") -> p.put("level", 0)
                has(t, "brighter|\\bup\\b|increase|raise|higher|too dark") -> p.put("delta", 1)
                has(t, "dimmer|\\bdim\\b|\\bdown\\b|lower|decrease|reduce|too bright") -> p.put("delta", -1)
                else -> return null
            }
            return Plan.of("", "brightness", p)
        }

        // 導航
        Regex("^(?:navigate to|navigate me to|directions to|get directions to|take me to|drive to|drive me to|walk to|how do i get to) (.{2,})$").find(t)?.let {
            return Plan.of("", "navigate", JSONObject().put("q", it.groupValues[1]))
        }

        // 打電話（"call mom and tell her I'm late" 這種要傳話的交給 AI）
        Regex("^(?:call|phone|dial|make a call to) (.+)$").find(t)?.let {
            val name = it.groupValues[1]
            if (!has(name, "\\b(?:and|to) (?:tell|say|ask|let)\\b")) return Plan.of("", "call", JSONObject().put("name", name))
        }

        // 設定頁
        val page = when {
            has(t, "wi-?fi|wireless") -> "wifi"
            has(t, "bluetooth") -> "bluetooth"
            has(t, "mobile data|cellular|mobile network") -> "internet"
            has(t, "location|\\bgps\\b") -> "location"
            has(t, "\\bnfc\\b") -> "nfc"
            t == "settings" || t == "open settings" || t == "system settings" || t == "open the settings" -> "main"
            else -> null
        }
        if (page != null && (page == "main" || has(t, "\\bon\\b|\\boff\\b|open|settings|toggle|connect|enable|disable|switch"))) {
            return Plan.of("", "settings", JSONObject().put("page", page))
        }

        return null
    }

    /** 打開 App：名稱夠像才直接開，不確定的交給 AI。 */
    private fun openApp(c: Context, t: String): Plan? {
        val m = Regex("^(?:open|launch|start|run|go to) (?:the )?(.+?)(?: app)?$").find(t) ?: return null
        val hit = Apps.find(c, listOf(m.groupValues[1])) ?: return null
        return if (hit.second >= 85) Plan.of("", "open_app", JSONObject().put("names", JSONArray().put(hit.first.label))) else null
    }

    private fun media(cmd: String) = Plan.of("", "media", JSONObject().put("cmd", cmd))

    private val musicApp = Regex("\\s*(?:on|in|with|using|from) (yt music|youtube music|youtube|spotify)$")
    private val anyMusic = Regex("^(?:(?:i want to|i'd like to|i wanna|let's) )?(?:play|listen to|put on|hear)(?: me)?(?: some| a| any| random)?(?: random)? (?:music|songs?|tunes|a song)$")
    private val likedMusic = Regex(
        "^(?:(?:i want to|i'd like to|i wanna) )?(?:play|listen to|put on|hear) (?:my |the )?(?:liked|favorite|favourite|favorites|favourites)(?: songs?| music| playlist| tracks)?$|" +
            "^play (?:the )?(?:songs|music) i (?:like|liked|love)$"
    )
    private val someMusic = Regex("^(?:(?:i want to|i'd like to|i wanna) (?:listen to|hear)|play|listen to|put on) (?:some |the song |the album |songs by |music by )?(.+?)(?: songs| music)?$")
    // "play a joke"、"play the news" 不是放音樂，交給 AI
    private val notMusic = Regex("\\b(?:joke|story|news|weather|podcast|audiobook|game|video|movie)\\b")

    /** "play some music" → 按播放鍵；"play Taylor Swift on Spotify" → 在音樂 App 找出來。不是放音樂的句子回 null。 */
    private fun music(t: String): Plan? {
        val m = musicApp.find(t)
        val rest = if (m != null) t.removeRange(m.range).trim() else t
        if (anyMusic.matches(rest) || (m != null && rest in setOf("play", "listen", "play music"))) return media("play")
        if (likedMusic.matches(rest)) return Plan.of("", "play", JSONObject().put("liked", true))
        val q = someMusic.find(rest)?.groupValues?.get(1)?.trim() ?: return null
        if (q.isEmpty() || notMusic.containsMatchIn(q)) return null
        val p = JSONObject().put("q", q)
        m?.groupValues?.get(1)?.let { p.put("app", if (it.contains("music")) "youtube music" else it) }
        return Plan.of("", "play", p)
    }
}
