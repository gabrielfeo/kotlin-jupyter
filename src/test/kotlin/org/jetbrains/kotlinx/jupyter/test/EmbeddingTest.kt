package org.jetbrains.kotlinx.jupyter.test

import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.reflect.KClass
import kotlin.test.DefaultAsserter.fail
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.kotlinx.jupyter.api.InMemoryMimeTypes
import org.jetbrains.kotlinx.jupyter.api.MimeTypedResult
import org.jetbrains.kotlinx.jupyter.api.MimeTypes
import org.jetbrains.kotlinx.jupyter.api.ResultHandlerCodeExecution
import org.jetbrains.kotlinx.jupyter.api.SubtypeRendererTypeHandler
import org.jetbrains.kotlinx.jupyter.api.libraries.LibraryResource
import org.jetbrains.kotlinx.jupyter.api.libraries.ResourceFallbacksBundle
import org.jetbrains.kotlinx.jupyter.api.libraries.ResourceLocation
import org.jetbrains.kotlinx.jupyter.api.libraries.ResourcePathType
import org.jetbrains.kotlinx.jupyter.api.libraries.ResourceType
import org.jetbrains.kotlinx.jupyter.api.libraries.libraryDefinition
import org.jetbrains.kotlinx.jupyter.api.takeScreenshot
import org.jetbrains.kotlinx.jupyter.test.repl.AbstractSingleReplTest
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SomeSingleton {
    companion object {
        var initialized: Boolean = false
    }
}

/**
 * Used for [EmbedReplTest.testSubtypeRenderer]
 */
@Suppress("unused")
class TestSum(val a: Int, val b: Int)

/**
 * Used for [EmbedReplTest.testSubtypeRenderer]
 */
class TestFunList<T>(private val head: T, private val tail: TestFunList<T>?) {
    @Suppress("unused")
    fun render(): String {
        return generateSequence(this) {
            it.tail
        }.joinToString(", ", "[", "]") {
            it.head.toString()
        }
    }
}

/**
 * Used for [EmbedReplTest.testSubtypeRenderer]
 */
@Suppress("unused")
val testLibraryDefinition1 =
    libraryDefinition {
        it.renderers =
            listOf(
                SubtypeRendererTypeHandler(
                    TestSum::class,
                    ResultHandlerCodeExecution("\$it.a + \$it.b"),
                ),
                SubtypeRendererTypeHandler(
                    TestFunList::class,
                    ResultHandlerCodeExecution("\$it.render()"),
                ),
            )
    }

/**
 * Used for [EmbeddedTestWithHackedDisplayHandler.testJsResources]
 */
@Suppress("unused")
val testLibraryDefinition2 =
    libraryDefinition {
        it.resources =
            listOf(
                LibraryResource(
                    listOf(
                        ResourceFallbacksBundle(
                            ResourceLocation(
                                "https://cdn.plot.ly/plotly-latest.min.js",
                                ResourcePathType.URL,
                            ),
                        ),
                        ResourceFallbacksBundle(
                            ResourceLocation(
                                "src/test/testData/js-lib.js",
                                ResourcePathType.LOCAL_PATH,
                            ),
                        ),
                    ),
                    ResourceType.JS,
                    "testLib2",
                ),
            )
    }

/**
 * Class for testing the embedded kernel.
 */
class EmbedReplTest : AbstractSingleReplTest() {
    override val repl = makeEmbeddedRepl()

    @Test
    fun testSharedStaticVariables() {
        var res = eval("org.jetbrains.kotlinx.jupyter.test.SomeSingleton.initialized")
        assertEquals(false, res.renderedValue)

        SomeSingleton.initialized = true

        res = eval("org.jetbrains.kotlinx.jupyter.test.SomeSingleton.initialized")
        assertEquals(true, res.renderedValue)
    }

    @Test
    fun testCustomClasses() {
        eval("class Point(val x: Int, val y: Int)")
        eval("val p = Point(1,1)")

        val res = eval("p.x")
        assertEquals(1, res.renderedValue)
    }

