# Kobo DictManager for Android — Guía Técnica y Arquitectura

Este documento resume cómo está construido el proyecto **Kobo DictManager for Android** (`com.neoruben.kobodictmanager`) para poder retomarlo, ampliarlo o depurarlo rápidamente en futuras sesiones.

---

## 1. Visión General y Stack
- **Repositorio:** `https://github.com/neoruben/kobo-dict-manager-android` (privado)
- **Paquete / Application ID:** `com.neoruben.kobodictmanager`
- **Lenguaje y UI:** Kotlin + Jetpack Compose + Material 3 (`compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`)
- **Acceso USB:** Acceso directo **USB Mass Storage (BOT / SCSI)** sobre particiones **FAT32** sin depender de `SAF` ni root (`UsbManager` nativo de Android + driver propio).
- **Compilación local (Windows):**
  - JDK 25 (Android Studio JBR) y Gradle 9.6.0 (`gradlew.bat assembleDebug --offline`).
  - `gradle.properties` incluye `android.overridePathCheck=true` por la tilde en la ruta de usuario.
  - Al generar APKs para GitHub/Releases, seguir la convención **`v.x.x.x.apk`** (además de `Kobo-DictManager-v.x.x.x.apk`).

---

## 2. Mapa de Archivos Principales

```text
app/src/main/
├── AndroidManifest.xml                  # Permiso/filtro USB_DEVICE_ATTACHED, orientación portrait
├── res/xml/usb_device_filter.xml        # Filtro Vendor ID Kobo (8759 / 0x2237) + Mass Storage BOT (8/6/80)
└── java/com/neoruben/kobodictmanager/
    ├── KoboUsbFat32Driver.kt            # Capa física USB BOT/SCSI + sistema de archivos FAT32
    ├── KoboBackend.kt                   # Lógica de negocio Kobo: diccionarios, libros, portadas, SQLite y backups
    ├── MainActivity.kt                  # UI Jetpack Compose (pantalla única, temas Kobo, pestañas Libros/Diccionarios)
    └── Translations.kt                  # Constantes (APP_VERSION, KOBO_MODELS, KNOWN_HASHES) + traducciones
```

---

## 3. Cómo Funciona Cada Capa

### A. Driver USB + FAT32 Directo (`KoboUsbFat32Driver.kt`)
1. **`BlockDevice` & `UsbBotScsiBlockDevice`:**
   - Reclama la interfaz USB Mass Storage (`Class 8`, `Subclass 6/0`, `Protocol 0x50` Bulk-Only Transport) con `claimInterface(..., force = true)` para desconectar el driver del kernel Android y evitar que Android cree carpetas basura (`Android/`, `LOST.DIR`, `Music/`, etc.) en el Kobo.
   - Implementa comandos SCSI sobre CBW/CSW: `TEST UNIT READY (0x00)`, `REQUEST SENSE (0x03)`, `READ CAPACITY (0x25)`, `READ(10) (0x28)`, `WRITE(10) (0x2A)`, `SYNCHRONIZE CACHE (0x35)` y `START STOP UNIT (0x1B)` para expulsión segura por hardware.
2. **`Fat32Volume`:**
   - Detecta el inicio de la partición FAT32 soportando Superfloppy, MBR y GPT Protective MBR.
   - Soporta nombres largos VFAT (**LFN**), lectura/escritura por cadenas de clústeres agrupadas (hasta 64 KB por transferencia USB), actualización de `FAT1` y `FAT2`, creación de directorios y borrado de archivos.
   - **Protección Wi-Fi Real:** `setFileReadOnly()` y `writeFileFromLocal(..., readOnly = true)` modifican directamente el bit **`ATTR_READ_ONLY (0x01)`** en la entrada de directorio FAT32 de 32 bytes.

### B. Backend Kobo (`KoboBackend.kt`)
1. **Detección del lector:**
   - Lee `.kobo/version` para identificar modelo (mapeo `KOBO_MODELS`, ej. `391` / `N365` → `Kobo Clara BW`), firmware y número de serie.
   - Lee `.kobo/Kobo/Kobo eReader.conf` (`CurrentLocale=...`) para determinar el slot de idioma activo (`dicthtml.zip` para inglés, `dicthtml-es.zip` para español, etc.).
