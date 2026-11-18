# Kobodroid — All-in-One Kobo eReader Manager for Android 📱📚

<p align="center">
  <a href="https://github.com/neoruben/kobo-manager-android/releases/latest"><img src="https://img.shields.io/badge/⬇_Download_APK-Android-D5001C?style=for-the-badge" alt="Download APK for Android" height="38"></a>
  &nbsp;
  <a href="https://ko-fi.com/neoruben" target="_blank">
    <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi" height="38">
  </a>
</p>

**Kobodroid** is an open-source native **Android** application designed to completely manage your **Rakuten Kobo eReader** directly from your phone via a **USB-C / USB OTG** cable, with no computer or Wi-Fi required.

Transfer books (EPUB, KEPUB, PDF, CBZ), browse your library with book covers and metadata, manage custom dictionaries with genuine FAT32 read-only Wi-Fi protection, and safely eject the device via hardware SCSI commands.

---

## ✨ Key Features

### 📚 1. Comprehensive Book Management
- **Visual Explorer with Book Covers:** Extracts covers and metadata directly from `EPUB` / `KEPUB` containers, with automatic fallback to the internal `KoboReader.sqlite` database.
- **Real-Time Search:** Instantly filter books by title, author, or full storage path on the eReader.
- **Live Progress Bar:** Shows real-time book count, percentage, and current book title being read both upon USB connection and manual library refresh.
- **Direct USB Transfer:** Send individual or batch books directly to the `/Books` folder with live progress tracking.
- **Safe Deletion:** Remove books from the eReader with a confirmation dialog showing the exact file path (`/folder/book.epub`).

### 📖 2. Advanced Dictionary Management
- **Genuine Wi-Fi Sync Protection (`ATTR_READ_ONLY`):** Modifies the read-only attribute bit (`0x01`) directly in the FAT32 directory table for `dicthtml-xx.zip`, preventing Kobo firmware from overwriting custom dictionaries during Wi-Fi sync.
- **Automatic Language Detection:** Reads `.kobo/Kobo/Kobo eReader.conf` (`CurrentLocale`) to automatically map your active dictionary slot (`dicthtml-es.zip`, `dicthtml.zip`, `dicthtml-fr.zip`, etc.).
- **Factory Backup & SHA-256 Verification:** Automatically backs up the genuine official dictionary on first connection, allowing one-tap factory restore at any time.
- **Custom Dictionary Library:** Store, organize, and switch between community-created dictionaries.

### 🔌 3. Direct USB Mass Storage Access (Native FAT32 & SCSI BOT)
- **Full Block-Level Control (SCSI Bulk-Only Transport):** Claims the USB interface directly (`claimInterface(..., force = true)`), detaching Android's kernel storage driver and preventing Android from polluting your Kobo with unwanted system folders (`LOST.DIR`, `Android/`, etc.).
- **Plug & Play Auto-Detection:** Instantly recognizes any Kobo eReader using Rakuten Kobo's official Vendor ID (`0x2237`).
- **Safe Hardware Ejection:** Sends SCSI `SYNCHRONIZE CACHE (0x35)` and `START STOP UNIT (0x1B)` commands so the reader safely unmounts and returns to its reading screen without data corruption.

### 🎨 4. "All-in-One Screen" Design with Kobo Identity
- **Zero Vertical Scroll:** The entire interface fits on a single screen for maximum speed and comfort.
- **E-Ink Themes:** Official **Rakuten Kobo Crimson Red** (`#D5001C`) combined with **E-Ink Carta** (Light paper mode) and **E-Ink Obsidian** (Dark mode).
- **Permanent Ko-fi Support Button:** Directly accessible in the top header bar at all times.
- **Multi-Language:** Interface available in English, Español, Català, Français, Deutsch, Italiano, and Português.

---

## 📱 Installation

