package com.neoruben.kobomanager

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Abstracción de dispositivo de bloques (sectores LBA) para permitir tanto acceso
 * USB Mass Storage Bulk-Only Transport (BOT) real como tests unitarios en memoria.
 */
interface BlockDevice : AutoCloseable {
    val sectorSize: Int
    fun readSectors(lba: Long, count: Int, dest: ByteArray, destOffset: Int = 0)
    fun writeSectors(lba: Long, count: Int, src: ByteArray, srcOffset: Int = 0)
    fun sync()
    fun eject(): Boolean
    override fun close()
}

/**
 * Implementación de USB Mass Storage Bulk-Only Transport (BOT - Protocolo 0x50, Subclase SCSI 0x06)
 * usando directamente UsbManager de Android (Opción B).
 */
class UsbBotScsiBlockDevice(
    private val usbManager: UsbManager,
    val usbDevice: UsbDevice
) : BlockDevice {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private val tagCounter = AtomicInteger(1)

    override var sectorSize: Int = 512
        private set

    var totalSectors: Long = 0L
        private set

    fun open() {
        var foundIntf: UsbInterface? = null
        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null

        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                (intf.interfaceSubclass == 6 || intf.interfaceSubclass == 0) &&
                intf.interfaceProtocol == 0x50
            ) {
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                        else epOut = ep
                    }
                }
                if (epIn != null && epOut != null) {
                    foundIntf = intf
                    foundIn = epIn
                    foundOut = epOut
                    break
                }
            }
        }

        if (foundIntf == null || foundIn == null || foundOut == null) {
            throw IOException("El dispositivo USB no expone una interfaz Mass Storage BOT válida.")
        }

        val conn = usbManager.openDevice(usbDevice)
            ?: throw IOException("No se pudo abrir la conexión USB con el lector Kobo.")

        // force = true desconecta el driver del kernel si Android lo montó, dándonos acceso directo a FAT32
        if (!conn.claimInterface(foundIntf, true)) {
            conn.close()
            throw IOException("No se pudo reclamar la interfaz USB Mass Storage.")
        }

        connection = conn
        usbInterface = foundIntf
        inEndpoint = foundIn
        outEndpoint = foundOut

        initializeScsiUnit()
    }

    private fun initializeScsiUnit() {
        // Algunos lectores Kobo reportan Unit Attention en el primer comando tras conectar el cable
        for (attempt in 0 until 4) {
            try {
                scsiTestUnitReady()
                break
            } catch (_: Exception) {
                try {
                    scsiRequestSense()
                } catch (_: Exception) {
                }
                Thread.sleep(80)
            }
        }
        val cap = scsiReadCapacity10()
        totalSectors = cap.first
        sectorSize = cap.second
    }

    private fun scsiTestUnitReady() {
        val cdb = ByteArray(6) // 0x00 TEST UNIT READY
        executeBotCommand(cdb, 0, isRead = true, dataBuffer = null, dataOffset = 0)
    }

    private fun scsiRequestSense(): ByteArray {
        val sense = ByteArray(18)
        val cdb = byteArrayOf(0x03, 0, 0, 0, 18, 0)
        executeBotCommand(cdb, 18, isRead = true, dataBuffer = sense, dataOffset = 0)
        return sense
    }

    private fun scsiReadCapacity10(): Pair<Long, Int> {
        val resp = ByteArray(8)
        val cdb = ByteArray(10)
        cdb[0] = 0x25.toByte() // READ CAPACITY (10)
        executeBotCommand(cdb, 8, isRead = true, dataBuffer = resp, dataOffset = 0)
        val bb = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        val blkLen = bb.int
        return Pair(lastLba + 1L, if (blkLen > 0) blkLen else 512)
    }

    @Synchronized
    override fun readSectors(lba: Long, count: Int, dest: ByteArray, destOffset: Int) {
        var remaining = count
        var currentLba = lba
        var currentOffset = destOffset
        val maxSectorsPerTransfer = 128 // 64 KB por transferencia USB Bulk

        while (remaining > 0) {
            val batch = min(remaining, maxSectorsPerTransfer)
            val byteLen = batch * sectorSize
            val cdb = ByteArray(10)
            cdb[0] = 0x28.toByte() // SCSI READ (10)
            cdb[2] = ((currentLba ushr 24) and 0xFF).toByte()
            cdb[3] = ((currentLba ushr 16) and 0xFF).toByte()
            cdb[4] = ((currentLba ushr 8) and 0xFF).toByte()
            cdb[5] = (currentLba and 0xFF).toByte()
            cdb[7] = ((batch ushr 8) and 0xFF).toByte()
            cdb[8] = (batch and 0xFF).toByte()

            executeBotCommand(cdb, byteLen, isRead = true, dataBuffer = dest, dataOffset = currentOffset)

            remaining -= batch
            currentLba += batch
            currentOffset += byteLen
        }
    }

    @Synchronized
    override fun writeSectors(lba: Long, count: Int, src: ByteArray, srcOffset: Int) {
        var remaining = count
        var currentLba = lba
        var currentOffset = srcOffset
        val maxSectorsPerTransfer = 128 // 64 KB por transferencia USB Bulk

        while (remaining > 0) {
            val batch = min(remaining, maxSectorsPerTransfer)
            val byteLen = batch * sectorSize
            val cdb = ByteArray(10)
            cdb[0] = 0x2A.toByte() // SCSI WRITE (10)
            cdb[2] = ((currentLba ushr 24) and 0xFF).toByte()
            cdb[3] = ((currentLba ushr 16) and 0xFF).toByte()
            cdb[4] = ((currentLba ushr 8) and 0xFF).toByte()
            cdb[5] = (currentLba and 0xFF).toByte()
            cdb[7] = ((batch ushr 8) and 0xFF).toByte()
            cdb[8] = (batch and 0xFF).toByte()

            executeBotCommand(cdb, byteLen, isRead = false, dataBuffer = src, dataOffset = currentOffset)

            remaining -= batch
            currentLba += batch
            currentOffset += byteLen
        }
    }

    @Synchronized
    override fun sync() {
        try {
            val cdb = ByteArray(10)
            cdb[0] = 0x35.toByte() // SCSI SYNCHRONIZE CACHE (10)
            executeBotCommand(cdb, 0, isRead = true, dataBuffer = null, dataOffset = 0)
        } catch (_: Exception) {
            // Algunos controladores USB ignoran SYNCHRONIZE CACHE
        }
    }

    @Synchronized
    override fun eject(): Boolean {
        return try {
            sync()
            // SCSI ALLOW MEDIUM REMOVAL (Prevent = 0)
            try {
                val allowCdb = byteArrayOf(0x1E, 0, 0, 0, 0, 0)
                executeBotCommand(allowCdb, 0, isRead = true, dataBuffer = null, dataOffset = 0)
            } catch (_: Exception) {
            }
            // SCSI START STOP UNIT (Immed = 0, LoEj = 1, Start = 0 -> Expulsar medio)
            val ejectCdb = byteArrayOf(0x1B, 0, 0, 0, 0x02, 0)
            executeBotCommand(ejectCdb, 0, isRead = true, dataBuffer = null, dataOffset = 0)
            close()
            true
        } catch (_: Exception) {
            close()
            true
        }
    }

    private fun executeBotCommand(
        cdb: ByteArray,
        transferLength: Int,
        isRead: Boolean,
        dataBuffer: ByteArray?,
        dataOffset: Int
    ) {
        val conn = connection ?: throw IOException("Conexión USB cerrada.")
        val epOut = outEndpoint ?: throw IOException("Endpoint OUT no disponible.")
        val epIn = inEndpoint ?: throw IOException("Endpoint IN no disponible.")

        val tag = tagCounter.getAndIncrement()
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355) // Signature "USBC"
        cbw.putInt(tag)
        cbw.putInt(transferLength)
        cbw.put(if (isRead) 0x80.toByte() else 0x00.toByte())
        cbw.put(0.toByte()) // LUN 0
        cbw.put(cdb.size.toByte())
        cbw.put(cdb)
        while (cbw.position() < 31) cbw.put(0.toByte())

        val cbwBytes = cbw.array()
        val sentCbw = conn.bulkTransfer(epOut, cbwBytes, cbwBytes.size, 8000)
        if (sentCbw < 0) {
            throw IOException("Fallo enviando CBW por USB (código $sentCbw)")
        }

        if (transferLength > 0 && dataBuffer != null) {
            var remaining = transferLength
            var offset = dataOffset
            val ep = if (isRead) epIn else epOut
            while (remaining > 0) {
                val chunk = min(remaining, 16384)
                val transferred = conn.bulkTransfer(ep, dataBuffer, offset, chunk, 15000)
                if (transferred <= 0) {
                    throw IOException("Error en transferencia de datos USB Bulk ($transferred)")
                }
                remaining -= transferred
                offset += transferred
            }
        }

        val csw = ByteArray(13)
        val cswLen = conn.bulkTransfer(epIn, csw, csw.size, 10000)
        if (cswLen < 13) {
            throw IOException("CSW incompleto recibido del dispositivo USB ($cswLen bytes)")
        }
        val cswBuf = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = cswBuf.int
        val rTag = cswBuf.int
        cswBuf.int // dataResidue
        val status = cswBuf.get().toInt() and 0xFF
        if (sig != 0x53425355 || rTag != tag || status != 0) {
            throw IOException("Estado de error SCSI CSW (status=$status)")
        }
    }

    @Synchronized
    override fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        usbInterface = null
    }
}