2. **Gestión de Diccionarios:**
   - Al conectar, hace backup automático del diccionario oficial de fábrica en `filesDir/KoboDictManager/backups/official_<slot>` si es genuino (verificado por `OFFICIAL_FACTORY_SPECS` SHA-256 y ausencia de `Read-Only`).
   - Auto-importa diccionarios personalizados existentes en `.kobo/dict/<slot>` a la biblioteca local (`filesDir/KoboDictManager/library/` + `catalog.json`) evitando duplicados por SHA-256.
   - Al cargar un diccionario personalizado, preserva el anterior si procede, escribe el nuevo en `.kobo/dict/<slot>` y activa `ATTR_READ_ONLY`.
   - Al restaurar el oficial, escribe el backup sin `Read-Only`.
3. **Gestión de Libros (`listKoboBooks`, `uploadBookToKobo`, `deleteBookFromKobo`):**
   - Escanea carpetas visibles del usuario (excluyendo `.`, `System Volume Information`, `$RECYCLE.BIN`) hasta profundidad 4 y también `.kobo/kepub` (libros de Kobo Store/Cloud).
   - Formatos soportados: `KEPUB`, `EPUB`, `PDF`, `CBZ`, `CBR`, `MOBI`, `TXT`, `HTML`, `RTF`.
   - Para `EPUB` / `KEPUB` / `KOBO`, extrae título, autor y miniatura de portada desde `container.xml` + `.opf` guardándolos en caché (`kepub_cache.json` y `covers/`).
   - Si algún libro en `.kobo/kepub` no expone metadatos (p. ej. DRM), usa `KoboReader.sqlite` (`SELECT ContentID, Title, Attribution FROM content WHERE ContentType = '6'`) como respaldo.
   - `uploadBookToKobo` sube archivos a `/Books` en la partición FAT32.

### C. Interfaz de Usuario (`MainActivity.kt`)
- **Paleta Kobo (`KoboPalette`):** Rojo carmesí oficial (`#CC000F` / `#E60012`) con modo claro **E-Ink Carta** (`#F5F3EE`) y modo oscuro (`#0E1014`).
- **Estructura sin scroll global:**
  1. Cabecera con logo `K`, versión (`v1.3.2`), selector de tema (`☀️`/`🌙`) y selector de idioma (`🌐 ES ▾`).
  2. Tarjeta de estado USB OTG con botones en línea: `➕ Cargar Libros` / `➕ Añadir`, `🔄 Actualizar` / `📁 .kobo/dict`, `⏏️ Expulsar`.
  3. Barra de pestañas: **`📚 Libros (N)`** y **`📖 Diccionarios`**.
  4. **Pestaña Libros:** Buscador por título, autor o ruta (`🔍`), y lista vertical (`LazyColumn`) con portada, título, `Autor • Tamaño`, ruta completa dentro del eReader (`📁 /carpeta/archivo`) en letra pequeña (`10.sp`) y botón de borrado (`🗑️`) con confirmación que incluye la ruta exacta.
  5. **Pestaña Diccionarios:** Lista unificada (oficial de fábrica + biblioteca local) paginada de 3 en 3 (`◀ 1/N ▶`), sin etiquetas superiores redundantes para que el título se lea completo.
  6. Barra de estado inferior + diálogos modales de progreso, confirmación, alertas y visor de `.kobo/dict`.

### D. Idiomas (`Translations.kt`)
- Traducciones centralizadas en `TRANSLATIONS` y helper `tr(lang, key, args...)`.
- `SUPPORTED_LANGUAGES` define los idiomas disponibles en el selector.
- `detectSystemLanguage()` detecta `Locale.getDefault().language`. *(Nota pendiente: si se amplían idiomas, cambiar el fallback de `"es"` a `"en"` y mover los pocos literales sueltos de `MainActivity.kt` como `"Buscar libro o autor..."` a `Translations.kt`).*

---

## 4. Historial de Versiones Relevantes
- **`v1.2.0` (`fe416c8`):** Versión inicial USB Mass Storage + FAT32 directo, protección Read-Only y gestión de diccionarios en pantalla única.
- **`v1.3.0` (`afcd130`..`43a65df`):** Renombrado de paquete a `com.neoruben.kobodictmanager`, pestaña de Libros con subida/borrado, portadas EPUB, buscador y fallback SQLite.
- **`v1.3.1` (`e7d8dc2`):** Eliminadas etiquetas superiores redundantes (`FÁBRICA` / `ACTIVO EN KOBO`) en tarjetas de diccionarios para evitar cortes en el título.
- **`v1.3.2` (`b84b658`):** Añadida ruta completa dentro del eReader (`📁 /carpeta/archivo`) en cada tarjeta de libro, en el buscador y en el diálogo de confirmación de borrado.