1. Download **`Kobodroid-v1.3.3.apk`** from the **[Releases](https://github.com/neoruben/kobo-manager-android/releases/latest)** page.
2. Install it on your Android device (Android 8.0 / API 26 or higher).
3. Connect your Kobo eReader using a USB-C or USB OTG cable, tap **"Connect"** on the Kobo screen, and manage your library.

---

## ☕ Support and Donations

If **Kobodroid** is helpful for managing your books and custom dictionaries on your Kobo, you can support its development:

<p align="center">
  <a href="https://ko-fi.com/neoruben" target="_blank">
    <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi" height="38">
  </a>
</p>

---

## 📄 License & Authorship

Developed with ❤️ by [neoruben](https://github.com/neoruben).

<br>

---
---

<br>

## 🇪🇸 Versión en español

# Kobodroid — Gestor integral para Rakuten Kobo eReader en Android 📱📚

<p align="center">
  <a href="https://github.com/neoruben/kobo-manager-android/releases/latest"><img src="https://img.shields.io/badge/⬇_Descargar_APK-Android-D5001C?style=for-the-badge" alt="Descargar APK para Android" height="38"></a>
  &nbsp;
  <a href="https://ko-fi.com/neoruben" target="_blank">
    <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi" height="38">
  </a>
</p>

**Kobodroid** es una aplicación nativa para **Android** de código abierto diseñada para gestionar de forma completa tu lector **Rakuten Kobo eReader** directamente desde el móvil mediante un cable **USB-C / USB OTG**, sin necesidad de encender el ordenador ni usar Wi-Fi.

Permite transferir libros (EPUB, KEPUB, PDF, CBZ), explorar tu biblioteca con portadas y metadatos, gestionar diccionarios personalizados con protección real contra sobrescritura Wi-Fi y expulsar el dispositivo de forma 100% segura por hardware.

---

### ✨ Características Principales

#### 📚 1. Gestión Integral de Libros
- **Explorador con portadas:** Extrae portadas y metadatos directamente del contenedor de los archivos `EPUB` / `KEPUB` o desde la base de datos interna `KoboReader.sqlite` como respaldo.
- **Buscador en tiempo real:** Filtra instantáneamente por título, autor o ruta completa dentro del eReader.
- **Barra de progreso en vivo:** Muestra en tiempo real el libro actual, porcentaje y conteo (`X de Total`) tanto al conectar el cable como al refrescar la biblioteca.
- **Transferencia directa USB:** Sube libros individuales o por lotes a la carpeta `/Books` con seguimiento de progreso.
- **Eliminación segura:** Borra libros del lector con diálogo de confirmación que muestra la ruta exacta del archivo (`/carpeta/archivo.epub`).
- **Paginación optimizada:** Navegación ágil sin sobrecargar la memoria.

#### 📖 2. Gestión Avanzada de Diccionarios
- **Protección real contra sincronización Wi-Fi (`ATTR_READ_ONLY`):**
  Modifica a nivel binario el bit de solo lectura (`0x01`) en la tabla de directorios FAT32 del lector para el archivo `dicthtml-xx.zip`, evitando que el firmware de Kobo lo sobreescriba al sincronizar por Wi-Fi.
- **Detección automática de idioma:** Lee `.kobo/Kobo/Kobo eReader.conf` (`CurrentLocale`) para asignar automáticamente el slot activo (`dicthtml-es.zip`, `dicthtml.zip`, etc.).
- **Respaldo de fábrica e integridad SHA-256:** Copia de seguridad automática del diccionario original de fábrica en la primera conexión para restaurarlo en cualquier momento con un solo toque.
- **Biblioteca personalizada:** Almacena y alterna entre múltiples diccionarios creados por la comunidad.

#### 🔌 3. Acceso USB Mass Storage Directo (FAT32 Nativo)
- **Control total a nivel de bloque (SCSI BOT):**
  Desconecta el driver del kernel de Android reclamando la interfaz USB (`claimInterface(..., force = true)`), impidiendo que Android cree carpetas no deseadas (`LOST.DIR`, `Android/`, etc.) en el almacenamiento del lector.
- **Detección automática Plug & Play:** Reconoce al instante cualquier Kobo mediante el Vendor ID oficial de Rakuten Kobo (`0x2237`).
- **Expulsión segura por hardware:** Envía comandos SCSI `SYNCHRONIZE CACHE (0x35)` y `START STOP UNIT (0x1B)` para que el lector vuelva a su pantalla de lectura de forma segura y sin corrupción de datos.

#### 🎨 4. Diseño "Todo en Una Pantalla" con Identidad Kobo
- **Cero scroll vertical:** Toda la interfaz encaja en una sola pantalla para máxima usabilidad.
- **Temas E-Ink:** Paleta oficial **Rakuten Kobo Crimson Red** (`#D5001C`) combinada con modo claro **E-Ink Carta** y modo oscuro **E-Ink Obsidian**.
- **Acceso Ko-fi permanente:** Botón de apoyo directo siempre accesible en la cabecera fija.
- **Multi-idioma:** Interfaz disponible en Español, English, Català, Français, Deutsch, Italiano y Português.

---

### 📱 Instalación

1. Descarga **`Kobodroid-v1.3.3.apk`** desde la sección de **[Releases](https://github.com/neoruben/kobo-manager-android/releases/latest)**.
2. Instálalo en tu móvil Android (Android 8.0 / API 26 o superior).
3. Conecta tu lector Kobo por cable USB-C o adaptador OTG, pulsa **"Conectar"** en la pantalla del Kobo y gestiona tu biblioteca.

---

### ☕ Apoyo y Donaciones

Si **Kobodroid** te resulta útil para gestionar tus lecturas y diccionarios en tu Kobo, puedes apoyar su desarrollo:

<p align="center">
  <a href="https://ko-fi.com/neoruben" target="_blank">
    <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi" height="38">
  </a>
</p>

---

### 📄 Licencia y Autoría

Desarrollado con ❤️ por [neoruben](https://github.com/neoruben).
