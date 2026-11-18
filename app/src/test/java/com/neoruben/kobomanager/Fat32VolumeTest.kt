package com.neoruben.kobomanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class InMemoryBlockDevice(totalSectors: Int, override val sectorSize: Int = 512) : BlockDevice {
    private val data = ByteArray(totalSectors * sectorSize)
    var ejected = false

    override fun readSectors(lba: Long, count: Int, dest: ByteArray, destOffset: Int) {
        System.arraycopy(data, (lba * sectorSize).toInt(), dest, destOffset, count * sectorSize)
    }

    override fun writeSectors(lba: Long, count: Int, src: ByteArray, srcOffset: Int) {
        System.arraycopy(src, srcOffset, data, (lba * sectorSize).toInt(), count * sectorSize)
    }

    override fun sync() {}

    override fun eject(): Boolean {
        ejected = true
        return true
    }

    override fun close() {}
}

class Fat32VolumeTest {

    private fun createMockKoboFat32Device(): InMemoryBlockDevice {
        val totalSectors = 2048
        val dev = InMemoryBlockDevice(totalSectors, 512)

        // Crear BPB FAT32 en Sector 0 (Superfloppy)
        val bpb = ByteArray(512)
        val bb = ByteBuffer.wrap(bpb).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0x0B, 512) // bytesPerSector
        bpb[0x0D] = 1          // sectorsPerCluster = 1 (512B por cluster para test rápido)
        bb.putShort(0x0E, 32)  // reservedSectors = 32
        bpb[0x10] = 2          // numFats = 2
        bb.putInt(0x20, totalSectors)
        bb.putInt(0x24, 16)    // sectorsPerFat = 16
        bb.putInt(0x2C, 2)     // rootCluster = 2
        bpb[510] = 0x55
        bpb[511] = 0xAA.toByte()
        dev.writeSectors(0, 1, bpb)

