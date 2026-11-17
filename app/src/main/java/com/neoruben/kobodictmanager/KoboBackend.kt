package com.neoruben.kobodictmanager

import android.content.Context
import android.database.Cursor
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

data class KoboDeviceInfo(
    val drive: String,
    val model: String,
    val firmware: String,
    val serial: String,
    val koboLocale: String,
    val primarySlot: String,
    val koboLangName: String,
    val usbDevice: UsbDevice? = null
)

data class InstalledDictInfo(
    val slot: String,
    val size: Long,
    val sha256: String,
    val readonly: Boolean
)

data class OfficialDictInfo(
    val size: Long,
    val sha256: String?,
    val backupFile: File?,
    val isFactory: Boolean = true
)

data class CatalogEntry(
    val key: String,
    val name: String,
    val filename: String,
    val targetSlot: String,
    val size: Long,
    val sha256: String,
    val addedDate: String,
    val sourceFile: String,
    val isDuplicate: Boolean = false
)

data class KoboDictFileItem(
    val name: String,
    val size: Long,
    val isReadOnly: Boolean
)

data class KoboBookItem(
    val name: String,
    val folderPath: String,
    val size: Long,
    val format: String
)

class KoboBackend(private val context: Context) {

    val dataDir: File = File(context.filesDir, "KoboDictManager").apply { mkdirs() }
    val libraryDir: File = File(dataDir, "library").apply { mkdirs() }
    val backupsDir: File = File(dataDir, "backups").apply { mkdirs() }
    private val catalogFile: File = File(libraryDir, "catalog.json")
    private val settingsFile: File = File(dataDir, "settings.json")

    private var activeBlockDevice: BlockDevice? = null
    private var activeVolume: Fat32Volume? = null
    private var activeUsbDeviceName: String? = null

