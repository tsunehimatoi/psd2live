package io.github.psd2live.ui

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CachedSkiaPictureTest {
    @Test fun eachCanvasRecordsOnlyWhenItsOwnArtworkChanges() {
        CachedSkiaPicture().use { first ->
            CachedSkiaPicture().use { second ->
                Surface.makeRasterN32Premul(8, 8).use { surface ->
                    var firstRecords = 0
                    var secondRecords = 0
                    fun recordFirst(key: String) = first.draw(surface.canvas, listOf(key), 8, 8) { canvas ->
                        firstRecords++
                        Paint().use { canvas.drawRect(Rect.makeWH(8f, 8f), it) }
                    }
                    fun recordSecond(key: String) = second.draw(surface.canvas, listOf(key), 8, 8) { canvas ->
                        secondRecords++
                        Paint().use { canvas.drawRect(Rect.makeWH(8f, 8f), it) }
                    }

                    recordFirst("pose-a")
                    recordSecond("pose-a")
                    recordFirst("pose-a")
                    recordSecond("pose-b")
                    recordFirst("pose-a")
                    assertEquals(1, firstRecords)
                    assertEquals(2, secondRecords)

                    recordFirst("pose-b")
                    assertEquals(2, firstRecords)
                    surface.makeImageSnapshot().use { snapshot ->
                        val encoded = requireNotNull(snapshot.encodeToData(EncodedImageFormat.PNG, 100))
                        encoded.use {
                            val pixel = ImageIO.read(ByteArrayInputStream(it.bytes)).getRGB(4, 4)
                            assertTrue((pixel ushr 24) > 0, "cached picture must still paint after recording resources close")
                        }
                    }
                }
            }
        }
    }
}
