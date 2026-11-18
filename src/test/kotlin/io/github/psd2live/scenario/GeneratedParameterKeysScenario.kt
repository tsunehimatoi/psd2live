package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.application.WorkspacePresetInitialize
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.modifier.RigModifierAlgorithms
import io.github.psd2live.ui.parameterKeyMarks
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.test.*

class GeneratedParameterKeysScenario {
    @TempDir lateinit var temp: Path

    @Test fun globalMouthKeysEditAllWritersAndSurviveUndoAndReopen() {
        val (prepared, choices) = WorkspacePresetInitialize.prepare(Characters.closedMouthed(), WorkspaceInitialModifiers.RECOMMENDED)
        // Keep separate base/outline/capture entries, as in the reported PSD's preset stack.
        val initialized = WorkspacePresetInitialization.prepare(prepared, WorkspaceRasterWork.Direct, requireNotNull(choices),
            requestedVersions = mapOf("mouth/fitted" to 1)).first
        Studio.run(temp, initialized) {
            val original = now
            val parameter = ParameterId("ParamMouthOpenY")
            val definition = puppet.parameters.single { it.id == parameter }
            fun keys() = puppet.parameterKeyMarks().getValue(parameter).gridKeys
            assertEquals(listOf(0f, .05f, .25f, .5f, 1f), keys())
            fun change(action: String, value: Float, from: Float? = null) {
                val command = mapOf("op" to "parameter_keys", "parameter" to parameter.raw, "action" to action,
                    "values" to listOf(value)) + (from?.let { mapOf("from" to it) } ?: emptyMap())
                // The Parameter Properties dialog submits the definition and key edits as one batch.
                edits("$action mouth key $value",
                    "parameter_update" to req("parameter_id" to parameter.raw, "name" to definition.name,
                        "min" to definition.min, "max" to definition.max, "default" to definition.default),
                    "keyform_apply" to req("changes" to listOf(command)))
                assertEquals(definition, puppet.parameters.single { it.id == parameter }.copy(keys = definition.keys))
            }
            change("delete", .05f)
            assertEquals(listOf(0f, .25f, .5f, 1f), keys())
            change("delete", .25f)
            assertEquals(listOf(0f, .5f, 1f), keys())
            change("move", .6f, .5f)
            assertEquals(listOf(0f, .6f, 1f), keys())
            change("add", .3f)
            assertEquals(listOf(0f, .3f, .6f, 1f), keys())
            val edited = now
            val expected = RigIrCompiler.compile(edited.model)
            checkout("undo mouth key changes", original.historyHead)
            assertEquals(listOf(0f, .05f, .25f, .5f, 1f), keys())
            checkout("redo mouth key changes", edited.historyHead)
            assertEquals(expected, RigIrCompiler.compile(now.model))
            reopen()
            assertEquals(listOf(0f, .3f, .6f, 1f), keys())
            assertEquals(expected, RigIrCompiler.compile(now.model))
            val stack = RigModifierStack.fromJson(requireNotNull(now.document.rigEdits.modifierStack))
            assertTrue(stack.instances.isNotEmpty())
            val owner = stack.overrides.keys.single()
            val providers = stack.overrides.getValue(owner).first().getValue("providers").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(providers.isNotEmpty())
            val previousWriter = stack.instances.indexOfFirst { it.id == providers.last() }
            assertFailsWith<IllegalArgumentException> {
                RigModifierAlgorithms.evaluator().validateOrder(stack.move(owner, previousWriter))
            }
        }
    }
}
