package gr.wsol.billablecalls.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything the app keeps, in one JSON file on the phone (files/data.json), written atomically
 * after every change. One in-memory copy, shared by the screens, the call job and the reminders.
 */
object Store {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private var file: File? = null
    @Volatile private var ready = false
    private val _data = MutableStateFlow(Data())
    val flow: StateFlow<Data> = _data
    val data: Data get() = _data.value
    val settings: Settings get() = _data.value.settings

    @Synchronized fun init(context: Context) {
        if (ready) return
        ready = true
        val f = File(context.filesDir, "data.json")
        file = f
        val loaded = runCatching { if (f.exists()) json.decodeFromString(Data.serializer(), f.readText()) else null }.getOrNull()
            ?: runCatching { File(context.filesDir, "data.json.bak").takeIf { it.exists() }?.let { json.decodeFromString(Data.serializer(), it.readText()) } }.getOrNull()
        _data.value = loaded ?: Data()
        Lang.english = _data.value.settings.english
    }

    /** For tests: no file. */
    @Synchronized fun inMemory(d: Data = Data()) { ready = true; file = null; _data.value = d; Lang.english = d.settings.english }

    @Synchronized fun update(change: (Data) -> Data): Data {
        val d = change(_data.value)
        _data.value = d
        Lang.english = d.settings.english
        save(d)
        return d
    }

    fun settings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }

    fun encode(d: Data = data): String = json.encodeToString(Data.serializer(), d)
    fun decode(text: String): Data = json.decodeFromString(Data.serializer(), text)

    private fun save(d: Data) {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "data.json.tmp")
            tmp.writeText(encode(d))
            if (f.exists()) f.copyTo(File(f.parentFile, "data.json.bak"), overwrite = true)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Report.note("store", it.toString()) }
    }
}
