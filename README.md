# Kobo DictManager for Android 📱📚

Versión nativa para **Android** de [Kobo DictManager](https://github.com/neoruben/kobo-dict-manager): gestiona, cambia y protege diccionarios personalizados en tu lector **Kobo eReader** directamente desde el móvil mediante un cable **USB-C / USB OTG**, sin necesidad de encender el ordenador.

---

## ✨ Características Principales

- **🔌 Acceso USB Mass Storage Directo (Opción B - FAT32 Nativo):**
  - Detecta automáticamente cualquier eReader Kobo al conectarlo por cable USB-C / OTG (Vendor ID oficial de Rakuten Kobo `0x2237` + protocolo SCSI Bulk-Only Transport).
  - Se comunica directamente a nivel de sectores LBA con la partición **FAT32** del lector.
- **🔒 Protección Real contra Sincronización Wi-Fi (`ATTR_READ_ONLY`):**
  - Activa directamente el bit **`ATTR_READ_ONLY` (`0x01`)** en la entrada de directorio de 32 bytes de la tabla FAT32 para `dicthtml-xx.zip`, exactamente igual que en Windows, impidiendo que el Kobo sobrescriba el diccionario personalizado al sincronizar por Wi-Fi.
- **⏏️ Expulsión Segura por Hardware (`SCSI START STOP UNIT`):**
  - Envía los comandos SCSI `SYNCHRONIZE CACHE (0x35)` y `START STOP UNIT (0x1B)` para desmontar con seguridad la memoria del Kobo y hacer que el lector vuelva automáticamente a su pantalla de lectura.
- **🌍 Detección Automática de Idioma (`CurrentLocale`):**
  - Lee `.kobo/Kobo/Kobo eReader.conf` y asigna el slot correspondiente (`dicthtml-es.zip`, `dicthtml.zip`, `dicthtml-fr.zip`, etc.).
- **🏛️ Respaldo de Fábrica e Integridad SHA-256:**
  - Respalda automáticamente el diccionario oficial genuino de fábrica en la primera conexión y verifica su hash SHA-256 para poder restaurarlo en cualquier momento con un toque.
- **🎨 Diseño "Todo en Una Pantalla" con Identidad Kobo:**
  - Paleta **Rakuten Kobo Crimson Red** (`#D5001C`) combinada con modos **E-Ink Carta (Claro)** y **E-Ink Obsidian (Oscuro)**.
  - **Cero scroll vertical:** Toda la interfaz (cabecera, estado USB + acciones rápidas, diccionario oficial de fábrica, biblioteca personalizada paginada y barra de estado) encaja al 100% en una sola pantalla.
- **🌐 Interfaz Multi-idioma:**
  - Español, English, Català, Français, Deutsch, Italiano y Português.
