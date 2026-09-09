package com.mqclient.app.fonts

import java.awt.GraphicsEnvironment
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs

/** A system-installed font family plus the disk file that backs it, if resolvable. */
data class FontEntry(val family: String, val file: File?)

/**
 * System font enumeration and family → file resolution.
 *
 * Two layers, consumed by two future consumers:
 * 1. [entries] enumerates **every** family the OS knows about — the settings
 *    dropdown's data source. The authoritative set comes from AWT
 *    (`GraphicsEnvironment.availableFontFamilyNames`), which includes every
 *    face of a .ttc collection (e.g. both "Microsoft YaHei" and
 *    "Microsoft YaHei UI" from `msyh.ttc`), not just the first one.
 * 2. [fileForFamily] resolves a family name to an actual font file on disk so
 *    the render layer can later load it via Compose Desktop's `Font(file)`.
 *
 * Rendering is deliberately NOT wired here: the settings pane persists a
 * selection now, and the typography layer will consume it after the message
 * bus rework lands.
 *
 * Known limitation: Compose Desktop's file-based font loading cannot pick a
 * face *inside* a .ttc collection, so a collection file is only mapped when
 * its first face's family (read from the sfnt name table) matches. Chinese
 * system fonts are first faces in practice (SimSun, Microsoft YaHei,
 * PingFang SC), so this covers the common cases; later faces (NSimSun,
 * YaHei UI, …) are enumerated but map to no file and show as disabled.
 *
 * Everything is lazy + best-effort: headless AWT, missing directories or
 * unparseable files degrade to empty/null instead of throwing.
 */
