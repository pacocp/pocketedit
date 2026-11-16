package dev.pocketedit

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val fontSize: Int = 16,
    val wordWrap: Boolean = true,
    val monospace: Boolean = false, // for plain text / markdown; code is always monospace
    val highlight: Boolean = true,
)

data class RecentFile(val uri: String, val name: String, val openedAt: Long) {
    val location: String get() = FileRepository.locationLabel(Uri.parse(uri))
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pocketedit", Context.MODE_PRIVATE)

    fun loadSettings() = Settings(
        fontSize = sp.getInt("fontSize", 16),
        wordWrap = sp.getBoolean("wordWrap", true),
        monospace = sp.getBoolean("monospace", false),
        highlight = sp.getBoolean("highlight", true),
    )

    fun saveSettings(s: Settings) {
        sp.edit()
            .putInt("fontSize", s.fontSize)
            .putBoolean("wordWrap", s.wordWrap)
            .putBoolean("monospace", s.monospace)
            .putBoolean("highlight", s.highlight)
            .apply()
    }

    var lastUri: String?
        get() = sp.getString("lastUri", null)
        set(v) = sp.edit().putString("lastUri", v).apply()

    fun loadRecent(): List<RecentFile> = try {
        val arr = JSONArray(sp.getString("recent", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentFile(o.getString("uri"), o.getString("name"), o.optLong("t"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Adds/moves [uri] to the top. Returns uris that fell off the list (to release permissions). */
    fun addRecent(uri: Uri, name: String): Pair<List<RecentFile>, List<String>> {
        val current = loadRecent().filterNot { it.uri == uri.toString() }
        val all = listOf(RecentFile(uri.toString(), name, System.currentTimeMillis())) + current
        val kept = all.take(MAX_RECENT)
        val dropped = all.drop(MAX_RECENT).map { it.uri }
        storeRecent(kept)
        return kept to dropped
    }

    fun removeRecent(uri: String): List<RecentFile> {
        val kept = loadRecent().filterNot { it.uri == uri }
        storeRecent(kept)
        return kept
    }

    private fun storeRecent(list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("t", it.openedAt)) }
        sp.edit().putString("recent", arr.toString()).apply()
    }

    companion object {
        const val MAX_RECENT = 20
    }
}

/** Unsaved work kept in app storage so nothing is lost if Android kills the app. */
data class Draft(val info: DocInfo, val text: String, val savedText: String)

class DraftStore(context: Context) {
    private val dir = File(context.filesDir, "draft")

    fun save(d: Draft) {
        dir.mkdirs()
        File(dir, "text.txt").writeText(d.text)
        File(dir, "saved.txt").writeText(d.savedText)
        val meta = JSONObject()
            .put("uri", d.info.uri?.toString() ?: "")
            .put("name", d.info.name)
            .put("lineEnding", d.info.lineEnding.name)
            .put("bom", d.info.bom)
            .put("charset", d.info.charset)
            .put("lastModified", d.info.lastModified ?: -1L)
        File(dir, "meta.json").writeText(meta.toString())
    }

    fun load(): Draft? = try {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) null else {
            val m = JSONObject(metaFile.readText())
            val info = DocInfo(
                uri = m.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                name = m.getString("name"),
                lineEnding = LineEnding.valueOf(m.optString("lineEnding", "LF")),
                bom = m.optBoolean("bom"),
                charset = m.optString("charset", DocInfo.UTF8),
                lastModified = m.optLong("lastModified", -1L).takeIf { it > 0 },
            )
            Draft(info, File(dir, "text.txt").readText(), File(dir, "saved.txt").readText())
        }
    } catch (_: Exception) {
        null
    }

    fun clear() {
        dir.deleteRecursively()
    }
}
