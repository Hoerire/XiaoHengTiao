package com.eta.sideindexx.config

import com.eta.sideindexx.util.XLog
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

/** XSharedPreferences 不可用时的兜底：直接解析模块的 SharedPreferences XML */
class DirectPrefs(private val file: File) {

    fun exists(): Boolean = try { file.isFile && file.canRead() } catch (_: Throwable) { false }

    fun read(): Map<String, Any?>? = try {
        val result = HashMap<String, Any?>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        file.inputStream().buffered().use { stream ->
            parser.setInput(stream, "utf-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val key = parser.getAttributeValue(null, "name")
                    val value = parser.getAttributeValue(null, "value")
                    if (key != null) {
                        when (parser.name) {
                            "string" -> result[key] = parser.nextText()
                            "boolean" -> result[key] = value?.toBoolean()
                            "int" -> result[key] = value?.toIntOrNull()
                            "long" -> result[key] = value?.toLongOrNull()
                            "float" -> result[key] = value?.toFloatOrNull()
                        }
                    }
                }
                event = parser.next()
            }
        }
        result
    } catch (t: Throwable) {
        XLog.w("直读配置文件失败：$t")
        null
    }
}
