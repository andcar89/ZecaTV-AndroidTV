package com.ze.iptvplayer.parser

import android.util.Xml
import com.ze.iptvplayer.model.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Result of parsing an XMLTV document: programmes grouped by channel id, plus id -> display-name. */
data class EpgParseResult(
    val programmesByChannelId: Map<String, List<EpgProgramme>>,
    val channelNames: Map<String, String>
)

/**
 * Streams an XMLTV EPG document and groups programmes by channel id, matching the
 * "channel" attribute on <programme> against Channel.id (tvg-id / epg_channel_id) —
 * or against a user-defined mapping, see EpgMappingStore.
 *
 * Also collects each <channel id="..."><display-name>...</display-name></channel>
 * so the UI can show human-readable names when a user picks a guide channel to map to.
 */
object EpgXmlParser {

    // XMLTV timestamps look like: "20240115120000 +0000" — always a fixed-width digit block,
    // optionally followed by a UTC offset. Parsed manually rather than via SimpleDateFormat,
    // whose "Z" offset-pattern handling is inconsistent enough across Android versions that a
    // parse failure silently falls back to the device's LOCAL timezone instead of erroring —
    // which shifts every timestamp by the local UTC offset and makes "now" stop matching while
    // "next" (a much looser check) often still looks plausible. See parseDate() below.

    fun parse(rawStream: InputStream, isGzip: Boolean): EpgParseResult {
        val input = if (isGzip) GZIPInputStream(rawStream) else rawStream
        val programmes = mutableMapOf<String, MutableList<EpgProgramme>>()
        val channelNames = mutableMapOf<String, String>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var eventType = parser.eventType
        var currentChannelId: String? = null
        var currentTitle: String? = null
        var currentDesc: String? = null
        var currentStart = 0L
        var currentStop = 0L
        var inTitle = false
        var inDesc = false

        // <channel> parsing state (separate from <programme> state above)
        var inChannelTag = false
        var currentChannelTagId: String? = null
        var inDisplayName = false
        var currentDisplayName: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannelTag = true
                        currentChannelTagId = parser.getAttributeValue(null, "id")
                        currentDisplayName = null
                    }
                    "display-name" -> if (inChannelTag) inDisplayName = true
                    "programme" -> {
                        currentChannelId = parser.getAttributeValue(null, "channel")
                        currentStart = parseDate(parser.getAttributeValue(null, "start"))
                        currentStop = parseDate(parser.getAttributeValue(null, "stop"))
                        currentTitle = null
                        currentDesc = null
                    }
                    "title" -> inTitle = true
                    "desc" -> inDesc = true
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) currentTitle = (currentTitle ?: "") + parser.text
                    if (inDesc) currentDesc = (currentDesc ?: "") + parser.text
                    if (inDisplayName) currentDisplayName = (currentDisplayName ?: "") + parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "display-name" -> inDisplayName = false
                    "channel" -> {
                        val id = currentChannelTagId
                        val name = currentDisplayName
                        // Only record the first display-name we see per channel id.
                        if (id != null && name != null && !channelNames.containsKey(id)) {
                            channelNames[id] = name
                        }
                        inChannelTag = false
                    }
                    "programme" -> {
                        val chId = currentChannelId
                        if (chId != null && currentTitle != null) {
                            programmes.getOrPut(chId) { mutableListOf() }.add(
                                EpgProgramme(chId, currentTitle!!, currentDesc, currentStart, currentStop)
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        // Some providers omit the "stop" attribute (technically required by the XMLTV spec, but
        // not everyone complies) — a programme with no stop time defaults to stopMillis=0, which
        // means it can never be "now" (nothing real can fall between a valid start and epoch
        // zero) even though its start time is perfectly valid. It still shows up fine as "next"
        // since that only checks the start time — which is exactly the "next works, now doesn't"
        // symptom this fixes. Infer the missing stop from the next programme's start time per
        // channel, or a default 2h duration for a channel's last known programme.
        val fixedProgrammes = programmes.mapValues { (_, list) ->
            val sorted = list.sortedBy { it.startMillis }
            sorted.mapIndexed { index, p ->
                if (p.stopMillis <= p.startMillis) {
                    val inferredStop = sorted.getOrNull(index + 1)?.startMillis ?: (p.startMillis + 2 * 60 * 60 * 1000L)
                    p.copy(stopMillis = inferredStop)
                } else {
                    p
                }
            }
        }

        return EpgParseResult(fixedProgrammes, channelNames)
    }

    /**
     * Parses an XMLTV timestamp like "20240115120000 +0000" (or "-0500", or with no offset at
     * all, in which case UTC is assumed) into epoch millis. Done with plain substring/int parsing
     * instead of SimpleDateFormat so the result never silently depends on the device's timezone.
     */
    private fun parseDate(value: String?): Long {
        if (value == null || value.length < 14) return 0
        return try {
            val year = value.substring(0, 4).toInt()
            val month = value.substring(4, 6).toInt() - 1 // Calendar months are 0-based
            val day = value.substring(6, 8).toInt()
            val hour = value.substring(8, 10).toInt()
            val minute = value.substring(10, 12).toInt()
            val second = value.substring(12, 14).toInt()

            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(year, month, day, hour, minute, second)

            val offsetPart = value.substring(14).trim()
            cal.timeInMillis - parseOffsetMillis(offsetPart)
        } catch (e: Exception) {
            0
        }
    }

    /** Parses a "+HHMM" / "-HHMM" UTC offset into millis; returns 0 (UTC) if absent/malformed. */
    private fun parseOffsetMillis(offsetPart: String): Long {
        if (offsetPart.length < 5) return 0
        val sign = if (offsetPart[0] == '-') -1 else 1
        val hours = offsetPart.substring(1, 3).toIntOrNull() ?: return 0
        val minutes = offsetPart.substring(3, 5).toIntOrNull() ?: 0
        return sign * (hours * 3_600_000L + minutes * 60_000L)
    }
}