/**
 * Representa una entrada de archivo o directorio dentro de una partición FAT32.
 */
data class Fat32Entry(
    val name: String,
    val isDirectory: Boolean,
    val isReadOnly: Boolean,
    val attr: Int,
    val firstCluster: Long,
    val size: Long,
    val dirEntryLba: Long,
    val dirEntryOffset: Int
)

/**
 * Driver FAT32 ligero de lectura/escritura sobre BlockDevice con soporte completo
 * para nombres largos VFAT (LFN), atributo FAT32 Read-Only (0x01) y escritura con progreso.
 */
class Fat32Volume(val device: BlockDevice) {

    var partitionLba: Long = 0L
        private set
    var bytesPerSector: Int = 512
        private set
    var sectorsPerCluster: Int = 8
        private set
    var reservedSectors: Int = 32
        private set
    var numFats: Int = 2
        private set
    var sectorsPerFat: Long = 0L
        private set
    var rootCluster: Long = 2L
        private set
    var fatStartLba: Long = 0L
        private set
    var dataStartLba: Long = 0L
        private set
    var totalClusters: Long = 0L
        private set

    val clusterSize: Int
        get() = bytesPerSector * sectorsPerCluster

    companion object {
        const val ATTR_READ_ONLY = 0x01
        const val ATTR_HIDDEN = 0x02
        const val ATTR_SYSTEM = 0x04
        const val ATTR_VOLUME_ID = 0x08
        const val ATTR_DIRECTORY = 0x10
        const val ATTR_ARCHIVE = 0x20
        const val ATTR_LFN = 0x0F
        private const val EOC_MARKER = 0x0FFFFFFFL
        private const val EOC_MIN = 0x0FFFFFF8L
    }

    init {
        mount()
    }

    private fun mount() {
        val sec0 = ByteArray(device.sectorSize)
        device.readSectors(0L, 1, sec0)

        partitionLba = detectFat32PartitionLba(sec0)
        val bpb = ByteArray(device.sectorSize)
        device.readSectors(partitionLba, 1, bpb)

        if (!isValidFat32Bpb(bpb)) {
            throw IOException("No se encontró un volumen FAT32 válido en el lector.")
        }

        val bb = ByteBuffer.wrap(bpb).order(ByteOrder.LITTLE_ENDIAN)
        bytesPerSector = bb.getShort(0x0B).toInt() and 0xFFFF
        sectorsPerCluster = bpb[0x0D].toInt() and 0xFF
        reservedSectors = bb.getShort(0x0E).toInt() and 0xFFFF
        numFats = bpb[0x10].toInt() and 0xFF
        val totalSec16 = bb.getShort(0x13).toInt() and 0xFFFF
        val totalSec32 = bb.getInt(0x20).toLong() and 0xFFFFFFFFL
        val totalVolumeSectors = if (totalSec16 != 0) totalSec16.toLong() else totalSec32

        sectorsPerFat = bb.getInt(0x24).toLong() and 0xFFFFFFFFL
        rootCluster = bb.getInt(0x2C).toLong() and 0xFFFFFFFFL

        fatStartLba = partitionLba + reservedSectors
        dataStartLba = fatStartLba + (numFats.toLong() * sectorsPerFat)
        val dataSectors = totalVolumeSectors - reservedSectors - (numFats.toLong() * sectorsPerFat)
        totalClusters = dataSectors / sectorsPerCluster
    }