object SystemFonts {

    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "tte")

    /* ---------- public API ---------- */

    /** Every selectable entry: the full AWT family list, each with its backing file. */
    fun entries(): List<FontEntry> = allFamilies.map { FontEntry(it, fileFor(it)) }

    /** Backing file for [family] (null when unresolvable — not installed on disk or ttc face > 0). */
    fun fileForFamily(family: String): File? = fileFor(family)

    /* ---------- caches ---------- */

    private val allFamilies: List<String> by lazy {
        val awt = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        }.getOrElse { emptyArray() }
        // AWT 是权威全集; 再把目录里扫到的 face0 家族并进来, 兜住 AWT 偶尔漏掉的磁盘字体。
        val parsed = scanned.map { it.second.family }
        (awt.asSequence().map { it.trim() }.filter { it.isNotEmpty() } + parsed.asSequence())
            .filterNot { it.lowercase(Locale.ROOT) in AWT_LOGICAL_FAMILIES }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }

    /** AWT 的合成家族(Dialog/SansSerif 等)没有实体字体文件, 对用户无意义, 不进列表。 */
    private val AWT_LOGICAL_FAMILIES =
        setOf("dialog", "dialoginput", "sansserif", "serif", "monospaced", "default")

    /** 每个文件扫一次 face0, 解析出家族名 + 字重信息。 */
    private val scanned: List<Pair<File, ParsedFace>> by lazy {
        fontFiles.mapNotNull { file ->
            parseFace0(file)?.let { file to it }
        }
    }

    /** 每个家族只留“最像 Regular 的一个文件”(字重最接近 400、非斜体), 供渲染层加载。 */
    private val bestByKey: Map<String, Pair<File, ParsedFace>> by lazy {
        val map = HashMap<String, Pair<File, ParsedFace>>()
        scanned.forEach { (file, face) ->
            val key = normKey(face.family)
            val prev = map[key]
            if (prev == null || preferRegular(face, prev.second)) map[key] = file to face
        }
        map
    }

    private fun fileFor(family: String): File? = bestByKey[normKey(family)]?.first

    /* ---------- directory discovery ---------- */

    private val fontDirs: List<File> by lazy {
        val home = File(System.getProperty("user.home"))
        val osDirs: List<File> = when {
            isWindows -> {
                val winFonts = File(
                    System.getenv("SystemRoot") ?: System.getenv("WINDIR") ?: "C:\\Windows",
                    "Fonts",
                )
                // 用户级安装的字体落在 LOCALAPPDATA(资源管理器里"安装为个人使用")。
                val userFonts = System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft\\Windows\\Fonts") }
                listOfNotNull(winFonts, userFonts)
            }
            isMac -> listOf(
                File("/System/Library/Fonts"),
                File("/System/Library/Fonts/Supplemental"),
                File("/Library/Fonts"),
                File(home, "Library/Fonts"),
            )
            else -> listOf(
                File("/usr/share/fonts"),
                File("/usr/local/share/fonts"),
                File(home, ".fonts"),
                File(home, ".local/share/fonts"),
            )
        }
        // AWT 会把运行时自带字体(<java.home>/lib/fonts, 如 JBR 的 Fira Code / Droid)
        // 注册为可用家族, 一并扫描, 否则它们会以"无文件"置灰的形式出现。
        osDirs + File(System.getProperty("java.home"), "lib/fonts")
    }

    private val fontFiles: List<File> by lazy {
        fontDirs.flatMap { dir ->
            if (!dir.isDirectory) return@flatMap emptyList()
            dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in FONT_EXTENSIONS }
                .toList()
        }
    }

    /* ---------- sfnt parsing (self-contained; no dependency on AWT font loading) ---------- */

    private data class TableDir(val tag: Long, val offset: Long, val length: Long)

    private data class ParsedFace(val family: String, val weightClass: Int, val italic: Boolean)

    private const val TAG_NAME = 0x6E616D65L // "name"
    private const val TAG_OS2 = 0x4F532F32L // "OS/2"

    /** Parses the first face of [file], returning its family name + weight info (null when unreadable). */
    private fun parseFace0(file: File): ParsedFace? = try {
        RandomAccessFile(file, "r").use { raf ->
            val base = face0Base(raf)
            if (base < 0) return null
            val dirs = readTableDirectory(raf, base) ?: return null
            val nameDir = dirs.firstOrNull { it.tag == TAG_NAME } ?: return null
            val os2Dir = dirs.firstOrNull { it.tag == TAG_OS2 }
            val len = nameDir.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            // Table offsets in a .ttc are face-relative per the collection spec;
            // some tools write them file-absolute. Try face-relative first, then
            // absolute, and keep whichever yields a plausible name table.
            val candidates = buildList {
                if (base != 0L) add(true to (base + nameDir.offset))
                add(false to nameDir.offset)
            }.distinctBy { it.second }

            for ((relative, tableAbs) in candidates) {
                val family = parseFamilyName(raf, tableAbs, len) ?: continue
                val os2 = os2Dir?.let { parseOs2(raf, base, it, relative) }
                return ParsedFace(family, os2?.weightClass ?: 1000, os2?.italic ?: false)
            }
            null
        }
    } catch (_: Throwable) {
        null
    }

    /** Absolute file offset of the first face: 0 for a plain sfnt, collection offset for a .ttc. */
    private fun face0Base(raf: RandomAccessFile): Long {
        val head = ByteArray(12)
        if (!readAt(raf, 0, head)) return -1
        if (u32(head, 0) != 0x74746366L /* "ttcf" */) return 0
        val numFonts = u32(head, 8) ?: return -1
        if (numFonts <= 0) return -1
        val offsets = ByteArray(4)
        return if (readAt(raf, 12, offsets)) u32(offsets, 0) ?: -1 else -1
    }

    private fun readTableDirectory(raf: RandomAccessFile, base: Long): List<TableDir>? {
        val header = ByteArray(12)
        if (!readAt(raf, base, header)) return null
        val numTables = u16(header, 4) ?: return null
        if (numTables == 0 || numTables > 256) return null
        val raw = ByteArray(numTables * 16)
        if (!readAt(raf, base + 12, raw)) return null
        return List(numTables) { i ->
            val p = i * 16
            TableDir(
                tag = u32(raw, p) ?: 0,
                offset = u32(raw, p + 8) ?: 0,
                length = u32(raw, p + 12) ?: 0,
            )
        }
    }

    /**
     * Reads a `name` table at [tableAbs] and picks the best nameID=1 record.
     * Platform preference: Windows-English (3,1,0x409) → Windows (3,1) →
     * Windows Unicode (3,0) → Unicode (0) → Mac Roman (1,0,0).
     */
    private fun parseFamilyName(raf: RandomAccessFile, tableAbs: Long, tableLen: Int): String? {
        if (tableLen < 6) return null
        val head = ByteArray(6)
        if (!readAt(raf, tableAbs, head)) return null
        val format = u16(head, 0) ?: return null
        if (format > 1) return null
        val count = u16(head, 2) ?: return null
        if (count == 0 || count > 1024) return null
        val strBase = u16(head, 4) ?: return null
        if (strBase > tableLen) return null

        val raw = ByteArray(count * 12)
        if (!readAt(raf, tableAbs + 6, raw)) return null

        data class Rec(
            val platform: Int,
            val encoding: Int,
            val lang: Int,
            val nameId: Int,
            val length: Int,
            val offset: Int,
        )
        var best: Rec? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until count) {
            val p = i * 12
            val rec = Rec(
                platform = u16(raw, p) ?: -1,
                encoding = u16(raw, p + 2) ?: -1,
                lang = u16(raw, p + 4) ?: -1,
                nameId = u16(raw, p + 6) ?: -1,
                length = u16(raw, p + 8) ?: 0,
                offset = u16(raw, p + 10) ?: 0,
            )
            if (rec.nameId != 1 || rec.length !in 1..255 || rec.offset !in 0..tableLen) continue
            val score = when {
                rec.platform == 3 && rec.encoding == 1 && rec.lang == 0x0409 -> 0
                rec.platform == 3 && rec.encoding == 1 -> 1
                rec.platform == 3 && rec.encoding == 0 -> 2
                rec.platform == 0 -> 3
                rec.platform == 1 && rec.encoding == 0 && rec.lang == 0 -> 4
                else -> 5
            }
            if (score < bestScore) {
                bestScore = score
                best = rec
            }
        }
        val rec = best ?: return null
        val bytes = ByteArray(rec.length)
        if (!readAt(raf, tableAbs + strBase + rec.offset, bytes)) return null
        val rawName = when (rec.platform) {
            1 -> String(bytes, StandardCharsets.ISO_8859_1) // Mac Roman; ASCII names decode fine
            else -> String(bytes, StandardCharsets.UTF_16BE)
        }
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 128 || name.any { it.isISOControl() }) return null
        return name
    }

    /** Reads OS/2 weight/italic flags. Uses the same face-relative-vs-absolute convention as the name table. */
    private fun parseOs2(raf: RandomAccessFile, base: Long, dir: TableDir, relative: Boolean): Os2Info? {
        if (dir.length < 64) return null
        val tableAbs = if (relative) base + dir.offset else dir.offset
        val raw = ByteArray(64)
        if (!readAt(raf, tableAbs, raw)) return null
        val weightClass = u16(raw, 4) ?: return null
        val fsSelection = u16(raw, 62) ?: return null
        return Os2Info(weightClass, italic = fsSelection and 1 != 0)
    }

    private data class Os2Info(val weightClass: Int, val italic: Boolean)

    /** 字重越接近 Regular(400) 越好, 斜体往后排 —— 保证给渲染层的是该家族的常规字面。 */
    private fun preferRegular(candidate: ParsedFace, current: ParsedFace): Boolean {
        val dC = abs(candidate.weightClass - 400)
        val dN = abs(current.weightClass - 400)
        if (dC != dN) return dC < dN
        return !candidate.italic && current.italic
    }

    /* ---------- tiny binary helpers ---------- */

    /** Case/punctuation/space-insensitive key, so AWT names match sfnt name-table names. CJK is kept verbatim. */
    private fun normKey(name: String): String = buildString(name.length) {
        for (ch in name.lowercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) append(ch)
        }
    }

    private fun readAt(raf: RandomAccessFile, pos: Long, into: ByteArray): Boolean = try {
        raf.seek(pos)
        raf.readFully(into)
        true
    } catch (_: Exception) {
        false
    }

    private fun u16(b: ByteArray, i: Int): Int? =
        if (i < 0 || i + 2 > b.size) null
        else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, i: Int): Long? =
        if (i < 0 || i + 4 > b.size) null
        else ((b[i].toLong() and 0xFF) shl 24) or
            ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or
            (b[i + 3].toLong() and 0xFF)
}