        // Inicializar FAT1 (LBA 32) y FAT2 (LBA 48)
        // Cluster 2 = Root dir
        // Cluster 3 = .kobo dir
        // Cluster 4 = .kobo/version file
        // Cluster 5 = .kobo/dict dir
        val fat = ByteArray(512)
        val fbb = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN)
        fbb.putInt(0, 0x0FFFFFF8)
        fbb.putInt(4, 0x0FFFFFFF)
        fbb.putInt(2 * 4, 0x0FFFFFFF) // Root dir
        fbb.putInt(3 * 4, 0x0FFFFFFF) // .kobo dir
        fbb.putInt(4 * 4, 0x0FFFFFFF) // version file
        fbb.putInt(5 * 4, 0x0FFFFFFF) // dict dir
        dev.writeSectors(32, 1, fat)
        dev.writeSectors(48, 1, fat)

        val dataStartLba = 32L + 2L * 16L // LBA 64

        // Root directory (Cluster 2 -> LBA 64): entrada ".kobo" (directorio, cluster 3)
        val vol = Fat32Volume(dev)
        // Usamos el propio driver o escribimos directorio ".kobo" en Root (Cluster 2)
        val rootSec = ByteArray(512)
        writeDirEntryWithLfn(rootSec, 0, ".kobo", Fat32Volume.ATTR_DIRECTORY, 3, 0)
        dev.writeSectors(dataStartLba + (2 - 2), 1, rootSec)

        // Directorio ".kobo" (Cluster 3 -> LBA 65): entradas "version" (cluster 4) y "dict" (cluster 5)
        val koboDirSec = ByteArray(512)
        val verBytes = "N36512345678,4.9.56,4.46.23456,0,0,00000000-0000-0000-0000-000000000391"
            .toByteArray(StandardCharsets.UTF_8)
        var nextOff = writeDirEntryWithLfn(koboDirSec, 0, "version", Fat32Volume.ATTR_ARCHIVE, 4, verBytes.size)
        writeDirEntryWithLfn(koboDirSec, nextOff, "dict", Fat32Volume.ATTR_DIRECTORY, 5, 0)
        dev.writeSectors(dataStartLba + (3 - 2), 1, koboDirSec)

        // Contenido de ".kobo/version" (Cluster 4 -> LBA 66)
        val verSec = ByteArray(512)
        System.arraycopy(verBytes, 0, verSec, 0, verBytes.size)
        dev.writeSectors(dataStartLba + (4 - 2), 1, verSec)

        assertNotNull(vol)
        return dev
    }

    private fun writeDirEntryWithLfn(
        sec: ByteArray,
        offset: Int,
        name: String,
        attr: Int,
        firstCluster: Int,
        size: Int
    ): Int {
        val shortName = "ITEM    DAT".toByteArray(StandardCharsets.ISO_8859_1)
        var chk = 0
        for (i in 0 until 11) {
            chk = (((chk and 1) shl 7) + (chk ushr 1) + (shortName[i].toInt() and 0xFF)) and 0xFF
        }
        val lfn = ByteArray(32) { 0xFF.toByte() }
        lfn[0] = 0x41
        lfn[11] = Fat32Volume.ATTR_LFN.toByte()
        lfn[12] = 0
        lfn[13] = chk.toByte()
        lfn[26] = 0
        lfn[27] = 0
        val pos = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        for (i in 0 until 13) {
            if (i < name.length) {
                val c = name[i].code
                lfn[pos[i]] = (c and 0xFF).toByte()
                lfn[pos[i] + 1] = ((c ushr 8) and 0xFF).toByte()
            } else if (i == name.length) {
                lfn[pos[i]] = 0
                lfn[pos[i] + 1] = 0
            }
        }
        System.arraycopy(lfn, 0, sec, offset, 32)

        val sEntry = ByteArray(32)
        System.arraycopy(shortName, 0, sEntry, 0, 11)
        val bb = ByteBuffer.wrap(sEntry).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(11, attr.toByte())
        bb.putShort(20, ((firstCluster ushr 16) and 0xFFFF).toShort())
        bb.putShort(26, (firstCluster and 0xFFFF).toShort())
        bb.putInt(28, size)
        System.arraycopy(sEntry, 0, sec, offset + 32, 32)
        return offset + 64
    }

    @Test
    fun testFat32WriteReadAndReadOnlyProtection() {
        val dev = createMockKoboFat32Device()
        val vol = Fat32Volume(dev)

        val verEntry = vol.findEntry(".kobo/version")
        assertNotNull(verEntry)
        val verText = String(vol.readFileBytes(verEntry!!), StandardCharsets.UTF_8)
        assertTrue(verText.startsWith("N365"))

        // Escribir un diccionario simulado en .kobo/dict/dicthtml-es.zip con Read-Only activado
        val tempDict = File.createTempFile("test_dict", ".zip")
        try {
            val payload = ByteArray(3500) { idx -> ((idx * 31) and 0xFF).toByte() }
            tempDict.writeBytes(payload)

            val written = vol.writeFileFromLocal(
                dirPath = ".kobo/dict",
                fileName = "dicthtml-es.zip",
                srcFile = tempDict,
                readOnly = true
            )
            assertTrue(written.isReadOnly)
            assertEquals(payload.size.toLong(), written.size)

            // Verificar lectura de vuelta desde la partición FAT32
            val found = vol.findEntry(".kobo/dict/dicthtml-es.zip")
            assertNotNull(found)
            assertTrue(found!!.isReadOnly)
            assertArrayEquals(payload, vol.readFileBytes(found))

            // Desactivar Read-Only y comprobar que cambia en disco
            vol.setFileReadOnly(found, false)
            val afterUnlock = vol.findEntry(".kobo/dict/dicthtml-es.zip")
            assertNotNull(afterUnlock)
            assertFalse(afterUnlock!!.isReadOnly)
        } finally {
            tempDict.delete()
        }
    }

    @Test
    fun testCreateDirectoryWriteBooksAndDelete() {
        val dev = createMockKoboFat32Device()
        val vol = Fat32Volume(dev)

        val booksDir = vol.createDirectory("", "Books")
        assertTrue(booksDir.isDirectory)
        assertEquals("Books", booksDir.name)

        // Idempotencia al volver a crear el mismo directorio
        val booksDirAgain = vol.createDirectory("", "Books")
        assertEquals(booksDir.firstCluster, booksDirAgain.firstCluster)

        val tempBook1 = File.createTempFile("book1", ".epub")
        val tempBook2 = File.createTempFile("book2", ".epub")
        try {
            val payload1 = ByteArray(1800) { idx -> (idx and 0xFF).toByte() }
            val payload2 = ByteArray(1200) { idx -> ((idx * 7) and 0xFF).toByte() }
            tempBook1.writeBytes(payload1)
            tempBook2.writeBytes(payload2)

            vol.writeFileFromLocal("Books", "El_Quijote_Parte_1.kepub.epub", tempBook1, readOnly = false)
            vol.writeFileFromLocal("Books", "El_Quijote_Parte_2.epub", tempBook2, readOnly = false)

            val entries = vol.listDirectory(booksDir.firstCluster)
            assertEquals(2, entries.size)
            assertTrue(entries.any { it.name == "El_Quijote_Parte_1.kepub.epub" && !it.isReadOnly })
            assertTrue(entries.any { it.name == "El_Quijote_Parte_2.epub" && !it.isReadOnly })

            val readBack1 = vol.findEntry("Books/El_Quijote_Parte_1.kepub.epub")
            assertNotNull(readBack1)
            assertArrayEquals(payload1, vol.readFileBytes(readBack1!!))

            // Eliminar el primer libro y verificar que el segundo sigue intacto
            assertTrue(vol.deleteFile("Books", "El_Quijote_Parte_1.kepub.epub"))
            val afterDelete = vol.listDirectory(booksDir.firstCluster)
            assertEquals(1, afterDelete.size)
            assertEquals("El_Quijote_Parte_2.epub", afterDelete[0].name)

            val readBack2 = vol.findEntry("Books/El_Quijote_Parte_2.epub")
            assertNotNull(readBack2)
            assertArrayEquals(payload2, vol.readFileBytes(readBack2!!))

            // Reutilizar el espacio liberado escribiendo otro libro
            vol.writeFileFromLocal("Books", "Dune.epub", tempBook1, readOnly = false)
            val afterReadd = vol.listDirectory(booksDir.firstCluster)
            assertEquals(2, afterReadd.size)
            assertTrue(afterReadd.any { it.name == "Dune.epub" })
        } finally {
            tempBook1.delete()
            tempBook2.delete()
        }
    }
}