    private fun detectFat32PartitionLba(sec0: ByteArray): Long {
        if (isValidFat32Bpb(sec0)) {
            return 0L
        }
        val bb = ByteBuffer.wrap(sec0).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) {
            val offset = 0x1BE + i * 16
            val partType = sec0[offset + 4].toInt() and 0xFF
            val startLba = bb.getInt(offset + 8).toLong() and 0xFFFFFFFFL
            val numSectors = bb.getInt(offset + 12).toLong() and 0xFFFFFFFFL

            if (partType == 0xEE) {
                // GPT Protective MBR: leer tabla de particiones GPT en LBA 2
                val gptLba = findFat32InGpt()
                if (gptLba != null) return gptLba
            } else if (partType != 0 && startLba > 0 && numSectors > 0) {
                val candidate = ByteArray(device.sectorSize)
                try {
                    device.readSectors(startLba, 1, candidate)
                    if (isValidFat32Bpb(candidate)) return startLba
                } catch (_: Exception) {
                }
            }
        }
        return 0L
    }

    private fun findFat32InGpt(): Long? {
        return try {
            val gptHeader = ByteArray(device.sectorSize)
            device.readSectors(1L, 1, gptHeader)
            val bb = ByteBuffer.wrap(gptHeader).order(ByteOrder.LITTLE_ENDIAN)
            val partEntryLba = bb.getLong(72)
            val numEntries = min(bb.getInt(80), 16)
            val entrySize = bb.getInt(84)
            val entriesBuf = ByteArray(device.sectorSize * 4)
            device.readSectors(partEntryLba, 4, entriesBuf)
            val ebb = ByteBuffer.wrap(entriesBuf).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numEntries) {
                val base = i * entrySize
                if (base + 48 > entriesBuf.size) break
                val firstLba = ebb.getLong(base + 32)
                if (firstLba > 0L) {
                    val testSec = ByteArray(device.sectorSize)
                    device.readSectors(firstLba, 1, testSec)
                    if (isValidFat32Bpb(testSec)) return firstLba
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidFat32Bpb(sector: ByteArray): Boolean {
        if (sector.size < 512) return false
        val sig = ((sector[510].toInt() and 0xFF) or ((sector[511].toInt() and 0xFF) shl 8))
        if (sig != 0xAA55) return false
        val bps = (sector[0x0B].toInt() and 0xFF) or ((sector[0x0C].toInt() and 0xFF) shl 8)
        val spc = sector[0x0D].toInt() and 0xFF
        val spf32 = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN).getInt(0x24)
        return bps == device.sectorSize && spc > 0 && spf32 > 0
    }

    fun clusterToLba(cluster: Long): Long {
        return dataStartLba + (cluster - 2L) * sectorsPerCluster
    }

    fun readFatEntry(cluster: Long): Long {
        val fatByteOffset = cluster * 4L
        val fatSectorLba = fatStartLba + (fatByteOffset / bytesPerSector)
        val offsetInSector = (fatByteOffset % bytesPerSector).toInt()
        val buf = ByteArray(bytesPerSector)
        device.readSectors(fatSectorLba, 1, buf)
        val raw = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt(offsetInSector).toLong() and 0xFFFFFFFFL
        return raw and 0x0FFFFFFFL
    }

    private fun getClusterChain(startCluster: Long): List<Long> {
        if (startCluster < 2L) return emptyList()
        val chain = ArrayList<Long>()
        var curr = startCluster
        val maxLen = totalClusters + 2
        while (curr in 2L until EOC_MIN && chain.size < maxLen) {
            chain.add(curr)
            curr = readFatEntry(curr)
        }
        return chain
    }

    fun listDirectory(dirCluster: Long = rootCluster): List<Fat32Entry> {
        val chain = getClusterChain(dirCluster)
        val entries = ArrayList<Fat32Entry>()
        val lfnParts = ArrayList<Pair<Int, String>>()
        val secBuf = ByteArray(bytesPerSector)

        for (cluster in chain) {
            val baseLba = clusterToLba(cluster)
            for (s in 0 until sectorsPerCluster) {
                val lba = baseLba + s
                device.readSectors(lba, 1, secBuf)

                var offset = 0
                while (offset < bytesPerSector) {
                    val firstByte = secBuf[offset].toInt() and 0xFF
                    if (firstByte == 0x00) {
                        return entries
                    }
                    if (firstByte == 0xE5) {
                        lfnParts.clear()
                        offset += 32
                        continue
                    }

                    val attr = secBuf[offset + 11].toInt() and 0xFF
                    if (attr == ATTR_LFN) {
                        val seq = firstByte and 0x1F
                        val part = decodeLfnEntry(secBuf, offset)
                        lfnParts.add(Pair(seq, part))
                        offset += 32
                        continue
                    }

                    if ((attr and ATTR_VOLUME_ID) != 0) {
                        lfnParts.clear()
                        offset += 32
                        continue
                    }

                    val name = if (lfnParts.isNotEmpty()) {
                        val full = lfnParts.sortedBy { it.first }.joinToString("") { it.second }
                        lfnParts.clear()
                        full
                    } else {
                        decodeShortName(secBuf, offset)
                    }

                    if (name != "." && name != "..") {
                        val bb = ByteBuffer.wrap(secBuf).order(ByteOrder.LITTLE_ENDIAN)
                        val clusHi = bb.getShort(offset + 20).toLong() and 0xFFFFL
                        val clusLo = bb.getShort(offset + 26).toLong() and 0xFFFFL
                        val firstClus = (clusHi shl 16) or clusLo
                        val size = bb.getInt(offset + 28).toLong() and 0xFFFFFFFFL
                        val isDir = (attr and ATTR_DIRECTORY) != 0
                        val isRo = (attr and ATTR_READ_ONLY) != 0

                        entries.add(
                            Fat32Entry(
                                name = name,
                                isDirectory = isDir,
                                isReadOnly = isRo,
                                attr = attr,
                                firstCluster = firstClus,
                                size = size,
                                dirEntryLba = lba,
                                dirEntryOffset = offset
                            )
                        )
                    } else {
                        lfnParts.clear()
                    }
                    offset += 32
                }
            }
        }
        return entries
    }

    private fun decodeLfnEntry(buf: ByteArray, offset: Int): String {
        val chars = CharArray(13)
        val bytePositions = intArrayOf(
            1, 3, 5, 7, 9,
            14, 16, 18, 20, 22, 24,
            28, 30
        )
        var len = 0
        for (i in 0 until 13) {
            val pos = offset + bytePositions[i]
            val lo = buf[pos].toInt() and 0xFF
            val hi = buf[pos + 1].toInt() and 0xFF
            val code = (hi shl 8) or lo
            if (code == 0x0000 || code == 0xFFFF) break
            chars[len++] = code.toChar()
        }
        return String(chars, 0, len)
    }

    private fun decodeShortName(buf: ByteArray, offset: Int): String {
        val ntRes = buf[offset + 12].toInt() and 0xFF
        val lowerBase = (ntRes and 0x08) != 0
        val lowerExt = (ntRes and 0x10) != 0

        val rawBase = ByteArray(8)
        System.arraycopy(buf, offset, rawBase, 0, 8)
        if ((rawBase[0].toInt() and 0xFF) == 0x05) rawBase[0] = 0xE5.toByte()

        var baseStr = String(rawBase, StandardCharsets.ISO_8859_1).trimEnd(' ')
        var extStr = String(buf, offset + 8, 3, StandardCharsets.ISO_8859_1).trimEnd(' ')

        if (lowerBase) baseStr = baseStr.lowercase()
        if (lowerExt) extStr = extStr.lowercase()

        return if (extStr.isNotEmpty()) "$baseStr.$extStr" else baseStr
    }

    fun findEntry(path: String): Fat32Entry? {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var currentCluster = rootCluster
        var currentEntry: Fat32Entry? = null

        for (i in parts.indices) {
            val part = parts[i]
            val list = listDirectory(currentCluster)
            val match = list.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            if (i < parts.lastIndex) {
                if (!match.isDirectory) return null
                currentCluster = match.firstCluster
            } else {
                currentEntry = match
            }
        }
        return currentEntry
    }

    fun readFileBytes(entry: Fat32Entry): ByteArray {
        if (entry.size <= 0L || entry.firstCluster < 2L) return ByteArray(0)
        val out = ByteArray(entry.size.toInt())
        val chain = getClusterChain(entry.firstCluster)
        var remaining = entry.size.toInt()
        var destOffset = 0
        val clusBuf = ByteArray(clusterSize)

        for (clus in chain) {
            if (remaining <= 0) break
            device.readSectors(clusterToLba(clus), sectorsPerCluster, clusBuf)
            val toCopy = min(remaining, clusterSize)
            System.arraycopy(clusBuf, 0, out, destOffset, toCopy)
            destOffset += toCopy
            remaining -= toCopy
        }
        return out
    }

    fun computeFileSha256(entry: Fat32Entry): String {
        val md = MessageDigest.getInstance("SHA-256")
        if (entry.size <= 0L || entry.firstCluster < 2L) {
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        val chain = getClusterChain(entry.firstCluster)
        var remaining = entry.size
        var idx = 0

        while (idx < chain.size && remaining > 0L) {
            // Agrupar clusters contiguos para leer de 64 KB en 64 KB por USB
            val startClus = chain[idx]
            var runClusters = 1
            val maxClustersPerRead = (65536 / clusterSize).coerceAtLeast(1)
            while (idx + runClusters < chain.size &&
                runClusters < maxClustersPerRead &&
                chain[idx + runClusters] == startClus + runClusters
            ) {
                runClusters++
            }
            val sectorsToRead = runClusters * sectorsPerCluster
            val buf = ByteArray(sectorsToRead * bytesPerSector)
            device.readSectors(clusterToLba(startClus), sectorsToRead, buf)

            val toHash = min(remaining, buf.size.toLong()).toInt()
            md.update(buf, 0, toHash)
            remaining -= toHash
            idx += runClusters
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun copyFileToLocal(
        entry: Fat32Entry,
        destFile: File,
        progressCb: ((Long, Long) -> Unit)? = null
    ) {
        destFile.parentFile?.mkdirs()
        val chain = getClusterChain(entry.firstCluster)
        var remaining = entry.size
        val total = entry.size
        var copied = 0L
        var idx = 0

        FileOutputStream(destFile).use { fos ->
            while (idx < chain.size && remaining > 0L) {
                val startClus = chain[idx]
                var runClusters = 1
                val maxClustersPerRead = (65536 / clusterSize).coerceAtLeast(1)
                while (idx + runClusters < chain.size &&
                    runClusters < maxClustersPerRead &&
                    chain[idx + runClusters] == startClus + runClusters
                ) {
                    runClusters++
                }
                val sectorsToRead = runClusters * sectorsPerCluster
                val buf = ByteArray(sectorsToRead * bytesPerSector)
                device.readSectors(clusterToLba(startClus), sectorsToRead, buf)

                val toWrite = min(remaining, buf.size.toLong()).toInt()
                fos.write(buf, 0, toWrite)
                remaining -= toWrite
                copied += toWrite
                idx += runClusters
                progressCb?.invoke(copied, total)
            }
        }
    }

    /**
     * Activa o desactiva directamente el atributo FAT32 ATTR_READ_ONLY (0x01)
     * en la entrada de directorio de 32 bytes en el disco del Kobo.
     */
    fun setFileReadOnly(entry: Fat32Entry, readOnly: Boolean): Fat32Entry {
        val secBuf = ByteArray(bytesPerSector)
        device.readSectors(entry.dirEntryLba, 1, secBuf)
        val oldAttr = secBuf[entry.dirEntryOffset + 11].toInt() and 0xFF
        val newAttr = if (readOnly) {
            oldAttr or ATTR_READ_ONLY
        } else {
            oldAttr and ATTR_READ_ONLY.inv()
        }
        secBuf[entry.dirEntryOffset + 11] = newAttr.toByte()
        device.writeSectors(entry.dirEntryLba, 1, secBuf)
        device.sync()
        return entry.copy(isReadOnly = readOnly, attr = newAttr)
    }

    /**
     * Crea un directorio en `parentPath` (o lo devuelve si ya existe).
     */
    fun createDirectory(parentPath: String, dirName: String): Fat32Entry {
        val parentCluster = resolveDirCluster(parentPath)
        val existing = listDirectory(parentCluster).firstOrNull {
            it.name.equals(dirName, ignoreCase = true)
        }
        if (existing != null) {
            if (!existing.isDirectory) {
                throw IOException("Ya existe un archivo llamado '$dirName' en '$parentPath'.")
            }
            return existing
        }

        // Reservar 1 cluster libre para el nuevo directorio
        val fatBytesPerTable = (sectorsPerFat * bytesPerSector).toInt()
        val fatData = ByteArray(fatBytesPerTable)
        device.readSectors(fatStartLba, sectorsPerFat.toInt(), fatData)
        val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)

        var newCluster = -1L
        for (c in 2L until (totalClusters + 2)) {
            val byteIdx = (c * 4L).toInt()
            if ((fatBuf.getInt(byteIdx).toLong() and 0x0FFFFFFFL) == 0L) {
                newCluster = c
                val prev = fatBuf.getInt(byteIdx).toLong() and 0xF0000000L
                fatBuf.putInt(byteIdx, (prev or EOC_MARKER).toInt())
                val secIdx = byteIdx / bytesPerSector
                val byteOff = secIdx * bytesPerSector
                for (f in 0 until numFats) {
                    device.writeSectors(fatStartLba + (f * sectorsPerFat) + secIdx, 1, fatData, byteOff)
                }
                break
            }
        }
        if (newCluster < 2L) throw IOException("Espacio insuficiente en la partición FAT32 del Kobo.")

        // Inicializar cluster del nuevo directorio con '.' y '..'
        val (dosTime, dosDate) = getCurrentDosDateTime()
        val clusterBuf = ByteArray(clusterSize)
        val cbb = ByteBuffer.wrap(clusterBuf).order(ByteOrder.LITTLE_ENDIAN)

        fun writeDotEntry(off: Int, label: String, targetClus: Long) {
            val nameBytes = label.padEnd(11, ' ').toByteArray(StandardCharsets.ISO_8859_1)
            System.arraycopy(nameBytes, 0, clusterBuf, off, 11)
            cbb.put(off + 11, ATTR_DIRECTORY.toByte())
            cbb.putShort(off + 14, dosTime.toShort())
            cbb.putShort(off + 16, dosDate.toShort())
            cbb.putShort(off + 18, dosDate.toShort())
            cbb.putShort(off + 20, ((targetClus ushr 16) and 0xFFFF).toShort())
            cbb.putShort(off + 22, dosTime.toShort())
            cbb.putShort(off + 24, dosDate.toShort())
            cbb.putShort(off + 26, (targetClus and 0xFFFF).toShort())
            cbb.putInt(off + 28, 0)
        }
        writeDotEntry(0, ".", newCluster)
        writeDotEntry(32, "..", if (parentCluster == rootCluster) 0L else parentCluster)
        device.writeSectors(clusterToLba(newCluster), sectorsPerCluster, clusterBuf)

        val targetSpot = allocateDirectoryEntry(parentCluster, dirName, ATTR_DIRECTORY)
        val dirSec = ByteArray(bytesPerSector)
        device.readSectors(targetSpot.first, 1, dirSec)
        val dbb = ByteBuffer.wrap(dirSec).order(ByteOrder.LITTLE_ENDIAN)
        val off = targetSpot.second
        dbb.put(off + 11, ATTR_DIRECTORY.toByte())
        dbb.putShort(off + 14, dosTime.toShort())
        dbb.putShort(off + 16, dosDate.toShort())
        dbb.putShort(off + 18, dosDate.toShort())
        dbb.putShort(off + 20, ((newCluster ushr 16) and 0xFFFF).toShort())
        dbb.putShort(off + 22, dosTime.toShort())
        dbb.putShort(off + 24, dosDate.toShort())
        dbb.putShort(off + 26, (newCluster and 0xFFFF).toShort())
        dbb.putInt(off + 28, 0)
        device.writeSectors(targetSpot.first, 1, dirSec)
        device.sync()

        return Fat32Entry(
            name = dirName,
            isDirectory = true,
            isReadOnly = false,
            attr = ATTR_DIRECTORY,
            firstCluster = newCluster,
            size = 0L,
            dirEntryLba = targetSpot.first,
            dirEntryOffset = targetSpot.second
        )
    }

    /**
     * Elimina un archivo en `dirPath`, liberando su cadena de clusters y marcando su entrada como borrada (0xE5).
     */
    fun deleteFile(dirPath: String, fileName: String): Boolean {
        val dirCluster = resolveDirCluster(dirPath)
        val chain = getClusterChain(dirCluster)
        val secBuf = ByteArray(bytesPerSector)
        val lfnSlots = ArrayList<Pair<Long, Int>>()
        val lfnParts = ArrayList<Pair<Int, String>>()

        for (cluster in chain) {
            val baseLba = clusterToLba(cluster)
            for (s in 0 until sectorsPerCluster) {
                val lba = baseLba + s
                device.readSectors(lba, 1, secBuf)
                var offset = 0
                while (offset < bytesPerSector) {
                    val firstByte = secBuf[offset].toInt() and 0xFF
                    if (firstByte == 0x00) return false
                    if (firstByte == 0xE5) {
                        lfnSlots.clear()
                        lfnParts.clear()
                        offset += 32
                        continue
                    }

                    val attr = secBuf[offset + 11].toInt() and 0xFF
                    if (attr == ATTR_LFN) {
                        lfnSlots.add(Pair(lba, offset))
                        lfnParts.add(Pair(firstByte and 0x1F, decodeLfnEntry(secBuf, offset)))
                        offset += 32
                        continue
                    }

                    if ((attr and ATTR_VOLUME_ID) != 0) {
                        lfnSlots.clear()
                        lfnParts.clear()
                        offset += 32
                        continue
                    }

                    val name = if (lfnParts.isNotEmpty()) {
                        val full = lfnParts.sortedBy { it.first }.joinToString("") { it.second }
                        lfnParts.clear()
                        full
                    } else {
                        decodeShortName(secBuf, offset)
                    }

                    val isDir = (attr and ATTR_DIRECTORY) != 0
                    if (!isDir && name.equals(fileName, ignoreCase = true)) {
                        val bb = ByteBuffer.wrap(secBuf).order(ByteOrder.LITTLE_ENDIAN)
                        val clusHi = bb.getShort(offset + 20).toLong() and 0xFFFFL
                        val clusLo = bb.getShort(offset + 26).toLong() and 0xFFFFL
                        val firstClus = (clusHi shl 16) or clusLo

                        // 1. Liberar cadena de clusters en FAT1 y FAT2
                        if (firstClus >= 2L) {
                            val fatBytesPerTable = (sectorsPerFat * bytesPerSector).toInt()
                            val fatData = ByteArray(fatBytesPerTable)
                            device.readSectors(fatStartLba, sectorsPerFat.toInt(), fatData)
                            val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)
                            val dirtyFatSectors = HashSet<Int>()

                            var c = firstClus
                            var guard = 0L
                            while (c in 2L until EOC_MIN && guard < totalClusters + 2) {
                                val byteIdx = (c * 4L).toInt()
                                val prev = fatBuf.getInt(byteIdx).toLong()
                                val next = prev and 0x0FFFFFFFL
                                fatBuf.putInt(byteIdx, (prev and 0xF0000000L).toInt())
                                dirtyFatSectors.add(byteIdx / bytesPerSector)
                                c = next
                                guard++
                            }

                            for (secIdx in dirtyFatSectors.sorted()) {
                                val byteOff = secIdx * bytesPerSector
                                for (f in 0 until numFats) {
                                    device.writeSectors(fatStartLba + (f * sectorsPerFat) + secIdx, 1, fatData, byteOff)
                                }
                            }
                        }

                        // 2. Marcar entradas LFN + 8.3 como borradas (0xE5)
                        val allSlots = lfnSlots + Pair(lba, offset)
                        for ((slotLba, slotOff) in allSlots.groupBy { it.first }) {
                            val modBuf = ByteArray(bytesPerSector)
                            device.readSectors(slotLba, 1, modBuf)
                            for ((_, o) in slotOff) {
                                modBuf[o] = 0xE5.toByte()
                            }
                            device.writeSectors(slotLba, 1, modBuf)
                        }
                        device.sync()
                        return true
                    }

                    lfnSlots.clear()
                    offset += 32
                }
            }
        }
        return false
    }

    private fun resolveDirCluster(dirPath: String): Long {
        val trimmed = dirPath.trim('/')
        if (trimmed.isEmpty()) return rootCluster
        val dirEntry = findEntry(trimmed)
            ?: throw IOException("El directorio '$dirPath' no existe en el Kobo.")
        if (!dirEntry.isDirectory) throw IOException("'$dirPath' no es un directorio.")
        return dirEntry.firstCluster
    }

    /**
     * Escribe o reemplaza un archivo desde el almacenamiento local hacia un directorio del Kobo
     * (por ejemplo, ".kobo/dict/dicthtml-es.zip"), actualizando FAT1, FAT2 y el atributo Read-Only.
     */
    fun writeFileFromLocal(
        dirPath: String,
        fileName: String,
        srcFile: File,
        readOnly: Boolean,
        progressCb: ((Long, Long) -> Unit)? = null
    ): Fat32Entry {
        val dirCluster = resolveDirCluster(dirPath)

        val existing = listDirectory(dirCluster).firstOrNull {
            it.name.equals(fileName, ignoreCase = true)
        }

        val fileSize = srcFile.length()
        val neededClusters = if (fileSize == 0L) 0 else ((fileSize + clusterSize - 1) / clusterSize).toInt()

        // Cargar tabla FAT en memoria para actualizar cadenas de clusters de forma atómica y rápida
        val fatBytesPerTable = (sectorsPerFat * bytesPerSector).toInt()
        val fatData = ByteArray(fatBytesPerTable)
        device.readSectors(fatStartLba, sectorsPerFat.toInt(), fatData)
        val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)
        val dirtyFatSectors = HashSet<Int>()

        fun setFatClus(clus: Long, value: Long) {
            val byteIndex = (clus * 4L).toInt()
            val prev = fatBuf.getInt(byteIndex).toLong() and 0xF0000000L
            val combined = (prev or (value and 0x0FFFFFFFL)).toInt()
            fatBuf.putInt(byteIndex, combined)
            dirtyFatSectors.add(byteIndex / bytesPerSector)
        }

        fun getFatClus(clus: Long): Long {
            val byteIndex = (clus * 4L).toInt()
            return (fatBuf.getInt(byteIndex).toLong() and 0x0FFFFFFFL)
        }

        // 1. Liberar cadena anterior si el archivo ya existía
        if (existing != null && existing.firstCluster >= 2L) {
            var c = existing.firstCluster
            var guard = 0L
            while (c in 2L until EOC_MIN && guard < totalClusters + 2) {
                val next = getFatClus(c)
                setFatClus(c, 0L)
                c = next
                guard++
            }
        }

        // 2. Asignar nuevos clusters libres para el nuevo diccionario
        val allocatedClusters = ArrayList<Long>(neededClusters)
        var searchClus = 2L
        while (allocatedClusters.size < neededClusters && searchClus < totalClusters + 2) {
            if (getFatClus(searchClus) == 0L) {
                allocatedClusters.add(searchClus)
            }
            searchClus++
        }

        if (allocatedClusters.size < neededClusters) {
            throw IOException("Espacio insuficiente en la partición FAT32 del Kobo.")
        }

        for (i in 0 until allocatedClusters.size) {
            val curr = allocatedClusters[i]
            val next = if (i == allocatedClusters.lastIndex) EOC_MARKER else allocatedClusters[i + 1]
            setFatClus(curr, next)
        }

        // 3. Escribir los datos del archivo en bloques agrupados con barra de progreso
        val firstNewCluster = if (allocatedClusters.isNotEmpty()) allocatedClusters[0] else 0L
        var copiedBytes = 0L
        FileInputStream(srcFile).use { fis ->
            var idx = 0
            while (idx < allocatedClusters.size) {
                val startClus = allocatedClusters[idx]
                var runClusters = 1
                val maxClustersPerWrite = (65536 / clusterSize).coerceAtLeast(1)
                while (idx + runClusters < allocatedClusters.size &&
                    runClusters < maxClustersPerWrite &&
                    allocatedClusters[idx + runClusters] == startClus + runClusters
                ) {
                    runClusters++
                }

                val runByteSize = runClusters * clusterSize
                val writeBuf = ByteArray(runByteSize)
                var readTotal = 0
                while (readTotal < runByteSize) {
                    val r = fis.read(writeBuf, readTotal, runByteSize - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }

                device.writeSectors(
                    clusterToLba(startClus),
                    runClusters * sectorsPerCluster,
                    writeBuf
                )

                copiedBytes = min(fileSize, copiedBytes + readTotal)
                progressCb?.invoke(copiedBytes, fileSize)
                idx += runClusters
            }
        }

        // 4. Volcar los sectores modificados de la FAT tanto en FAT1 como en FAT2
        val sortedDirty = dirtyFatSectors.sorted()
        for (secIdx in sortedDirty) {
            val byteOff = secIdx * bytesPerSector
            for (f in 0 until numFats) {
                val lba = fatStartLba + (f * sectorsPerFat) + secIdx
                device.writeSectors(lba, 1, fatData, byteOff)
            }
        }

        // 5. Actualizar o crear la entrada de directorio de 32 bytes con ATTR_READ_ONLY
        val attr = ATTR_ARCHIVE or (if (readOnly) ATTR_READ_ONLY else 0)
        val (dosTime, dosDate) = getCurrentDosDateTime()

        val targetSpot: Pair<Long, Int> = if (existing != null) {
            Pair(existing.dirEntryLba, existing.dirEntryOffset)
        } else {
            allocateDirectoryEntry(dirCluster, fileName)
        }

        val dirSec = ByteArray(bytesPerSector)
        device.readSectors(targetSpot.first, 1, dirSec)
        val dbb = ByteBuffer.wrap(dirSec).order(ByteOrder.LITTLE_ENDIAN)
        val off = targetSpot.second

        dbb.put(off + 11, attr.toByte())
        dbb.putShort(off + 14, dosTime.toShort())
        dbb.putShort(off + 16, dosDate.toShort())
        dbb.putShort(off + 18, dosDate.toShort())
        dbb.putShort(off + 20, ((firstNewCluster ushr 16) and 0xFFFF).toShort())
        dbb.putShort(off + 22, dosTime.toShort())
        dbb.putShort(off + 24, dosDate.toShort())
        dbb.putShort(off + 26, (firstNewCluster and 0xFFFF).toShort())
        dbb.putInt(off + 28, fileSize.toInt())

        device.writeSectors(targetSpot.first, 1, dirSec)
        device.sync()

        return Fat32Entry(
            name = fileName,
            isDirectory = false,
            isReadOnly = readOnly,
            attr = attr,
            firstCluster = firstNewCluster,
            size = fileSize,
            dirEntryLba = targetSpot.first,
            dirEntryOffset = targetSpot.second
        )
    }

    private fun collectExistingShortNames(chain: List<Long>): Set<String> {
        val names = HashSet<String>()
        val secBuf = ByteArray(bytesPerSector)
        for (clus in chain) {
            val baseLba = clusterToLba(clus)
            for (s in 0 until sectorsPerCluster) {
                device.readSectors(baseLba + s, 1, secBuf)
                var off = 0
                while (off < bytesPerSector) {
                    val b0 = secBuf[off].toInt() and 0xFF
                    if (b0 == 0x00) return names
                    if (b0 != 0xE5) {
                        val attr = secBuf[off + 11].toInt() and 0xFF
                        if (attr != ATTR_LFN && (attr and ATTR_VOLUME_ID) == 0) {
                            names.add(String(secBuf, off, 11, StandardCharsets.ISO_8859_1))
                        }
                    }
                    off += 32
                }
            }
        }
        return names
    }

    private fun allocateDirectoryEntry(
        dirCluster: Long,
        fileName: String,
        fileAttr: Int = ATTR_ARCHIVE
    ): Pair<Long, Int> {
        val chain = getClusterChain(dirCluster)
        val existingShortNames = collectExistingShortNames(chain)
        val lfnRecords = buildLfnAndShortEntries(fileName, fileAttr, existingShortNames)
        val neededSlots = lfnRecords.size
        val secBuf = ByteArray(bytesPerSector)

        for (clus in chain) {
            val baseLba = clusterToLba(clus)
            for (s in 0 until sectorsPerCluster) {
                val lba = baseLba + s
                device.readSectors(lba, 1, secBuf)
                val maxSlotsPerSector = bytesPerSector / 32
                var consecutiveFree = 0
                var startSlot = 0
                for (slot in 0 until maxSlotsPerSector) {
                    val b0 = secBuf[slot * 32].toInt() and 0xFF
                    if (b0 == 0x00 || b0 == 0xE5) {
                        if (consecutiveFree == 0) startSlot = slot
                        consecutiveFree++
                        if (consecutiveFree == neededSlots) {
                            for (i in 0 until neededSlots) {
                                System.arraycopy(lfnRecords[i], 0, secBuf, (startSlot + i) * 32, 32)
                            }
                            device.writeSectors(lba, 1, secBuf)
                            return Pair(lba, (startSlot + neededSlots - 1) * 32)
                        }
                    } else {
                        consecutiveFree = 0
                    }
                }
            }
        }

        // Extender la cadena de clusters del directorio si todos los sectores actuales están llenos
        val lastCluster = chain.lastOrNull() ?: throw IOException("Directorio FAT32 inválido.")
        val fatBytesPerTable = (sectorsPerFat * bytesPerSector).toInt()
        val fatData = ByteArray(fatBytesPerTable)
        device.readSectors(fatStartLba, sectorsPerFat.toInt(), fatData)
        val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)

        var newCluster = -1L
        for (c in 2L until (totalClusters + 2)) {
            val byteIdx = (c * 4L).toInt()
            if ((fatBuf.getInt(byteIdx).toLong() and 0x0FFFFFFFL) == 0L) {
                newCluster = c
                break
            }
        }
        if (newCluster < 2L) throw IOException("No hay entradas ni clusters libres en el directorio FAT32.")

        val lastIdx = (lastCluster * 4L).toInt()
        val lastPrev = fatBuf.getInt(lastIdx).toLong() and 0xF0000000L
        fatBuf.putInt(lastIdx, (lastPrev or newCluster).toInt())

        val newIdx = (newCluster * 4L).toInt()
        val newPrev = fatBuf.getInt(newIdx).toLong() and 0xF0000000L
        fatBuf.putInt(newIdx, (newPrev or EOC_MARKER).toInt())

        val dirtySectors = setOf(lastIdx / bytesPerSector, newIdx / bytesPerSector)
        for (secIdx in dirtySectors) {
            val byteOff = secIdx * bytesPerSector
            for (f in 0 until numFats) {
                device.writeSectors(fatStartLba + (f * sectorsPerFat) + secIdx, 1, fatData, byteOff)
            }
        }

        val newClusterBuf = ByteArray(clusterSize)
        for (i in 0 until neededSlots) {
            System.arraycopy(lfnRecords[i], 0, newClusterBuf, i * 32, 32)
        }
        val newBaseLba = clusterToLba(newCluster)
        device.writeSectors(newBaseLba, sectorsPerCluster, newClusterBuf)
        return Pair(newBaseLba, (neededSlots - 1) * 32)
    }

    private fun buildLfnAndShortEntries(
        fileName: String,
        fileAttr: Int = ATTR_ARCHIVE,
        existingShortNames: Set<String> = emptySet()
    ): List<ByteArray> {
        val shortBytes = generate83Name(fileName, existingShortNames)
        val checksum = computeLfnChecksum(shortBytes)
        val numLfn = (fileName.length + 12) / 13
        val records = ArrayList<ByteArray>(numLfn + 1)

        for (seq in numLfn downTo 1) {
            val rec = ByteArray(32) { 0xFF.toByte() }
            val seqByte = if (seq == numLfn) (seq or 0x40) else seq
            rec[0] = seqByte.toByte()
            rec[11] = ATTR_LFN.toByte()
            rec[12] = 0
            rec[13] = checksum
            rec[26] = 0
            rec[27] = 0

            val bytePositions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            val charStart = (seq - 1) * 13
            for (i in 0 until 13) {
                val cIdx = charStart + i
                val pos = bytePositions[i]
                if (cIdx < fileName.length) {
                    val code = fileName[cIdx].code
                    rec[pos] = (code and 0xFF).toByte()
                    rec[pos + 1] = ((code ushr 8) and 0xFF).toByte()
                } else if (cIdx == fileName.length) {
                    rec[pos] = 0
                    rec[pos + 1] = 0
                }
            }
            records.add(rec)
        }

        val shortRec = ByteArray(32)
        System.arraycopy(shortBytes, 0, shortRec, 0, 11)
        shortRec[11] = fileAttr.toByte()
        records.add(shortRec)
        return records
    }

    private fun generate83Name(fileName: String, existingShortNames: Set<String> = emptySet()): ByteArray {
        val upper = fileName.uppercase()
        val dot = upper.lastIndexOf('.')
        val rawBase = (if (dot > 0) upper.substring(0, dot) else upper)
            .replace(Regex("[^A-Z0-9_-]"), "")
            .ifEmpty { "FILE" }
        val rawExt = (if (dot > 0) upper.substring(dot + 1) else "")
            .replace(Regex("[^A-Z0-9_-]"), "")
        val ext3 = rawExt.take(3).padEnd(3, ' ')

        val initialBase8 = if (rawBase.length > 8) {
            rawBase.substring(0, 6) + "~1"
        } else {
            rawBase.padEnd(8, ' ')
        }
        val initialCandidate = initialBase8 + ext3
        if (!existingShortNames.contains(initialCandidate)) {
            return initialCandidate.toByteArray(StandardCharsets.ISO_8859_1)
        }

        for (n in 1..999) {
            val suffix = "~$n"
            val prefixLen = (8 - suffix.length).coerceAtLeast(1)
            val candidateBase = (rawBase.take(prefixLen) + suffix).padEnd(8, ' ')
            val candidate = candidateBase + ext3
            if (!existingShortNames.contains(candidate)) {
                return candidate.toByteArray(StandardCharsets.ISO_8859_1)
            }
        }
        return initialCandidate.toByteArray(StandardCharsets.ISO_8859_1)
    }

    private fun computeLfnChecksum(shortName11: ByteArray): Byte {
        var sum = 0
        for (i in 0 until 11) {
            sum = (((sum and 1) shl 7) + (sum ushr 1) + (shortName11[i].toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    private fun getCurrentDosDateTime(): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        val year = (cal.get(Calendar.YEAR) - 1980).coerceIn(0, 127)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND) / 2

        val dosDate = (year shl 9) or (month shl 5) or day
        val dosTime = (hour shl 11) or (minute shl 5) or second
        return Pair(dosTime, dosDate)
    }
}
