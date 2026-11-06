package example

import io.github.psd2live.algorithms.binding.BlendShapeLowering
import org.umamo.runtime.model.PuppetModel
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BlendShapeLoweringTest {
    @Test fun unchangedInputIsReusedAndCancellationDoesNotPublishAResult() {
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        val lowering = BlendShapeLowering({ _, _ -> error("No carrier is needed") })
        assertSame(source, lowering.lawful(source))
        assertFailsWith<CancellationException> {
            BlendShapeLowering({ _, _ -> error("No carrier is needed") }) {
                throw CancellationException()
            }.lawful(source)
        }
        assertSame(source, lowering.lawful(source))
    }
}
