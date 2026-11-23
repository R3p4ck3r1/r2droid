package top.wsdx233.r2droid.util

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class FlutterTargetResolverTest {
    @Test
    fun validateArm64Elf_acceptsElf64Aarch64() {
        val file = createElf(elfClass = 2, machine = 183)
        try {
            FlutterTargetResolver.validateArm64Elf(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun validateArm64Elf_rejects32BitElf() {
        val file = createElf(elfClass = 1, machine = 183)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FlutterTargetResolver.validateArm64Elf(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun validateArm64Elf_rejectsNonArm64Elf() {
        val file = createElf(elfClass = 2, machine = 62)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FlutterTargetResolver.validateArm64Elf(file)
            }
        } finally {
            file.delete()
        }
    }

    private fun createElf(elfClass: Int, machine: Int): File {
        val bytes = ByteArray(64)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = elfClass.toByte()
        bytes[5] = 1
        bytes[18] = (machine and 0xff).toByte()
        bytes[19] = ((machine ushr 8) and 0xff).toByte()
        return File.createTempFile("flutter-target-", ".so").apply { writeBytes(bytes) }
    }
}
