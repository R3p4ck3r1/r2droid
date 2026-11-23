package top.wsdx233.r2droid.feature.disasm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.wsdx233.r2droid.core.data.source.R2DataSource
import java.io.InputStream

class DisasmRepositoryTest {
    private class Source(val json: Result<String>, val text: Result<String> = Result.success("hello")) : R2DataSource {
        override suspend fun execute(cmd: String): Result<String> = text
        override suspend fun executeJson(cmd: String): Result<String> = json
        override suspend fun <T> executeStream(cmd: String, block: suspend (InputStream) -> T): Result<T> = error("unused")
    }

    @Test fun parsingStillResolvesStringReferences() = runTest {
        val repository = DisasmRepository(Source(Result.success("""[{"offset":16,"size":4,"opcode":"nop","refs":[{"type":"STRN","addr":128}]}]""")))
        val instruction = repository.getDisassembly(16, 1).getOrThrow().single()
        assertEquals(16L, instruction.addr)
        assertTrue(instruction.comment.orEmpty().contains("hello"))
    }

    @Test fun malformedJsonIsAFailureNotACrash() = runTest {
        val repository = DisasmRepository(Source(Result.success("not json")))
        assertTrue(repository.getDisassembly(0, 1).isFailure)
    }

    @Test fun cancelledDisassemblyResultPropagatesCancellation() = runTest {
        val repository = DisasmRepository(Source(Result.failure(CancellationException("cancelled"))))
        try {
            repository.getDisassembly(0, 1)
            fail("Cancellation must not become a Result.failure")
        } catch (_: CancellationException) {}
    }

    @Test fun cancelledStringResolutionPropagatesCancellation() = runTest {
        val repository = DisasmRepository(Source(
            Result.success("""[{"offset":16,"size":4,"refs":[{"type":"STRN","addr":128}]}]"""),
            Result.failure(CancellationException("cancelled"))
        ))
        try {
            repository.getDisassembly(16, 1)
            fail("String lookup must not swallow cancellation")
        } catch (_: CancellationException) {}
    }
}