    companion object {
        const val KOBO_VENDOR_ID = 0x2237 // Rakuten Kobo Inc.

        fun getFileSha256(file: File?): String? {
            if (file == null || !file.exists() || !file.isFile) return null
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val r = fis.read(buf)
                        if (r <= 0) break
                        md.update(buf, 0, r)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }

        fun isMassStorageDevice(device: UsbDevice): Boolean {
            if (device.vendorId == KOBO_VENDOR_ID) return true
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    return true
                }
            }
            return false
        }
    }

    // -------------------------------------------------------------------------
    // Idioma y Preferencias Visuales (Tema Kobo Claro / Oscuro)
    // -------------------------------------------------------------------------
    fun loadSavedLanguage(): String {
        if (settingsFile.exists()) {
            try {
                val obj = JSONObject(settingsFile.readText(StandardCharsets.UTF_8))
                val lang = obj.optString("language", "")
                if (SUPPORTED_LANGUAGES.containsKey(lang)) return lang
            } catch (_: Exception) {
            }
        }
        return detectSystemLanguage()
    }

    fun saveLanguagePreference(langCode: String) {
        try {
            val obj = if (settingsFile.exists()) {
                try {
                    JSONObject(settingsFile.readText(StandardCharsets.UTF_8))
                } catch (_: Exception) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            obj.put("language", langCode)
            settingsFile.writeText(obj.toString(2), StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun loadDarkThemePreference(defaultDark: Boolean): Boolean {
        if (settingsFile.exists()) {
            try {
                val obj = JSONObject(settingsFile.readText(StandardCharsets.UTF_8))
                if (obj.has("darkTheme")) return obj.getBoolean("darkTheme")
            } catch (_: Exception) {
            }
        }
        return defaultDark
    }

    fun saveDarkThemePreference(isDark: Boolean) {
        try {
            val obj = if (settingsFile.exists()) {
                try {
                    JSONObject(settingsFile.readText(StandardCharsets.UTF_8))
                } catch (_: Exception) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            obj.put("darkTheme", isDark)
            settingsFile.writeText(obj.toString(2), StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------------------
    // Descubrimiento USB y Montaje FAT32 (Opción B)
    // -------------------------------------------------------------------------
    fun findCandidateUsbDevice(usbManager: UsbManager): UsbDevice? {
        val list = usbManager.deviceList.values
        // Priorizar Vendor ID oficial de Kobo (0x2237)
        list.firstOrNull { it.vendorId == KOBO_VENDOR_ID }?.let { return it }
        return list.firstOrNull { isMassStorageDevice(it) }
    }

    @Synchronized
    fun connectOverUsb(usbManager: UsbManager, usbDevice: UsbDevice): KoboDeviceInfo? {
        return try {
            if (activeVolume != null && activeUsbDeviceName == usbDevice.deviceName) {
                return inspectMountedVolume(activeVolume!!, usbDevice)
            }
            closeActiveConnection()

            val blockDev = UsbBotScsiBlockDevice(usbManager, usbDevice)
            blockDev.open()
            val volume = Fat32Volume(blockDev)

            val info = inspectMountedVolume(volume, usbDevice)
            if (info != null) {
                activeBlockDevice = blockDev
                activeVolume = volume
                activeUsbDeviceName = usbDevice.deviceName
            } else {
                blockDev.close()
            }
            info
        } catch (e: Exception) {
            e.printStackTrace()
            closeActiveConnection()
            null
        }
    }

    /**
     * Permite montar cualquier BlockDevice (por ejemplo en tests o simulaciones)
     * con exactamente la misma lógica de detección de Kobo, backup oficial y auto-importación.
     */
    @Synchronized
    fun connectWithBlockDevice(blockDevice: BlockDevice, driveLabel: String = "USB-OTG"): KoboDeviceInfo? {
        closeActiveConnection()
        return try {
            val volume = Fat32Volume(blockDevice)
            val info = inspectMountedVolume(volume, null, driveLabel)
            if (info != null) {
                activeBlockDevice = blockDevice
                activeVolume = volume
                activeUsbDeviceName = driveLabel
            }
            info
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun inspectMountedVolume(
        volume: Fat32Volume,
        usbDevice: UsbDevice?,
        driveLabel: String = "USB-OTG"
    ): KoboDeviceInfo? {
        val versionEntry = volume.findEntry(".kobo/version") ?: return null
        val versionText = String(volume.readFileBytes(versionEntry), StandardCharsets.UTF_8).trim()

        var modelName = "Kobo eReader"
        var firmware = "4.46+"
        var serial = "N/A"

        if (versionText.isNotEmpty()) {
            val parts = versionText.split(",")
            if (parts.isNotEmpty()) serial = parts[0].trim()
            if (parts.size >= 3) firmware = parts[2].trim()
            if (parts.size >= 6) {
                val hwId = parts[5].replace("-", "").trimStart('0')
                KOBO_MODELS[hwId]?.let { modelName = it }
            }
            for ((prefix, name) in KOBO_MODELS) {
                if (serial.startsWith(prefix)) {
                    modelName = name
                    break
                }
            }
        }

        val (koboLocale, primarySlot, koboLangName) = detectKoboLocale(volume)

        val koboInfo = KoboDeviceInfo(
            drive = driveLabel,
            model = modelName,
            firmware = firmware,
            serial = serial,
            koboLocale = koboLocale,
            primarySlot = primarySlot,
            koboLangName = koboLangName,
            usbDevice = usbDevice
        )

        // Respaldo automático del diccionario oficial de fábrica si es genuino
        ensureFactoryBackup(volume, primarySlot)

        // Auto-importar diccionario personalizado existente en el lector si no está en el catálogo
        autoImportExistingDeviceDict(volume, koboInfo)

        return koboInfo
    }

    private fun detectKoboLocale(volume: Fat32Volume): Triple<String, String, String> {
        var localeCode = "es"
        val confEntry = volume.findEntry(".kobo/Kobo/Kobo eReader.conf")
        if (confEntry != null) {
            try {
                val confText = String(volume.readFileBytes(confEntry), StandardCharsets.UTF_8)
                for (rawLine in confText.lineSequence()) {
                    val line = rawLine.trim()
                    if (line.startsWith("CurrentLocale=")) {
                        val value = line.substringAfter("CurrentLocale=").trim()
                        if (value.isNotEmpty()) {
                            localeCode = value.split("_", "-")[0].lowercase()
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val primarySlot = if (localeCode == "en") "dicthtml.zip" else "dicthtml-$localeCode.zip"
        val langName = LANGUAGE_SLOT_INFO[localeCode]?.name ?: localeCode.uppercase()
        return Triple(localeCode, primarySlot, langName)
    }

    private fun ensureFactoryBackup(volume: Fat32Volume, primarySlot: String) {
        val officialBackup = File(backupsDir, "official_$primarySlot")
        val expectedSpec = OFFICIAL_FACTORY_SPECS[primarySlot]
        val expectedHash = expectedSpec?.sha256

        if (officialBackup.exists() && expectedHash != null) {
            if (getFileSha256(officialBackup) != expectedHash) {
                officialBackup.delete()
            }
        }

        if (!officialBackup.exists()) {
            val currentPrimary = volume.findEntry(".kobo/dict/$primarySlot")
            if (currentPrimary != null && currentPrimary.size > 0) {
                val isRo = currentPrimary.isReadOnly
                val currHash = volume.computeFileSha256(currentPrimary)
                var isGenuine = !isRo
                if (expectedHash != null && currHash != expectedHash) {
                    isGenuine = false
                }
                if (isGenuine) {
                    try {
                        volume.copyFileToLocal(currentPrimary, officialBackup)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun autoImportExistingDeviceDict(volume: Fat32Volume, koboInfo: KoboDeviceInfo): CatalogEntry? {
        val targetEntry = volume.findEntry(".kobo/dict/${koboInfo.primarySlot}") ?: return null
        if (targetEntry.size < 100 * 1024) return null // Ignorar archivos menores de 100 KB en tests/corruptos

        val hash = volume.computeFileSha256(targetEntry)
        val officialInfo = getOfficialInfo(koboInfo.primarySlot)
        if (hash == officialInfo.sha256) return null

        val catalog = loadCatalog().toMutableMap()
        if (catalog.values.any { it.sha256 == hash }) return null

        val label = KNOWN_HASHES[hash] ?: "Diccionario Personalizado (${koboInfo.koboLangName})"
        val ts = System.currentTimeMillis()
        val internalKey = "dict_imported_$ts.zip"
        val destZip = File(libraryDir, internalKey)

        return try {
            volume.copyFileToLocal(targetEntry, destZip)
            val entry = CatalogEntry(
                key = internalKey,
                name = label,
                filename = internalKey,
                targetSlot = koboInfo.primarySlot,
                size = destZip.length(),
                sha256 = hash,
                addedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()),
                sourceFile = "Kobo eReader"
            )
            catalog[internalKey] = entry
            saveCatalog(catalog)
            entry
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun getInstalledPrimaryDict(koboInfo: KoboDeviceInfo?): InstalledDictInfo? {
        val vol = activeVolume ?: return null
        val slot = koboInfo?.primarySlot ?: return null
        val entry = vol.findEntry(".kobo/dict/$slot") ?: return null
        val sha = vol.computeFileSha256(entry)
        return InstalledDictInfo(
            slot = slot,
            size = entry.size,
            sha256 = sha,
            readonly = entry.isReadOnly
        )
    }

    @Synchronized
    fun listKoboDictFolder(): List<KoboDictFileItem> {
        val vol = activeVolume ?: return emptyList()
        val dictDir = vol.findEntry(".kobo/dict") ?: return emptyList()
        if (!dictDir.isDirectory) return emptyList()
        return vol.listDirectory(dictDir.firstCluster)
            .filter { !it.isDirectory }
            .map { KoboDictFileItem(it.name, it.size, it.isReadOnly) }
            .sortedBy { it.name.lowercase() }
    }

    fun getOfficialInfo(primarySlot: String = "dicthtml-es.zip"): OfficialDictInfo {
        val backupFile = File(backupsDir, "official_$primarySlot")
        val spec = OFFICIAL_FACTORY_SPECS[primarySlot]
        val expectedHash = spec?.sha256
        val expectedSize = spec?.size ?: 13976932L

        if (backupFile.exists()) {
            val sz = backupFile.length()
            val h = getFileSha256(backupFile)
            if (expectedHash != null && h != expectedHash) {
                return OfficialDictInfo(expectedSize, expectedHash, null, true)
            }
            return OfficialDictInfo(sz, h, backupFile, true)
        }
        return OfficialDictInfo(expectedSize, expectedHash, null, true)
    }

    // -------------------------------------------------------------------------
    // Gestión del Catálogo Local de Diccionarios
    // -------------------------------------------------------------------------
    fun loadCatalog(): LinkedHashMap<String, CatalogEntry> {
        val map = LinkedHashMap<String, CatalogEntry>()
        if (!catalogFile.exists()) return map
        try {
            val root = JSONObject(catalogFile.readText(StandardCharsets.UTF_8))
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val item = root.getJSONObject(k)
                map[k] = CatalogEntry(
                    key = k,
                    name = item.optString("name", k),
                    filename = item.optString("filename", k),
                    targetSlot = item.optString("target_slot", "dicthtml-es.zip"),
                    size = item.optLong("size", 0L),
                    sha256 = item.optString("sha256", ""),
                    addedDate = item.optString("added_date", ""),
                    sourceFile = item.optString("source_file", "")
                )
            }
        } catch (_: Exception) {
        }
        return map
    }

    fun saveCatalog(catalog: Map<String, CatalogEntry>) {
        try {
            val root = JSONObject()
            for ((k, v) in catalog) {
                val obj = JSONObject()
                obj.put("name", v.name)
                obj.put("filename", v.filename)
                obj.put("target_slot", v.targetSlot)
                obj.put("size", v.size)
                obj.put("sha256", v.sha256)
                obj.put("added_date", v.addedDate)
                obj.put("source_file", v.sourceFile)
                root.put(k, obj)
            }
            catalogFile.writeText(root.toString(2), StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun resolveDisplayName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = it.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/') ?: "diccionario.zip"
        }
        return result!!
    }

    fun importDictionaryUri(uri: Uri, defaultSlot: String = "dicthtml-es.zip"): CatalogEntry {
        val displayName = resolveDisplayName(uri)
        val tempInput = File(context.cacheDir, "import_${System.currentTimeMillis()}_$displayName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempInput).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("No se pudo leer el archivo seleccionado.")
            return importDictionaryFile(tempInput, displayName, defaultSlot)
        } finally {
            tempInput.delete()
        }
    }

    fun importDictionaryFile(
        sourceFile: File,
        originalFileName: String = sourceFile.name,
        defaultSlot: String = "dicthtml-es.zip"
    ): CatalogEntry {
        val catalog = loadCatalog()
        val cleanName = originalFileName.substringBeforeLast('.')
        val cleanTitle = cleanName.replace("-kobo", "", ignoreCase = true)
            .replace("_", " ")
            .trim()
            .ifEmpty { "Diccionario Kobo" }

        val targetSlot = detectTargetSlotForFilename(originalFileName, defaultSlot)
        val ts = System.currentTimeMillis()
        val internalKey = "dict_$ts.zip"
        val destZip = File(libraryDir, internalKey)

        // Si el ZIP contiene dentro un dicthtml*.zip empaquetado, extraer el interno
        var extractedInner = false
        try {
            ZipInputStream(FileInputStream(sourceFile)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val entryName = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory &&
                        entryName.lowercase().startsWith("dicthtml") &&
                        entryName.lowercase().endsWith(".zip")
                    ) {
                        FileOutputStream(destZip).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractedInner = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (!extractedInner) {
            sourceFile.copyTo(destZip, overwrite = true)
        }

        val sha = getFileSha256(destZip)
            ?: throw IllegalStateException("No se pudo calcular el hash SHA-256 del diccionario.")
        val size = destZip.length()

        // Evitar duplicados por SHA-256
        for (existing in catalog.values) {
            if (existing.sha256 == sha) {
                destZip.delete()
                return existing.copy(isDuplicate = true)
            }
        }

        val finalTitle = KNOWN_HASHES[sha] ?: cleanTitle
        val newEntry = CatalogEntry(
            key = internalKey,
            name = finalTitle,
            filename = internalKey,
            targetSlot = targetSlot,
            size = size,
            sha256 = sha,
            addedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()),
            sourceFile = originalFileName,
            isDuplicate = false
        )

        catalog[internalKey] = newEntry
        saveCatalog(catalog)
        return newEntry
    }

    fun deleteFromCatalog(dictKey: String): Boolean {
        val catalog = loadCatalog()
        val removed = catalog.remove(dictKey) ?: return false
        File(libraryDir, removed.filename).delete()
        saveCatalog(catalog)
        return true
    }

    // -------------------------------------------------------------------------
    // Carga y Restauración en el Kobo con Protección FAT32 Read-Only
    // -------------------------------------------------------------------------
    @Synchronized
    fun setAsPrimary(
        koboInfo: KoboDeviceInfo,
        dictKey: String,
        protect: Boolean = true,
        progressCb: ((Long, Long) -> Unit)? = null
    ): String {
        val vol = activeVolume ?: throw IllegalStateException("Lector Kobo no conectado.")
        val catalog = loadCatalog()
        val dictInfo = catalog[dictKey] ?: throw IllegalArgumentException("Diccionario no encontrado.")
        val srcFile = File(libraryDir, dictInfo.filename)
        if (!srcFile.exists()) throw IllegalStateException("Archivo local del diccionario no encontrado.")

        val targetSlot = dictInfo.targetSlot.ifEmpty { koboInfo.primarySlot }
        val existingEntry = vol.findEntry(".kobo/dict/$targetSlot")

        if (existingEntry != null && existingEntry.size > 0) {
            val currentHash = vol.computeFileSha256(existingEntry)
            val officialInfo = getOfficialInfo(targetSlot)
            val isInCatalog = catalog.values.any { it.sha256 == currentHash }

            // Preservar en la biblioteca si había un diccionario personalizado previo no registrado
            if (currentHash != officialInfo.sha256 && !isInCatalog) {
                val label = KNOWN_HASHES[currentHash] ?: "Diccionario previo ($targetSlot)"
                val ts = System.currentTimeMillis()
                val autoKey = "dict_preserved_$ts.zip"
                val autoDest = File(libraryDir, autoKey)
                try {
                    vol.copyFileToLocal(existingEntry, autoDest)
                    catalog[autoKey] = CatalogEntry(
                        key = autoKey,
                        name = label,
                        filename = autoKey,
                        targetSlot = targetSlot,
                        size = autoDest.length(),
                        sha256 = currentHash,
                        addedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()),
                        sourceFile = "Preservado del eReader"
                    )
                    saveCatalog(catalog)
                } catch (_: Exception) {
                }
            }

            // Quitar Read-Only antes de reemplazar y guardar backup con fecha
            vol.setFileReadOnly(existingEntry, false)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupFile = File(backupsDir, "backup_${targetSlot}_$stamp.zip")
            try {
                vol.copyFileToLocal(existingEntry, backupFile)
            } catch (_: Exception) {
            }
        }

        // Escribir nuevo diccionario en .kobo/dict/<targetSlot> y activar bit FAT32 Read-Only
        vol.writeFileFromLocal(
            dirPath = ".kobo/dict",
            fileName = targetSlot,
            srcFile = srcFile,
            readOnly = protect,
            progressCb = progressCb
        )

        return targetSlot
    }

    @Synchronized
    fun restoreOfficial(
        koboInfo: KoboDeviceInfo,
        progressCb: ((Long, Long) -> Unit)? = null
    ): String {
        val vol = activeVolume ?: throw IllegalStateException("Lector Kobo no conectado.")
        val targetSlot = koboInfo.primarySlot
        val officialInfo = getOfficialInfo(targetSlot)
        val backupFile = officialInfo.backupFile
            ?: throw IllegalStateException("Copia de seguridad oficial ($targetSlot) aún no disponible en este dispositivo.")

        if (!backupFile.exists()) {
            throw IllegalStateException("Archivo de respaldo oficial no encontrado.")
        }

        val existingEntry = vol.findEntry(".kobo/dict/$targetSlot")
        if (existingEntry != null) {
            vol.setFileReadOnly(existingEntry, false)
        }

        // Escribir el diccionario oficial sin Read-Only (tal como viene de fábrica)
        vol.writeFileFromLocal(
            dirPath = ".kobo/dict",
            fileName = targetSlot,
            srcFile = backupFile,
            readOnly = false,
            progressCb = progressCb
        )

        return targetSlot
    }

    // -------------------------------------------------------------------------
    // Gestión de Libros en Kobo (EPUB, KEPUB, PDF, CBZ, CBR, MOBI, TXT...)
    // -------------------------------------------------------------------------
    private fun detectBookFormat(fileName: String): String? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".kepub.epub") -> "KEPUB"
            lower.endsWith(".epub") -> "EPUB"
            lower.endsWith(".pdf") -> "PDF"
            lower.endsWith(".cbz") -> "CBZ"
            lower.endsWith(".cbr") -> "CBR"
            lower.endsWith(".mobi") -> "MOBI"
            lower.endsWith(".txt") -> "TXT"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "HTML"
            lower.endsWith(".rtf") -> "RTF"
            else -> null
        }
    }

    @Synchronized
    fun listKoboBooks(): List<KoboBookItem> {
        val vol = activeVolume ?: return emptyList()
        val result = ArrayList<KoboBookItem>()

        fun scanDir(dirCluster: Long, dirPath: String, depth: Int) {
            if (depth > 2) return
            val entries = try {
                vol.listDirectory(dirCluster)
            } catch (_: Exception) {
                return
            }
            for (entry in entries) {
                val lowerName = entry.name.lowercase()
                if (entry.isDirectory) {
                    if (entry.name.startsWith(".") ||
                        lowerName == "system volume information" ||
                        lowerName == "\$recycle.bin"
                    ) {
                        continue
                    }
                    val subPath = if (dirPath.isEmpty()) entry.name else "$dirPath/${entry.name}"
                    scanDir(entry.firstCluster, subPath, depth + 1)
                } else {
                    val fmt = detectBookFormat(entry.name)
                    if (fmt != null) {
                        result.add(
                            KoboBookItem(
                                name = entry.name,
                                folderPath = dirPath,
                                size = entry.size,
                                format = fmt
                            )
                        )
                    }
                }
            }
        }

        scanDir(vol.rootCluster, "", 0)
        return result.sortedBy { it.name.lowercase() }
    }

    @Synchronized
    fun uploadBookToKobo(
        uri: Uri,
        targetFolder: String = "Books",
        progressCb: ((Long, Long) -> Unit)? = null
    ): KoboBookItem {
        val vol = activeVolume ?: throw IllegalStateException("Lector Kobo no conectado.")
        val rawName = resolveDisplayName(uri).trim()
        val cleanName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "libro.epub" }
        val format = detectBookFormat(cleanName)
            ?: throw IllegalArgumentException("Formato no soportado para Kobo: $cleanName")

        val tempFile = File(context.cacheDir, "kobo_book_${System.currentTimeMillis()}_$cleanName")
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("No se pudo leer el archivo seleccionado.")
            inputStream.use { ins ->
                FileOutputStream(tempFile).use { outs ->
                    ins.copyTo(outs, 65536)
                }
            }

            if (targetFolder.isNotEmpty()) {
                vol.createDirectory("", targetFolder)
            }

            val written = vol.writeFileFromLocal(
                dirPath = targetFolder,
                fileName = cleanName,
                srcFile = tempFile,
                readOnly = false,
                progressCb = progressCb
            )

            return KoboBookItem(
                name = written.name,
                folderPath = targetFolder,
                size = written.size,
                format = format
            )
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    @Synchronized
    fun deleteBookFromKobo(book: KoboBookItem): Boolean {
        val vol = activeVolume ?: throw IllegalStateException("Lector Kobo no conectado.")
        return vol.deleteFile(book.folderPath, book.name)
    }

    @Synchronized
    fun safelyEject(): Boolean {
        val dev = activeBlockDevice
        activeVolume = null
        activeBlockDevice = null
        activeUsbDeviceName = null
        return dev?.eject() ?: true
    }

    @Synchronized
    fun closeActiveConnection() {
        try {
            activeBlockDevice?.close()
        } catch (_: Exception) {
        }
        activeBlockDevice = null
        activeVolume = null
        activeUsbDeviceName = null
    }
}
