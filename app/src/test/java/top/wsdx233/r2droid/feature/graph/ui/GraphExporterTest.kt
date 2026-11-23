package top.wsdx233.r2droid.feature.graph.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wsdx233.r2droid.core.data.model.GraphBlockInstruction
import top.wsdx233.r2droid.core.data.model.GraphData
import top.wsdx233.r2droid.core.data.model.GraphNode
import top.wsdx233.r2droid.feature.project.GraphType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GraphExporterTest {

    @Test
    fun `getSanitizedGraphTitle sanitizes titles properly`() {
        val dataWithTitle = GraphData(
            nodes = listOf(GraphNode(id = 0, title = "main")),
            title = "sym.main:test?*<>|"
        )
        val sanitized = GraphExporter.getSanitizedGraphTitle(dataWithTitle)
        assertEquals("sym.main_test", sanitized)

        val dataWithNodeTitle = GraphData(
            nodes = listOf(GraphNode(id = 0, title = "entry_0x401000"))
        )
        assertEquals("entry_0x401000", GraphExporter.getSanitizedGraphTitle(dataWithNodeTitle))

        val emptyData = GraphData(nodes = emptyList())
        assertEquals("flowchart", GraphExporter.getSanitizedGraphTitle(emptyData))
    }

    @Test
    fun `renderGraphToBitmap renders valid bitmap for layout result`() {
        val node1 = GraphNode(
            id = 0,
            title = "entry",
            address = 0x401000L,
            instructions = listOf(
                GraphBlockInstruction(addr = 0x401000L, opcode = "push rbp", disasm = "push rbp", type = "push", bytes = "55")
            ),
            outNodes = listOf(1)
        )
        val node2 = GraphNode(
            id = 1,
            title = "exit",
            address = 0x401010L,
            instructions = listOf(
                GraphBlockInstruction(addr = 0x401010L, opcode = "ret", disasm = "ret", type = "ret", bytes = "c3")
            )
        )
        val data = GraphData(nodes = listOf(node1, node2), title = "sym.test")
        val layout = layoutGraph(data, GraphType.FunctionFlow)
        assertFalse(layout.nodes.isEmpty())

        val graphBounds = Rect(0f, 0f, 800f, 1000f)
        val bitmap = GraphExporter.renderGraphToBitmap(
            layoutResult = layout,
            graphBounds = graphBounds,
            structuredNodeLayout = true,
            density = 2.5f
        )

        assertNotNull(bitmap)
        assertTrue(bitmap!!.width > 0)
        assertTrue(bitmap.height > 0)
    }

    @Test
    fun `renderGraphToBitmap returns null for empty graph`() {
        val emptyLayout = GraphLayoutResult(emptyList(), emptyList())
        val bitmap = GraphExporter.renderGraphToBitmap(
            layoutResult = emptyLayout,
            graphBounds = Rect.Zero,
            structuredNodeLayout = false,
            density = 2.0f
        )
        assertNull(bitmap)
    }
}
