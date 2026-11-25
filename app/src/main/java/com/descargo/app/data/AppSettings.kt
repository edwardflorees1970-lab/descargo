package com.descargo.app.data

import android.content.Context
import android.content.SharedPreferences
import com.descargo.app.engine.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) { SYSTEM("Igual que el sistema"), DARK("Oscuro"), LIGHT("Claro") }

enum class FolderMode(val label: String) {
    DOWNLOADS("Todo en Descargas"),
    MEDIA("Música y Películas (audio en Music, video en Movies)"),
}

enum class NameTemplate(val label: String, val pattern: String) {
    TITLE("Título", "%(title).150B.%(ext)s"),
    TITLE_ID("Título [id]", "%(title).120B [%(id)s].%(ext)s"),
    CHANNEL_TITLE("Canal - Título", "%(uploader,channel|Desconocido).60B - %(title).120B.%(ext)s"),
    DATE_TITLE("Fecha - Título", "%(upload_date>%Y-%m-%d|sin-fecha)s - %(title).120B.%(ext)s"),
}

data class Settings(
    /** Max height preselected in the Video tab and used by playlist presets. 0 = best available. */
    val videoHeight: Int = 1080,
    val mp3Kbps: Int = 320,
    val openTab: Kind = Kind.VIDEO,
    val maxConcurrent: Int = 2,
    val wifiOnly: Boolean = false,
    /** yt-dlp --limit-rate value, "" = unlimited. */
    val rateLimit: String = "",
    val embedSubs: Boolean = false,
    val autoSubs: Boolean = false,
    val subLangs: String = "es.*,en.*",
    val useCookies: Boolean = false,
    val watchClipboard: Boolean = true,
    val nightlyEngine: Boolean = false,
    // YouTube extras
    val sponsorBlock: Boolean = false,
    val chapters: Boolean = true,
    val splitArtist: Boolean = true,
    // Subscriptions
    val subsIntervalH: Int = 6,
    // Look & files
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val folderMode: FolderMode = FolderMode.DOWNLOADS,
    val folderName: String = "Descargo",
    val bySite: Boolean = false,
    val nameTemplate: NameTemplate = NameTemplate.TITLE,
    /** SAF tree the user picked ("" = use the default public folders). */
    val treeUri: String = "",
    /** First-run folder question already answered (picked or skipped). */
    val folderChosen: Boolean = false,
)

object AppSettings {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state
    val current get() = _state.value

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, def: E): E =
        runCatching { enumValueOf<E>(getString(key, null)!!) }.getOrDefault(def)

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = Settings()
        val p = prefs
        _state.value = Settings(
            videoHeight = p.getInt("videoHeight", d.videoHeight),
            mp3Kbps = p.getInt("mp3Kbps", d.mp3Kbps),
            openTab = p.enum("openTab", d.openTab),
            maxConcurrent = p.getInt("maxConcurrent", d.maxConcurrent),
            wifiOnly = p.getBoolean("wifiOnly", d.wifiOnly),
            rateLimit = p.getString("rateLimit", d.rateLimit) ?: "",
            embedSubs = p.getBoolean("embedSubs", d.embedSubs),
            autoSubs = p.getBoolean("autoSubs", d.autoSubs),
            subLangs = p.getString("subLangs", d.subLangs) ?: d.subLangs,
            useCookies = p.getBoolean("useCookies", d.useCookies),
            watchClipboard = p.getBoolean("watchClipboard", d.watchClipboard),
            nightlyEngine = p.getBoolean("nightlyEngine", d.nightlyEngine),
            sponsorBlock = p.getBoolean("sponsorBlock", d.sponsorBlock),
            chapters = p.getBoolean("chapters", d.chapters),
            splitArtist = p.getBoolean("splitArtist", d.splitArtist),
            subsIntervalH = p.getInt("subsIntervalH", d.subsIntervalH),
            theme = p.enum("theme", d.theme),
            dynamicColor = p.getBoolean("dynamicColor", d.dynamicColor),
            folderMode = p.enum("folderMode", d.folderMode),
            folderName = p.getString("folderName", d.folderName) ?: d.folderName,
            bySite = p.getBoolean("bySite", d.bySite),
            nameTemplate = p.enum("nameTemplate", d.nameTemplate),
            treeUri = p.getString("treeUri", d.treeUri) ?: "",
            folderChosen = p.getBoolean("folderChosen", d.folderChosen),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value).let {
            it.copy(
                maxConcurrent = it.maxConcurrent.coerceIn(1, 5),
                folderName = it.folderName.replace(Regex("""[\\/:*?"<>|]"""), "").take(40),
            )
        }
        _state.value = s
        prefs.edit()
            .putInt("videoHeight", s.videoHeight)
            .putInt("mp3Kbps", s.mp3Kbps)
            .putString("openTab", s.openTab.name)
            .putInt("maxConcurrent", s.maxConcurrent)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putString("rateLimit", s.rateLimit)
            .putBoolean("embedSubs", s.embedSubs)
            .putBoolean("autoSubs", s.autoSubs)
            .putString("subLangs", s.subLangs)
            .putBoolean("useCookies", s.useCookies)
            .putBoolean("watchClipboard", s.watchClipboard)
            .putBoolean("nightlyEngine", s.nightlyEngine)
            .putBoolean("sponsorBlock", s.sponsorBlock)
            .putBoolean("chapters", s.chapters)
            .putBoolean("splitArtist", s.splitArtist)
            .putInt("subsIntervalH", s.subsIntervalH)
            .putString("theme", s.theme.name)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putString("folderMode", s.folderMode.name)
            .putString("folderName", s.folderName)
            .putBoolean("bySite", s.bySite)
            .putString("nameTemplate", s.nameTemplate.name)
            .putString("treeUri", s.treeUri)
            .putBoolean("folderChosen", s.folderChosen)
            .apply()
    }
}
