package com.c520.shengdewoxin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 英文指令規則：數字、時間長度、時刻，和不用查 App 清單的句型。 */
class QuickEnTest {
    private fun act(s: String): String? = QuickEn.rules(QuickEn.clean(s))?.acts?.joinToString("+") { a ->
        a.t + a.p.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> "\"$k\":" + a.p.get(k).let { v -> if (v is String) "\"$v\"" else v.toString() } }
    }
    private fun say(s: String): String? = QuickEn.rules(QuickEn.clean(s))?.say

    @Test
    fun numbers() {
        assertEquals(25, QuickEn.num("25"))
        assertEquals(25, QuickEn.num("twenty five"))
        assertEquals(25, QuickEn.num("twenty-five"))
        assertEquals(7, QuickEn.num("seven"))
        assertEquals(50, QuickEn.num("fifty"))
        assertNull(QuickEn.num("lots"))
    }

    @Test
    fun durations() {
        assertEquals(300, QuickEn.seconds("set a timer for 5 minutes"))
        assertEquals(330, QuickEn.seconds("5 minutes 30 seconds"))
        assertEquals(5400, QuickEn.seconds("an hour and a half"))
        assertEquals(5400, QuickEn.seconds("one and a half hours"))
        assertEquals(1800, QuickEn.seconds("half an hour"))
        assertEquals(600, QuickEn.seconds("remind me in ten minutes"))
        assertNull(QuickEn.seconds("soon"))
    }

    @Test
    fun clocks() {
        assertEquals(7 to 0, QuickEn.clock("wake me at 7"))
        assertEquals(19 to 30, QuickEn.clock("set an alarm for 7:30 pm"))
        assertEquals(6 to 30, QuickEn.clock("alarm at half past six"))
        assertEquals(18 to 45, QuickEn.clock("alarm at quarter to seven in the evening"))
        assertEquals(7 to 0, QuickEn.clock("wake me up at seven in the morning"))
        assertEquals(0 to 15, QuickEn.clock("alarm for 12:15 a.m."))
        assertEquals(12 to 0, QuickEn.clock("wake me at noon"))
        assertEquals(6 to 5, QuickEn.clock("alarm at 6 05"))
    }

    @Test
    fun spoken() {
        assertEquals("7:30 AM", QuickEn.hm(7, 30))
        assertEquals("12 PM", QuickEn.hm(12, 0))
        assertEquals("12:05 AM", QuickEn.hm(0, 5))
        assertEquals("1 hour 30 minutes", QuickEn.dur(5400))
        assertEquals("45 seconds", QuickEn.dur(45))
    }

    @Test
    fun cleaning() {
        assertEquals("turn on the flashlight", QuickEn.clean("Hey, can you turn on the flashlight, please?"))
        assertEquals("set an alarm for 7:30 a.m", QuickEn.clean("Set an alarm for 7:30 a.m."))
    }

    @Test
    fun alarmsAndTimers() {
        assertEquals("""alarm{"h":7,"m":0}""", act("Wake me up at 7 tomorrow morning"))
        assertEquals("OK, I'll wake you at 7 AM", say("Wake me up at 7 tomorrow morning"))
        assertEquals("""alarm{"h":18,"m":30}""", act("Set an alarm for 6:30 PM"))
        assertEquals("""timer{"sec":600}""", act("Set a timer for ten minutes"))
        assertEquals("""timer{"label":"turn off the stove","sec":600}""", act("Remind me in 10 minutes to turn off the stove"))
        assertEquals("OK, I'll remind you in 10 minutes", say("Remind me in 10 minutes to turn off the stove"))
        // 有說日期的交給 AI 寫行事曆
        assertNull(act("Set an alarm for 7 on Friday"))
    }

    @Test
    fun phoneControls() {
        assertEquals("""flashlight{"on":true}""", act("Turn on the flashlight"))
        assertEquals("""flashlight{"on":false}""", act("flashlight off"))
        assertEquals("""dnd{"on":true}""", act("Turn on do not disturb"))
        assertEquals("""dnd{"on":false}""", act("turn off do not disturb"))
        assertEquals("""ringer{"mode":"normal"}+volume{"mute":false,"stream":"media"}""", act("Unmute"))
        assertEquals("""ringer{"mode":"vibrate"}""", act("vibrate mode"))
        assertEquals("""ringer{"mode":"silent"}""", act("silent mode"))
        assertEquals("""volume{"level":50,"stream":"media"}""", act("Set the volume to 50"))
        assertEquals("""volume{"level":70,"stream":"media"}""", act("set media volume to 70"))
        assertEquals("""volume{"delta":1,"stream":"ring"}""", act("turn the ring volume up"))
        assertEquals("""volume{"delta":-1,"stream":"media"}""", act("volume down"))
        assertEquals("""brightness{"delta":1}""", act("make the screen brighter"))
        assertEquals("""brightness{"level":30}""", act("set brightness to 30%"))
    }

    @Test
    fun mediaAndMusic() {
        assertEquals("""media{"cmd":"pause"}""", act("Pause"))
        assertEquals("""media{"cmd":"next"}""", act("next song"))
        assertEquals("""media{"cmd":"play"}""", act("Play some music"))
        assertEquals("""play{"liked":true}""", act("play my liked songs"))
        assertEquals("""play{"q":"taylor swift"}""", act("Play Taylor Swift"))
        assertEquals("""play{"app":"spotify","q":"coldplay"}""", act("play coldplay on spotify"))
        assertNull(act("play the news"))
    }

    @Test
    fun placesPeopleSettings() {
        assertEquals("""navigate{"q":"central station"}""", act("Navigate to Central Station"))
        assertEquals("""call{"name":"mom"}""", act("Call mom"))
        assertNull(act("call mom and tell her I'm late"))
        assertEquals("""settings{"page":"wifi"}""", act("open wifi settings"))
        assertEquals("""settings{"page":"bluetooth"}""", act("turn on bluetooth"))
        assertEquals("""weather{"day":1}""", act("Will it rain tomorrow?"))
        assertEquals("""weather{"day":0}""", act("What's the weather like?"))
        assertNull(act("what's the weather in Tokyo"))
    }
}
