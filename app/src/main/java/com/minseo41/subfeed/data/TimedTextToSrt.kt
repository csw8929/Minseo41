package com.minseo41.subfeed.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

// YouTube timedtext XML(srv1 transcript 형식)을 SRT로 변환.
// 형식: <transcript><text start="0.32" dur="1.5">Hello</text>...</transcript>
object TimedTextToSrt {
    fun convert(xml: String): String {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val builder = StringBuilder()
        var index = 1
        var startSec = 0.0
        var durSec = 0.0
        var text = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "text") {
                    startSec = parser.getAttributeValue(null, "start")?.toDoubleOrNull() ?: 0.0
                    durSec = parser.getAttributeValue(null, "dur")?.toDoubleOrNull() ?: 0.0
                    text = ""
                }
                XmlPullParser.TEXT -> text = parser.text.orEmpty()
                XmlPullParser.END_TAG -> if (parser.name == "text") {
                    val cleaned = decodeEntities(text).trim()
                    if (cleaned.isNotEmpty()) {
                        val startMs = (startSec * 1000).toLong()
                        val endMs = ((startSec + durSec) * 1000).toLong()
                        builder.append(index)
                        builder.append('\n')
                        builder.append(formatSrtTime(startMs))
                        builder.append(" --> ")
                        builder.append(formatSrtTime(endMs))
                        builder.append('\n')
                        builder.append(cleaned)
                        builder.append("\n\n")
                        index++
                    }
                }
            }
            event = parser.next()
        }
        return builder.toString()
    }

    private fun formatSrtTime(ms: Long): String {
        val totalSec = ms / 1000
        val millis = ms % 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }

    private fun decodeEntities(text: String): String =
        text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\n", " ")
}
