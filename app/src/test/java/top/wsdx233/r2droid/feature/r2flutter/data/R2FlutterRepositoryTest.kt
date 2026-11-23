package top.wsdx233.r2droid.feature.r2flutter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R2FlutterRepositoryTest {
    @Test
    fun extractJsonPayload_skipsLogsAndTrailingOutput() {
        val raw = "INFO: loading snapshot\n{\"kind\":3,\"hash\":\"abc\"}\nINFO: done"

        assertEquals(
            "{\"kind\":3,\"hash\":\"abc\"}",
            R2FlutterRepository.extractJsonPayload(raw)
        )
    }

    @Test
    fun extractJsonPayload_handlesBracketsInsideStrings() {
        val raw = "prefix [\"value ] still in string\",{\"nested\":[1,2]}] suffix"

        assertEquals(
            "[\"value ] still in string\",{\"nested\":[1,2]}]",
            R2FlutterRepository.extractJsonPayload(raw)
        )
    }

    @Test
    fun extractJsonPayload_rejectsIncompleteJson() {
        assertThrows(IllegalStateException::class.java) {
            R2FlutterRepository.extractJsonPayload("INFO {\"kind\":3")
        }
    }
}
