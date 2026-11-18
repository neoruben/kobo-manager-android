package com.neoruben.kobomanager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import android.graphics.BitmapFactory
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val ACTION_USB_PERMISSION = "com.neoruben.kobomanager.USB_PERMISSION"

// Paleta Oficial Rakuten Kobo + E-Ink Carta
data class KoboPalette(
    val isDark: Boolean,
    val bg: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val headerGradStart: Color,
    val headerGradEnd: Color,
    val koboRed: Color,
    val koboRedSoft: Color,
    val activeGreen: Color,
    val activeCardBg: Color,
    val activeCardBorder: Color,
    val factoryBlueBg: Color,
    val factoryBlueText: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val badgeBg: Color,
    val warningAmber: Color,
    val dangerRed: Color
)

fun getKoboPalette(isDark: Boolean): KoboPalette {
    return if (isDark) {
        KoboPalette(
            isDark = true,
            bg = Color(0xFF0E1014),
            cardBg = Color(0xFF181B22),
            cardBorder = Color(0xFF2A2F3B),
            headerGradStart = Color(0xFF26080D),
            headerGradEnd = Color(0xFF181B22),
            koboRed = Color(0xFFE60012),
            koboRedSoft = Color(0xFF3B1016),
            activeGreen = Color(0xFF10B981),
            activeCardBg = Color(0xFF11261E),
            activeCardBorder = Color(0xFF10B981),
            factoryBlueBg = Color(0xFF172A45),
            factoryBlueText = Color(0xFF7CC4FA),
            textPrimary = Color(0xFFF3F4F6),
            textSecondary = Color(0xFF9CA3AF),
            badgeBg = Color(0xFF252934),
            warningAmber = Color(0xFFF59E0B),
            dangerRed = Color(0xFFEF4444)
        )
    } else {
        KoboPalette(
            isDark = false,
            bg = Color(0xFFF5F3EE), // Papel E-Ink Carta cálido
            cardBg = Color(0xFFFFFFFF),
            cardBorder = Color(0xFFE2DFD7),
            headerGradStart = Color(0xFFFFF0F1),
            headerGradEnd = Color(0xFFFFFFFF),
            koboRed = Color(0xFFCC000F),
            koboRedSoft = Color(0xFFFCE8E9),
            activeGreen = Color(0xFF059669),
            activeCardBg = Color(0xFFECFDF5),
            activeCardBorder = Color(0xFF10B981),
            factoryBlueBg = Color(0xFFE0F2FE),
            factoryBlueText = Color(0xFF0369A1),
            textPrimary = Color(0xFF18181B),
            textSecondary = Color(0xFF52525B),
            badgeBg = Color(0xFFEDEAE2),
            warningAmber = Color(0xFFD97706),
            dangerRed = Color(0xFFDC2626)
        )
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val backend = KoboBackend(applicationContext)

        setContent {
            val sysDark = isSystemInDarkTheme()
            var isDark by remember { mutableStateOf(backend.loadDarkThemePreference(sysDark)) }
            val palette = getKoboPalette(isDark)

            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = palette.koboRed,
                    background = palette.bg,
                    surface = palette.cardBg
                )
            } else {
                lightColorScheme(
                    primary = palette.koboRed,
                    background = palette.bg,
                    surface = palette.cardBg
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = palette.bg
                ) {
                    KobodroidSingleScreen(
                        backend = backend,
                        palette = palette,
                        onToggleTheme = {
                            isDark = !isDark
                            backend.saveDarkThemePreference(isDark)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun KobodroidSingleScreen(
    backend: KoboBackend,
    palette: KoboPalette,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }

    var lang by remember { mutableStateOf(backend.loadSavedLanguage()) }
    var currentKobo by remember { mutableStateOf<KoboDeviceInfo?>(null) }
    var pendingPermissionDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var installedDict by remember { mutableStateOf<InstalledDictInfo?>(null) }
    var catalog by remember { mutableStateOf<Map<String, CatalogEntry>>(backend.loadCatalog()) }
    var statusText by remember { mutableStateOf(tr(lang, "ready_status")) }

    // Navegación entre Diccionarios (0) y Libros (1)
    var selectedTab by remember { mutableIntStateOf(0) }
    var koboBooks by remember { mutableStateOf<List<KoboBookItem>>(emptyList()) }
    var booksPage by remember { mutableIntStateOf(0) }
    val booksPerPage = 4

    // Paginación compacta (3 diccionarios por página para que NUNCA haya scroll vertical)
    var currentPage by remember { mutableIntStateOf(0) }
    val itemsPerPage = 3

    // Estado de transferencia / progreso modal
    var isTransferring by remember { mutableStateOf(false) }
    var progressTitle by remember { mutableStateOf("") }
    var progressItemName by remember { mutableStateOf("") }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var progressStatusText by remember { mutableStateOf("") }
    var progressIndeterminate by remember { mutableStateOf(false) }

    // Diálogos modales
    var alertTitle by remember { mutableStateOf<String?>(null) }
    var alertMessage by remember { mutableStateOf("") }

    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }

    var showFolderDialog by remember { mutableStateOf(false) }
    var folderFiles by remember { mutableStateOf<List<KoboDictFileItem>>(emptyList()) }

    fun refreshCatalogAndInstalled(kobo: KoboDeviceInfo?) {
        catalog = backend.loadCatalog()
        installedDict = if (kobo != null) backend.getInstalledPrimaryDict(kobo) else null
    }

    fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    suspend fun checkUsbState() {
        if (isTransferring) return
        withContext(Dispatchers.IO) {
            val candidate = backend.findCandidateUsbDevice(usbManager)
            if (candidate == null) {
                if (currentKobo != null || pendingPermissionDevice != null) {
                    backend.closeActiveConnection()
                    withContext(Dispatchers.Main) {
                        currentKobo = null
                        pendingPermissionDevice = null
                        installedDict = null
                        koboBooks = emptyList()
                        statusText = tr(lang, "waiting_usb")
                    }
                }
            } else {
                if (!usbManager.hasPermission(candidate)) {
                    val alreadyPrompted = pendingPermissionDevice?.deviceName == candidate.deviceName
                    withContext(Dispatchers.Main) {
                        pendingPermissionDevice = candidate
                        currentKobo = null
                        installedDict = null
                        koboBooks = emptyList()
                        statusText = tr(lang, "usb_permission_needed")
                        if (!alreadyPrompted) {
                            requestUsbPermission(candidate)
                        }
                    }
                } else {
                    if (currentKobo == null || currentKobo?.usbDevice?.deviceName != candidate.deviceName) {
                        val info = backend.connectOverUsb(usbManager, candidate)
                        if (info != null) {
                            val inst = backend.getInstalledPrimaryDict(info)
                            val cat = backend.loadCatalog()
                            withContext(Dispatchers.Main) {
                                pendingPermissionDevice = null
                                currentKobo = info
                                installedDict = inst
                                catalog = cat
                                isTransferring = true
                                progressTitle = tr(lang, "loading_books_title")
                                progressItemName = tr(lang, "scanning_directories")
                                progressIndeterminate = true
                                progressFraction = 0f
                                progressStatusText = "..."
                            }
                            val books = backend.listKoboBooks { current, total, name ->
                                scope.launch(Dispatchers.Main) {
                                    progressIndeterminate = false
                                    val frac = if (total > 0) current.toFloat() / total.toFloat() else 0f
                                    progressFraction = frac
                                    progressItemName = name
                                    progressStatusText = tr(
                                        lang,
                                        "scanning_books_progress",
                                        "current" to current.toString(),
                                        "total" to total.toString(),
                                        "pct" to (frac * 100).toInt().toString()
                                    )
                                }
                            }
                            withContext(Dispatchers.Main) {
                                isTransferring = false
                                koboBooks = books
                                statusText = tr(
                                    lang,
                                    "device_ready_status",
                                    "model" to info.model,
                                    "kobo_lang" to info.koboLangName
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Receptor de eventos USB en caliente (Attach / Detach / Permission)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED,
                    ACTION_USB_PERMISSION -> {
                        scope.launch { checkUsbState() }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    // Bucle de sondeo ligero cada 2.5s por si el Kobo pasa a modo conectado tras pulsar "Conectar" en su pantalla
    LaunchedEffect(lang) {
        while (isActive) {
            checkUsbState()
            delay(2500)
        }
    }

    // Selector de archivos .zip para añadir diccionarios
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val defaultSlot = currentKobo?.primarySlot ?: "dicthtml-es.zip"
        isTransferring = true
        progressTitle = tr(lang, "importing_title")
        progressItemName = "${uris.size} archivo(s)"
        progressIndeterminate = true
        progressStatusText = "..."

        scope.launch(Dispatchers.IO) {
            val addedNames = ArrayList<String>()
            val duplicates = ArrayList<Pair<String, String>>()
            val errors = ArrayList<String>()

            for (uri in uris) {
                try {
                    val entry = backend.importDictionaryUri(uri, defaultSlot)
                    if (entry.isDuplicate) {
                        duplicates.add(Pair(backend.resolveDisplayName(uri), entry.name))
                    } else {
                        addedNames.add("${entry.name} (🎯 ${entry.targetSlot})")
                    }
                } catch (e: Exception) {
                    errors.add("${backend.resolveDisplayName(uri)}: ${e.message}")
                }
            }

            val updatedCat = backend.loadCatalog()
            withContext(Dispatchers.Main) {
                isTransferring = false
                catalog = updatedCat
                if (addedNames.isNotEmpty()) {
                    var msg = tr(
                        lang,
                        "added_success_msg",
                        "count" to addedNames.size.toString(),
                        "list" to addedNames.joinToString("\n• ")
                    )
                    if (duplicates.isNotEmpty()) {
                        msg += "\n\n" + duplicates.joinToString("\n") { "⚠️ '${it.first}' -> '${it.second}'" }
                    }
                    alertTitle = tr(lang, "added_success_title")
                    alertMessage = msg
                    statusText = "${addedNames.size} OK."
                } else if (duplicates.isNotEmpty()) {
                    val firstDup = duplicates.first()
                    alertTitle = tr(lang, "dict_already_in_library_title")
                    alertMessage = tr(
                        lang,
                        "dict_already_in_library_msg",
                        "file" to firstDup.first,
                        "name" to firstDup.second
                    )
                } else if (errors.isNotEmpty()) {
                    alertTitle = "Error"
                    alertMessage = errors.joinToString("\n")
                }
            }
        }
    }

    val bookPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        if (currentKobo == null) {
            alertTitle = "Kobo"
            alertMessage = tr(lang, "not_connected_warning")
            return@rememberLauncherForActivityResult
        }

        isTransferring = true
        progressTitle = tr(lang, "upload_books_btn")
        progressItemName = ""
        progressIndeterminate = false
        progressFraction = 0f
        progressStatusText = "0%"

        scope.launch(Dispatchers.IO) {
            var uploadedCount = 0
            val errors = ArrayList<String>()

            for ((idx, uri) in uris.withIndex()) {
                val displayName = backend.resolveDisplayName(uri)
                withContext(Dispatchers.Main) {
                    progressItemName = tr(
                        lang,
                        "uploading_book_progress",
                        "idx" to (idx + 1).toString(),
                        "total" to uris.size.toString(),
                        "name" to displayName
                    )
                    progressFraction = 0f
                    progressStatusText = "0%"
                }

                try {
                    backend.uploadBookToKobo(uri, targetFolder = "Books") { copied, total ->
                        val frac = if (total > 0) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        scope.launch(Dispatchers.Main) {
                            progressFraction = frac
                            progressStatusText = "${(frac * 100).toInt()}% (${formatSizeMb(copied)} / ${formatSizeMb(total)})"
                        }
                    }
                    uploadedCount++
                } catch (e: Exception) {
                    errors.add("$displayName: ${e.message}")
                }
            }

            val updatedBooks = backend.listKoboBooks()
            withContext(Dispatchers.Main) {
                isTransferring = false
                koboBooks = updatedBooks
                if (uploadedCount > 0) {
                    var msg = tr(lang, "upload_books_success_msg", "count" to uploadedCount.toString())
                    if (errors.isNotEmpty()) {
                        msg += "\n\n⚠️ " + errors.joinToString("\n")
                    }
                    alertTitle = tr(lang, "upload_books_success_title")
                    alertMessage = msg
                    statusText = "$uploadedCount libro(s) OK."
                } else if (errors.isNotEmpty()) {
                    alertTitle = "Error"
                    alertMessage = errors.joinToString("\n")
                }
            }
        }
    }

    // Función para activar un diccionario o restaurar el oficial en el Kobo
    fun triggerActivateDictionary(dictKey: String, isFactory: Boolean, slotName: String) {
        val kobo = currentKobo
        if (kobo == null) {
            alertTitle = tr(lang, "app_title")
            alertMessage = tr(lang, "not_connected_warning")
            return
        }

        val currentActiveHash = installedDict?.sha256
        val officialInfo = backend.getOfficialInfo(slotName)
        val targetHash: String?
        val dictName: String

        if (isFactory) {
            targetHash = officialInfo.sha256
            dictName = tr(lang, "official_dict_name", "lang" to kobo.koboLangName)
        } else {
            val info = catalog[dictKey]
            targetHash = info?.sha256
            dictName = info?.name ?: dictKey
        }

        if (currentActiveHash != null && targetHash != null && currentActiveHash == targetHash) {
            alertTitle = tr(lang, "dict_already_active_title")
            alertMessage = tr(lang, "dict_already_active_msg", "name" to dictName)
            return
        }

        confirmTitle = if (isFactory) tr(lang, "confirm_restore_title") else tr(lang, "confirm_load_title")
        confirmMessage = if (isFactory) {
            tr(lang, "confirm_restore_msg", "slot" to slotName)
        } else {
            tr(lang, "confirm_load_msg", "name" to dictName, "model" to kobo.model, "slot" to slotName)
        }

        confirmAction = {
            isTransferring = true
            progressTitle = tr(lang, "uploading_title")
            progressItemName = dictName
            progressIndeterminate = false
            progressFraction = 0f
            progressStatusText = "..."

            scope.launch(Dispatchers.IO) {
                var err: String? = null
                var usedSlot = slotName
                try {
                    val cb: (Long, Long) -> Unit = { copied, total ->
                        val frac = if (total > 0) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        val pct = (frac * 100).toInt()
                        val mbCopied = String.format(Locale.US, "%.1f", copied / (1048576.0))
                        val mbTotal = String.format(Locale.US, "%.1f", total / (1048576.0))
                        progressFraction = frac
                        progressStatusText = tr(
                            lang,
                            "copying_status",
                            "slot" to slotName,
                            "pct" to pct.toString(),
                            "copied" to mbCopied,
                            "total" to mbTotal
                        )
                    }
                    usedSlot = if (isFactory) {
                        backend.restoreOfficial(kobo, cb)
                    } else {
                        backend.setAsPrimary(kobo, dictKey, protect = true, progressCb = cb)
                    }
                } catch (e: Exception) {
                    err = e.message ?: "Error desconocido"
                }

                val updatedInst = backend.getInstalledPrimaryDict(kobo)
                val updatedCat = backend.loadCatalog()

                withContext(Dispatchers.Main) {
                    isTransferring = false
                    installedDict = updatedInst
                    catalog = updatedCat
                    if (err != null) {
                        alertTitle = "Error"
                        alertMessage = err
                        statusText = "Error: $err"
                    } else {
                        statusText = "OK: $dictName ($usedSlot)"
                        alertTitle = if (isFactory) tr(lang, "restore_success_title") else tr(lang, "load_success_title")
                        alertMessage = if (isFactory) {
                            tr(lang, "restore_success_msg", "slot" to usedSlot)
                        } else {
                            tr(lang, "load_success_msg", "name" to dictName, "slot" to usedSlot)
                        }
                    }
                }
            }
        }
    }

    // Cálculo de qué diccionario está marcado como ACTIVO (garantizando que solo 1 esté activo)
    val currentActiveHash = installedDict?.sha256
    val isReadOnlyOnKobo = installedDict?.readonly ?: false
    val primarySlot = currentKobo?.primarySlot ?: "dicthtml-es.zip"
    val koboLangName = currentKobo?.koboLangName ?: "Español"

    val activeCatalogKey = remember(currentActiveHash, catalog) {
        catalog.entries.firstOrNull { (_, item) ->
            currentActiveHash != null && currentActiveHash == item.sha256
        }?.key
    }

    val officialInfo = remember(primarySlot, currentKobo, installedDict) {
        backend.getOfficialInfo(primarySlot)
    }
    val isOfficialActive = activeCatalogKey == null &&
        currentActiveHash != null &&
        currentActiveHash == officialInfo.sha256

    val catalogList = remember(catalog) { catalog.values.toList() }
    // Lista unificada de diccionarios: Oficial de fábrica + añadidos (3 por página dentro del mismo cuadro)
    val totalDictEntries = 1 + catalogList.size
    val totalPages = ((totalDictEntries + itemsPerPage - 1) / itemsPerPage).coerceAtLeast(1)
    if (currentPage >= totalPages) currentPage = (totalPages - 1).coerceAtLeast(0)
    val pageIndices = (0 until totalDictEntries).drop(currentPage * itemsPerPage).take(itemsPerPage)

    // =========================================================================
    // PANTALLA ÚNICA SIN SCROLL VERTICAL (100% ENCAJADA EN EL VIEWPORT)
    // =========================================================================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---------------------------------------------------------------------
        // 1. CABECERA SUPERIOR CON IDENTIDAD KOBO + IDIOMA + TEMA
        // ---------------------------------------------------------------------
        TopKoboHeaderBar(
            lang = lang,
            palette = palette,
            onLangSelected = { newLang ->
                lang = newLang
                backend.saveLanguagePreference(newLang)
                statusText = if (currentKobo != null) {
                    tr(
                        newLang,
                        "device_ready_status",
                        "model" to currentKobo!!.model,
                        "kobo_lang" to currentKobo!!.koboLangName
                    )
                } else {
                    tr(newLang, "ready_status")
                }
            },
            onToggleTheme = onToggleTheme
        )

        // ---------------------------------------------------------------------
        // 2. PANEL DE ESTADO USB OTG + ACCIONES RÁPIDAS (AÑADIR / CARPETA / EXPULSAR)
        // ---------------------------------------------------------------------
        KoboUsbStatusCard(
            lang = lang,
            palette = palette,
            selectedTab = selectedTab,
            currentKobo = currentKobo,
            pendingPermissionDevice = pendingPermissionDevice,
            onGrantPermission = {
                pendingPermissionDevice?.let { requestUsbPermission(it) }
            },
            onAddClicked = {
                if (selectedTab == 1) {
                    filePickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                } else {
                    bookPickerLauncher.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "application/x-cbz",
                            "application/x-cbr",
                            "application/x-mobipocket-ebook",
                            "text/plain",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }
            },
            onOpenFolderClicked = {
                if (currentKobo != null) {
                    scope.launch(Dispatchers.IO) {
                        if (selectedTab == 1) {
                            val list = backend.listKoboDictFolder()
                            withContext(Dispatchers.Main) {
                                folderFiles = list
                                showFolderDialog = true
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                isTransferring = true
                                progressTitle = tr(lang, "loading_books_title")
                                progressItemName = tr(lang, "scanning_directories")
                                progressIndeterminate = true
                                progressFraction = 0f
                                progressStatusText = "..."
                            }
                            val list = backend.listKoboBooks { current, total, name ->
                                scope.launch(Dispatchers.Main) {
                                    progressIndeterminate = false
                                    val frac = if (total > 0) current.toFloat() / total.toFloat() else 0f
                                    progressFraction = frac
                                    progressItemName = name
                                    progressStatusText = tr(
                                        lang,
                                        "scanning_books_progress",
                                        "current" to current.toString(),
                                        "total" to total.toString(),
                                        "pct" to (frac * 100).toInt().toString()
                                    )
                                }
                            }
                            withContext(Dispatchers.Main) {
                                isTransferring = false
                                koboBooks = list
                                statusText = "${list.size} libro(s) en Kobo"
                            }
                        }
                    }
                }
            },
            onEjectClicked = {
                val kobo = currentKobo ?: return@KoboUsbStatusCard
                confirmTitle = tr(lang, "eject_confirm_title")
                confirmMessage = tr(lang, "eject_confirm_msg", "model" to kobo.model)
                confirmAction = {
                    scope.launch(Dispatchers.IO) {
                        backend.safelyEject()
                        withContext(Dispatchers.Main) {
                            alertTitle = tr(lang, "eject_success_title")
                            alertMessage = tr(lang, "eject_success_msg", "model" to kobo.model)
                            currentKobo = null
                            installedDict = null
                            koboBooks = emptyList()
                            statusText = tr(lang, "waiting_usb")
                        }
                    }
                }
            }
        )

        // ---------------------------------------------------------------------
        // 2B. SELECTOR DE MODO (DICCIONARIOS / LIBROS)
        // ---------------------------------------------------------------------
        ModeTabBar(
            lang = lang,
            palette = palette,
            selectedTab = selectedTab,
            booksCount = if (currentKobo != null) koboBooks.size else null,
            onSelectTab = { selectedTab = it }
        )

        if (selectedTab == 0) {
            // -----------------------------------------------------------------
            // 3A. PANEL DE LIBROS EN EL KOBO (PRIMERA PESTAÑA)
            // -----------------------------------------------------------------
            KoboBooksLibraryCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                lang = lang,
                palette = palette,
                isConnected = currentKobo != null,
                books = koboBooks,
                currentPage = booksPage,
                itemsPerPage = booksPerPage,
                onPageChange = { booksPage = it },
                onDeleteBook = { book ->
                    val bookPath = if (book.folderPath.isEmpty()) {
                        "/${book.name}"
                    } else {
                        "/${book.folderPath.trim('/')}/${book.name}"
                    }
                    confirmTitle = tr(lang, "confirm_delete_book_title")
                    confirmMessage = "${tr(lang, "confirm_delete_book_msg", "name" to book.displayTitle)}\n\n📁 $bookPath"
                    confirmAction = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                backend.deleteBookFromKobo(book)
                                val updated = backend.listKoboBooks()
                                withContext(Dispatchers.Main) {
                                    koboBooks = updated
                                    statusText = tr(lang, "book_deleted_status", "name" to book.displayTitle)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    alertTitle = "Error"
                                    alertMessage = e.message ?: "Error al eliminar el libro"
                                }
                            }
                        }
                    }
                }
            )
        } else {
            // -----------------------------------------------------------------
            // 3B. CUADRO UNIFICADO DE DICCIONARIOS (OFICIAL + AÑADIDOS)
            // -----------------------------------------------------------------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = palette.cardBg),
                border = BorderStroke(1.dp, palette.cardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Cabecera unificada + Paginador compacto
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(palette.koboRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${tr(lang, "tab_dicts")} ($totalDictEntries)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary
                            )
                        }

                        if (totalPages > 1) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmallPageButton(
                                    text = "◀",
                                    enabled = currentPage > 0,
                                    palette = palette,
                                    onClick = { if (currentPage > 0) currentPage-- }
                                )
                                Text(
                                    text = "${currentPage + 1} / $totalPages",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textSecondary
                                )
                                SmallPageButton(
                                    text = "▶",
                                    enabled = currentPage < totalPages - 1,
                                    palette = palette,
                                    onClick = { if (currentPage < totalPages - 1) currentPage++ }
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (idx in pageIndices) {
                            if (idx == 0) {
                                DictionaryCompactCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    palette = palette,
                                    lang = lang,
                                    title = tr(lang, "official_dict_name", "lang" to koboLangName),
                                    subtitle = tr(lang, "official_dict_desc", "slot" to primarySlot),
                                    sizeBytes = officialInfo.size,
                                    isActive = isOfficialActive,
                                    isFactory = true,
                                    isReadOnly = if (isOfficialActive) isReadOnlyOnKobo else false,
                                    onActivate = {
                                        triggerActivateDictionary("__official_factory_kobo__", isFactory = true, slotName = primarySlot)
                                    },
                                    onDelete = null
                                )
                            } else {
                                val item = catalogList[idx - 1]
                                val isItemActive = (item.key == activeCatalogKey)
                                val targetSlot = item.targetSlot.ifEmpty { primarySlot }
                                val sub = tr(
                                    lang,
                                    "file_added_label",
                                    "file" to item.sourceFile,
                                    "date" to item.addedDate
                                )
                                DictionaryCompactCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    palette = palette,
                                    lang = lang,
                                    title = item.name,
                                    subtitle = sub,
                                    sizeBytes = item.size,
                                    isActive = isItemActive,
                                    isFactory = false,
                                    isReadOnly = if (isItemActive) isReadOnlyOnKobo else false,
                                    onActivate = {
                                        triggerActivateDictionary(item.key, isFactory = false, slotName = targetSlot)
                                    },
                                    onDelete = {
                                        confirmTitle = tr(lang, "confirm_delete_title")
                                        confirmMessage = tr(lang, "confirm_delete_msg", "name" to item.name)
                                        confirmAction = {
                                            backend.deleteFromCatalog(item.key)
                                            refreshCatalogAndInstalled(currentKobo)
                                            statusText = "Eliminado: ${item.name}"
                                        }
                                    }
                                )
                            }
                        }
                        repeat(itemsPerPage - pageIndices.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 5. BARRA INFERIOR DE ESTADO COMPACTA
        // ---------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.cardBg)
                .border(1.dp, palette.cardBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText,
                fontSize = 11.sp,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "FAT32 USB-OTG",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = palette.koboRed
            )
        }
    }

    // =========================================================================
    // DIÁLOGOS MODALES (PROGRESO, CONFIRMACIÓN, AVISO Y CARPETA .KOBO/DICT)
    // =========================================================================
    if (isTransferring) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = palette.cardBg),
                border = BorderStroke(1.dp, palette.koboRed)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = progressTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = progressItemName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.koboRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (progressIndeterminate) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = palette.koboRed,
                            trackColor = palette.badgeBg
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = palette.koboRed,
                            trackColor = palette.badgeBg
                        )
                    }
                    Text(
                        text = progressStatusText,
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = tr(lang, "keep_usb_warning"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.warningAmber,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (confirmAction != null) {
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            containerColor = palette.cardBg,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = { Text(confirmTitle, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text(confirmMessage, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        val action = confirmAction
                        confirmAction = null
                        action?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.koboRed)
                ) {
                    Text(tr(lang, "accept_btn"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text(tr(lang, "cancel_btn"), color = palette.textSecondary)
                }
            }
        )
    }

    if (alertTitle != null) {
        AlertDialog(
            onDismissRequest = { alertTitle = null },
            containerColor = palette.cardBg,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = { Text(alertTitle ?: "", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text(alertMessage, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { alertTitle = null },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.koboRed)
                ) {
                    Text(tr(lang, "accept_btn"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            containerColor = palette.cardBg,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = {
                Text(tr(lang, "folder_dialog_title"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (folderFiles.isEmpty()) {
                        Text("Vacío", fontSize = 13.sp, color = palette.textSecondary)
                    } else {
                        for (f in folderFiles.take(10)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = (if (f.isReadOnly) "🔒 " else "📄 ") + f.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = formatSizeMb(f.size),
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }
                            HorizontalDivider(color = palette.cardBorder)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showFolderDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.koboRed)
                ) {
                    Text(tr(lang, "close_btn"), color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun TopKoboHeaderBar(
    lang: String,
    palette: KoboPalette,
    onLangSelected: (String) -> Unit,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    var langExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(palette.headerGradStart, palette.headerGradEnd)
                )
            )
            .border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Marca Kobodroid con emblema rojo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(palette.koboRed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "K",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Kobodroid",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = palette.textPrimary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.koboRedSoft)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "v$APP_VERSION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.koboRed
                )
            }
        }

        // Acciones: Botón Ko-fi (permanente y visible) + Tema E-Ink + Selector de Idioma
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Botón Ko-fi de apoyo al proyecto
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF5E5B).copy(alpha = 0.16f))
                    .border(1.dp, Color(0xFFFF5E5B).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .clickable {
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://ko-fi.com/neoruben")
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "☕", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Ko-fi",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (palette.isDark) Color(0xFFFF7E70) else Color(0xFFD9383A)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.badgeBg)
                    .clickable { onToggleTheme() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (palette.isDark) "☀️" else "🌙",
                    fontSize = 14.sp
                )
            }

            Box {
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.badgeBg)
                        .clickable { langExpanded = true }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🌐 ${lang.uppercase()} ▾",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                }

                DropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false },
                    modifier = Modifier.background(palette.cardBg)
                ) {
                    for ((code, label) in SUPPORTED_LANGUAGES) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "$label (${code.uppercase()})",
                                    fontWeight = if (code == lang) FontWeight.Bold else FontWeight.Normal,
                                    color = if (code == lang) palette.koboRed else palette.textPrimary
                                )
                            },
                            onClick = {
                                langExpanded = false
                                onLangSelected(code)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KoboUsbStatusCard(
    lang: String,
    palette: KoboPalette,
    selectedTab: Int,
    currentKobo: KoboDeviceInfo?,
    pendingPermissionDevice: UsbDevice?,
    onGrantPermission: () -> Unit,
    onAddClicked: () -> Unit,
    onOpenFolderClicked: () -> Unit,
    onEjectClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        border = BorderStroke(
            width = 1.dp,
            color = if (currentKobo != null) palette.activeGreen else palette.cardBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Fila de estado de conexión del lector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (currentKobo != null) {
                        Text(
                            text = tr(
                                lang,
                                "connected",
                                "model" to currentKobo.model,
                                "drive" to currentKobo.drive,
                                "kobo_lang" to currentKobo.koboLangName
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.activeGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = tr(
                                lang,
                                "device_info",
                                "fw" to currentKobo.firmware,
                                "serial" to currentKobo.serial.take(8),
                                "slot" to currentKobo.primarySlot
                            ),
                            fontSize = 11.sp,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (pendingPermissionDevice != null) {
                        Text(
                            text = tr(lang, "usb_permission_needed"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.warningAmber
                        )
                        Text(
                            text = pendingPermissionDevice.productName ?: "Rakuten Kobo USB",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    } else {
                        Text(
                            text = tr(lang, "waiting_usb"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.warningAmber
                        )
                        Text(
                            text = tr(lang, "connect_prompt"),
                            fontSize = 11.sp,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (pendingPermissionDevice != null && currentKobo == null) {
                    Button(
                        onClick = onGrantPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.koboRed),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr(lang, "grant_usb_btn"), fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            // Fila de botones de acción rápida en una sola línea
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val primaryEnabled = if (selectedTab == 1) true else currentKobo != null
                Button(
                    onClick = onAddClicked,
                    enabled = primaryEnabled,
                    modifier = Modifier
                        .weight(1.25f)
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.koboRed,
                        disabledContainerColor = palette.badgeBg
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (selectedTab == 1) tr(lang, "add_dicts_btn") else tr(lang, "upload_books_btn"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (primaryEnabled) Color.White else palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onOpenFolderClicked,
                    enabled = currentKobo != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, palette.cardBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (selectedTab == 1) tr(lang, "open_folder_btn") else tr(lang, "refresh_books_btn"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (currentKobo != null) palette.textPrimary else palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onEjectClicked,
                    enabled = currentKobo != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.dangerRed,
                        disabledContainerColor = palette.badgeBg
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = tr(lang, "eject_btn"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (currentKobo != null) Color.White else palette.textSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun DictionaryCompactCard(
    modifier: Modifier = Modifier,
    palette: KoboPalette,
    lang: String,
    title: String,
    subtitle: String,
    sizeBytes: Long,
    isActive: Boolean,
    isFactory: Boolean,
    isReadOnly: Boolean,
    onActivate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) palette.activeCardBg else palette.cardBg,
        label = "cardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) palette.activeCardBorder else palette.cardBorder,
        label = "cardBorder"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(if (isActive) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Fila 1: Título completo ocupando todo el ancho (sin etiquetas que corten el nombre)
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Fila 2: Subtítulo + Badge de protección Wi-Fi si está activo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isActive) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isReadOnly) tr(lang, "protected_badge") else tr(lang, "unprotected_badge"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isReadOnly) palette.activeGreen else palette.warningAmber
                    )
                }
            }

            // Fila 3: Tamaño en MB + Botones (Eliminar / Cargar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr(lang, "size_label", "size" to formatSizeMb(sizeBytes)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isFactory && onDelete != null) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(palette.koboRedSoft)
                                .clickable { onDelete() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🗑️", fontSize = 13.sp)
                        }
                    }

                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(palette.activeGreen)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tr(lang, "currently_active_btn"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Button(
                            onClick = onActivate,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(7.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.koboRed),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (isFactory) tr(lang, "restore_official_btn") else tr(lang, "load_btn"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1
        )
    }
}

@Composable
private fun SmallPageButton(
    text: String,
    enabled: Boolean,
    palette: KoboPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) palette.koboRed else palette.badgeBg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.White else palette.textSecondary
        )
    }
}

@Composable
private fun ModeTabBar(
    lang: String,
    palette: KoboPalette,
    selectedTab: Int,
    booksCount: Int?,
    onSelectTab: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val booksSelected = selectedTab == 0
        val dictsSelected = selectedTab == 1
        val booksLabel = if (booksCount != null) {
            "${tr(lang, "tab_books")} ($booksCount)"
        } else {
            tr(lang, "tab_books")
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (booksSelected) palette.koboRed else Color.Transparent)
                .clickable { onSelectTab(0) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = booksLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (booksSelected) Color.White else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (dictsSelected) palette.koboRed else Color.Transparent)
                .clickable { onSelectTab(1) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tr(lang, "tab_dicts"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (dictsSelected) Color.White else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun KoboBooksLibraryCard(
    modifier: Modifier = Modifier,
    lang: String,
    palette: KoboPalette,
    isConnected: Boolean,
    books: List<KoboBookItem>,
    currentPage: Int,
    itemsPerPage: Int,
    onPageChange: (Int) -> Unit,
    onDeleteBook: (KoboBookItem) -> Unit
) {
    val totalPages = ((books.size + itemsPerPage - 1) / itemsPerPage).coerceAtLeast(1)
    var searchQuery by remember { mutableStateOf("") }
    val filteredBooks = remember(books, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) books
        else books.filter {
            val path = if (it.folderPath.isEmpty()) "/${it.name}" else "/${it.folderPath}/${it.name}"
            it.displayTitle.lowercase().contains(q) ||
                it.author.lowercase().contains(q) ||
                path.lowercase().contains(q)
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(palette.koboRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${tr(lang, "kobo_books_title")} (${filteredBooks.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                }
            }

            if (isConnected && books.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.bg)
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "🔍", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Buscar libro o autor...",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = palette.textPrimary,
                                fontSize = 12.sp
                            ),
                            cursorBrush = SolidColor(palette.koboRed),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "✕",
                            fontSize = 12.sp,
                            color = palette.textSecondary,
                            modifier = Modifier
                                .clickable { searchQuery = "" }
                                .padding(start = 6.dp)
                        )
                    }
                }
            }

            if (!isConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "🔌", fontSize = 28.sp)
                        Text(
                            text = tr(lang, "connect_books_hint"),
                            fontSize = 13.sp,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "📚", fontSize = 28.sp)
                        Text(
                            text = tr(lang, "empty_books_hint"),
                            fontSize = 13.sp,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredBooks,
                        key = { "${it.folderPath}/${it.name}" }
                    ) { book ->
                        BookCompactCard(
                            modifier = Modifier.fillMaxWidth(),
                            palette = palette,
                            book = book,
                            onDelete = { onDeleteBook(book) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCompactCard(
    modifier: Modifier = Modifier,
    palette: KoboPalette,
    book: KoboBookItem,
    onDelete: () -> Unit
) {
    val coverBitmap = remember(book.coverPath) {
        book.coverPath?.let { path ->
            try {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = palette.bg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Portada del libro o miniatura limpia de respaldo
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.badgeBg)
                    .border(1.dp, palette.cardBorder, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap,
                        contentDescription = book.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = if (book.format == "KOBO") "📖" else book.format,
                        fontSize = if (book.format == "KOBO") 18.sp else 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textSecondary
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = book.displayTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val metaInfo = if (book.author.isNotEmpty()) {
                    "${book.author} • ${formatSizeMb(book.size)}"
                } else {
                    formatSizeMb(book.size)
                }
                Text(
                    text = metaInfo,
                    fontSize = 11.sp,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val fullEreaderPath = if (book.folderPath.isEmpty()) {
                    "/${book.name}"
                } else {
                    "/${book.folderPath.trim('/')}/${book.name}"
                }
                Text(
                    text = "📁 $fullEreaderPath",
                    fontSize = 10.sp,
                    color = palette.textSecondary.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, palette.cardBorder),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(text = "🗑️", fontSize = 13.sp)
            }
        }
    }
}
