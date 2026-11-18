# Kobodroid 📱📚
### Gestor integral para Rakuten Kobo eReader en Android (USB OTG Directo)

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=flat&logo=android&logoColor=white)](https://github.com/neoruben/kobo-manager-android)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Apoyar%20el%20proyecto-ff5e5b?style=flat&logo=kofi&logoColor=white)](https://ko-fi.com/neoruben)
[![GitHub release](https://img.shields.io/github/v/release/neoruben/kobo-manager-android?color=D5001C&logo=github)](https://github.com/neoruben/kobo-manager-android/releases)

**Kobodroid** es una aplicación nativa para **Android** diseñada para gestionar de forma completa tu lector **Rakuten Kobo eReader** directamente desde el móvil mediante un cable **USB-C / USB OTG**, sin necesidad de encender el ordenador ni usar Wi-Fi.

Permite transferir libros (EPUB, KEPUB, PDF, CBZ), explorar tu biblioteca con portadas y metadatos, gestionar diccionarios personalizados con protección real contra sobrescritura Wi-Fi y expulsar el dispositivo de forma 100% segura por hardware.

---

## ✨ Características Principales

### 📚 1. Gestión Integral de Libros
- **Explorador con portadas:** Extrae portadas y metadatos directamente del contenedor de los archivos `EPUB` / `KEPUB` o desde la base de datos interna `KoboReader.sqlite` como respaldo.
- **Buscador en tiempo real:** Filtra instantáneamente por título, autor o ruta completa dentro del eReader.
- **Transferencia directa USB:** Sube libros individuales o por lotes a la carpeta `/Books` con barra de progreso en vivo y cálculo de velocidad.
- **Eliminación segura:** Borra libros del lector con diálogo de confirmación que muestra la ruta exacta del archivo (`/carpeta/archivo.epub`).
- **Paginación optimizada:** Navegación ágil sin sobrecargar la memoria.

### 📖 2. Gestión Avanzada de Diccionarios
- **Protección real contra sincronización Wi-Fi (`ATTR_READ_ONLY`):**
  Modifica a nivel binario el bit de solo lectura (`0x01`) en la tabla de directorios FAT32 del lector para el archivo `dicthtml-xx.zip`, evitando que el firmware de Kobo lo sobreescriba al sincronizar por Wi-Fi.
- **Detección automática de idioma:** Lee `.kobo/Kobo/Kobo eReader.conf` (`CurrentLocale`) para asignar automáticamente el slot activo (`dicthtml-es.zip`, `dicthtml.zip`, etc.).
- **Respaldo de fábrica e integridad SHA-256:** Copia de seguridad automática del diccionario original de fábrica en la primera conexión para restaurarlo en cualquier momento con un solo toque.
- **Biblioteca personalizada:** Almacena y alterna entre múltiples diccionarios creados por la comunidad.

### 🔌 3. Acceso USB Mass Storage Directo (FAT32 Nativo)
- **Control total a nivel de bloque (SCSI BOT):**
  Desconecta el driver del kernel de Android reclamando la interfaz USB (`claimInterface(..., force = true)`), impidiendo que Android cree carpetas no deseadas (`LOST.DIR`, `Android/`, etc.) en el almacenamiento del lector.
- **Detección automática Plug & Play:** Reconoce al instante cualquier Kobo mediante el Vendor ID oficial de Rakuten Kobo (`0x2237`).
- **Expulsión segura por hardware:** Envía comandos SCSI `SYNCHRONIZE CACHE (0x35)` y `START STOP UNIT (0x1B)` para que el lector vuelva a su pantalla de lectura de forma segura y sin corrupción de datos.

### 🎨 4. Diseño "Todo en Una Pantalla" con Identidad Kobo
- **Cero scroll vertical:** Toda la interfaz encaja en una sola pantalla para máxima usabilidad.
- **Temas E-Ink:** Paleta oficial **Rakuten Kobo Crimson Red** combinada con modo claro **E-Ink Carta** y modo oscuro **E-Ink Obsidian**.
- **Acceso Ko-fi permanente:** Botón de apoyo directo siempre accesible en la cabecera.
- **Multi-idioma:** Interfaz disponible en Español, English, Català, Français, Deutsch, Italiano y Português.

---

## ☕ Apoyo y Donaciones

Si **Kobodroid** te resulta útil para gestionar tus lecturas y diccionarios en tu Kobo, puedes apoyar su desarrollo e invitar a un café:

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Invitar%20a%20un%20caf%C3%A9-ff5e5b?style=for-the-badge&logo=kofi&logoColor=white)](https://ko-fi.com/neoruben)

---

## 📄 Licencia y Autoría

Desarrollado con ❤️ por [neoruben](https://github.com/neoruben).