    @Test
    fun testSubtypeRenderer() {
        repl.eval {
            addLibrary(testLibraryDefinition1)
        }
        val result1 = eval("org.jetbrains.kotlinx.jupyter.test.TestSum(5, 8)")
        assertEquals(13, result1.renderedValue)
        val result2 =
            eval(
                """
                import org.jetbrains.kotlinx.jupyter.test.TestFunList
                TestFunList(12, TestFunList(13, TestFunList(14, null)))
                """.trimIndent(),
            )
        assertEquals("[12, 13, 14]", result2.renderedValue)
    }

    @Test
    fun testInMemoryValue() {
        val types = listOf(
            JFrame::class to """
                import javax.swing.JFrame
                val frame = JFrame("panel")
                frame.setSize(300, 300)
                frame.isVisible = true
                frame
            """.trimIndent(),
            JDialog::class to """
                import javax.swing.JDialog
                val dialog = JDialog()
                dialog.setSize(300, 300)
                dialog.isVisible = true
                dialog
            """.trimIndent(),
            JComponent::class to """
                import javax.swing.JPanel
                val panel = JPanel()
                panel.setSize(300, 300)
                panel
            """.trimIndent()
        )
        types.forEach { (expectedOutputClass: KClass<*>, code: String) ->
            val result  = eval(code)
            assertTrue(result.renderedValue is MimeTypedResult)
            assertTrue(result.displayValue is MimeTypedResult)
            val display = result.displayValue as MimeTypedResult
            assertEquals(2, display.size)
            assertTrue(display.containsKey("image/png"))
            assertTrue(display.containsKey(InMemoryMimeTypes.SWING))
            assertEquals("-1", display[InMemoryMimeTypes.SWING])
            val inMemHolder = repl.notebook.sharedReplContext!!.inMemoryReplResultsHolder
            assertEquals(1, inMemHolder.size)
            assertNotNull(inMemHolder.getReplResult("-1", expectedOutputClass))
        }
    }


    @Test
    fun testScreenshotWithNoSize() {
        val panel = JPanel()
        assertNull(panel.takeScreenshot())
    }

    @Test
    fun testScreenshotOfJFrame() {
        val frame = JFrame()
        frame.size = Dimension(100, 50)
        val button = JButton("Button 1")
        frame.contentPane.add(button)
        frame.isVisible = true
        val screenshot = frame.takeScreenshot()
        assertNotEmptyImage(screenshot)
    }

    @Test
    fun testScreenshotOfJDialog() {
        val dialog = JDialog()
        dialog.size = Dimension(100, 50)
        val button = JButton("Button 1")
        dialog.contentPane.add(button)
        dialog.isVisible = true
        val screenshot = dialog.takeScreenshot()
        assertNotEmptyImage(screenshot)
    }

    @Test
    fun testScreenshotOfJComponent() {
        val panel = JPanel()
        panel.size = Dimension(100, 50)
        val button = JButton("Button 1")
        button.size = Dimension(100, 50)
        panel.add(button)
        val screenshot = panel.takeScreenshot()
        assertNotEmptyImage(screenshot)
    }

    // Check if a screenshot actually contains anything useful.
    // We assume "useful" means an image with width/length > 0 and doesn't only consist of
    // one color.
    private fun assertNotEmptyImage(image: BufferedImage?) {
        if (image == null) {
            fail("`null` image was returned")
        }
        assertNotEquals(0, image.width)
        assertNotEquals(0, image.height)
        val topLeftColor = image.getRGB(0, 0)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                if (image.getRGB(x, y) != topLeftColor) {
                    return
                }
            }
        }
        fail("Image only contained a single color: $topLeftColor")
    }

}

class EmbeddedTestWithHackedDisplayHandler : AbstractSingleReplTest() {
    private val displayHandler = TestDisplayHandler()
    override val repl = makeEmbeddedRepl(displayHandler = displayHandler)

    @Test
    fun testJsResources() {
        val res =
            eval(
                "USE(org.jetbrains.kotlinx.jupyter.test.testLibraryDefinition2)",
            )
        assertTrue(res.renderedValue is Unit)
        assertEquals(1, displayHandler.list.size)
        val typedResult = displayHandler.list[0] as MimeTypedResult
        val content = typedResult[MimeTypes.HTML]!!
        assertTrue(content.contains("""id="kotlin_out_0""""))
        assertTrue(content.contains("""function test_fun(x)"""))
    }
}
