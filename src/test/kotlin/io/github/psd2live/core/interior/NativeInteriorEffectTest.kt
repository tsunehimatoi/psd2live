package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

class NativeInteriorEffectTest {
	@TempDir lateinit var directory: Path

	private fun preview(): RigPreviewModel {
		val w = 96
		val h = 144
		val raster =
			LayerRaster(
				w,
				h,
				ByteArray(w * h * 4) { if (it % 4 == 3) 255.toByte() else 140.toByte() },
			)
		val layer =
			WorkspaceSourceLayer(
				LayerId("cloth"),
				"cloth",
				"",
				SourceLayerKind.Raster,
				true,
				0,
				LayerBounds(12, 8, w, h),
				1f,
				false,
				LayerBlend.Normal,
				ChannelMask.ALL,
				raster,
				null,
				null,
				false,
			)
		return PSD2LivePipeline()
			.buildPreview(
				WorkspaceSourceArt(128, 168, listOf(layer), emptyList()),
				PipelineConfig(atlasSize = 512, meshSpacing = 16),
			)
	}

	private fun request(target: String, motion: Boolean = true, parameters: List<String>? = null) =
		buildJsonObject {
			put("target", target)
			put("enabled", true)
			put("motion", InteriorMotionSettings(enabled = motion).toJson())
			put(
				"settings",
				NativeInteriorAuthoring.settingsJson(InteriorSurfaceSettings(contour = .35f)),
			)
			parameters?.let { put("parameters", JsonArray(it.map(::JsonPrimitive))) }
		}

	private fun error(a: FloatArray, b: FloatArray) =
		(a.indices step 2).maxOf {
			hypot((a[it] - b[it]).toDouble(), (a[it + 1] - b[it + 1]).toDouble())
		}

	@Test
	fun nativeRecipeRetainsTheOriginalMeshAndReplaysAndExportsTheSameMotion() {
		val p = preview()
		val authored = p.authored.rig.puppet
		val d = authored.drawables.first { it.mesh != null }
		val overlay = NativeInteriorAuthoring.put(p, RigEditOverlay(), request(d.id.raw))
		val effect = overlay.interiorEffects.single()
		val model = DocumentGenerators.generate(authored, overlay)
		val toggled =
			NativeInteriorAuthoring.put(
					p,
					overlay,
					buildJsonObject {
						put("target", d.id.raw)
						put("enabled", false)
						put("state", "captured-state")
					},
				)
				.interiorEffects
				.single()
		assertFalse(toggled.enabled)
		val node = "interiorEffect:${effect.id}"
		assertTrue("document:generators" in DocumentGenerators.changed(RigEditOverlay(), overlay))
		assertTrue(node in DocumentGenerators.stale(RigEditOverlay(), overlay))
		val disabledOverlay = overlay.copy(interiorEffects = listOf(toggled))
		assertTrue("document:$node" in DocumentGenerators.changed(overlay, disabledOverlay))
		val stale = DocumentGenerators.stale(overlay, disabledOverlay)
		assertTrue(node in stale)
		assertTrue(DocumentGenerators.PHYSICS in stale)
		assertTrue(DocumentGenerators.MOTIONS in stale)
		assertTrue(DocumentGenerators.changed(overlay, overlay).isEmpty())
		assertTrue("document:generators" in DocumentGenerators.changed(overlay, RigEditOverlay()))
		assertSame(
			effect.bindings,
			toggled.bindings,
			"MCP context fields must not turn a toggle into a rebake",
		)
		assertSame(d.mesh, model.drawables.single { it.id == d.id }.mesh)
		assertSame(d.geometryGrid, model.drawables.single { it.id == d.id }.geometryGrid)
		assertEquals(d.parentDeformerId, model.drawables.single { it.id == d.id }.parentDeformerId)
		val copy =
			NativeInteriorEffect.fromJson(
				Json.parseToJsonElement(effect.toJson().toString()).jsonObject
			)
		assertEquals(effect.toJson(), copy.toJson())
		val reopened =
			DocumentGenerators.generate(authored, overlay.copy(interiorEffects = listOf(copy)))
		val exported = deletionExport(reopened)
		val evaluator = CpuDeformationEvaluator()
		val engine = PhysicsEngine(effect.groups, PhysicsEngine.ranges(model.parameters), 60f)
		engine.settle(effect.sources.associate { it.id.raw to it.default })
		for (i in 0..240) {
			val values =
				effect.sources
					.mapIndexed { a, v ->
						val q = (if (a == 0) sin(i * .073f) else cos(i * .051f)) * .9f
						v.id.raw to
							(v.default + q * (if (q < 0) v.default - v.min else v.max - v.default))
					}
					.toMap()
			val pose = (values + engine.step(values, 1f / 60)).mapKeys { ParameterId(it.key) }
			val a = evaluator.evaluate(model, pose).worldPositions.getValue(d.id)
			assertTrue(
				error(a, evaluator.evaluate(reopened, pose).worldPositions.getValue(d.id)) < .0001
			)
			assertTrue(
				error(a, evaluator.evaluate(exported, pose).worldPositions.getValue(d.id)) < .003
			)
		}
		val disabled = effect.enabled(false)
		val off =
			DocumentGenerators.generate(authored, overlay.copy(interiorEffects = listOf(disabled)))
		assertEquals(d.blendShapes, off.drawables.single { it.id == d.id }.blendShapes)
	}

	@Test
	fun existingNonzeroAsymmetricSourcesStayNeutralAndKeepNativeBlendZero() {
		val original = preview()
		val x = Parameter(ParameterId("CustomX"), "X", -10f, 50f, 5f)
		val y = Parameter(ParameterId("CustomY"), "Y", -8f, 20f, 2f)
		val base =
			original.authored.rig.copy(
				puppet =
					original.authored.rig.puppet.copy(
						parameters = original.authored.rig.puppet.parameters + listOf(x, y)
					)
			)
		val p = original.copy(rig = base, sources = PreviewRigSources.of(base))
		val d = base.puppet.drawables.first { it.mesh != null }
		val evaluator = CpuDeformationEvaluator()
		for (dynamic in listOf(false, true)) {
			val effect =
				NativeInteriorAuthoring.put(
						p,
						RigEditOverlay(),
						request(d.id.raw, dynamic, listOf(x.id.raw, y.id.raw)),
					)
					.interiorEffects
					.single()
			val model = effect.apply(base.puppet)
			assertTrue(effect.bindings.all { it.keys[it.neutralIndex] == 0f })
			val values = mapOf(x.id.raw to x.default, y.id.raw to y.default)
			val output =
				PhysicsEngine(effect.groups, PhysicsEngine.ranges(model.parameters), 60f)
					.settle(values, 3f)
			val pose = (values + output).mapKeys { ParameterId(it.key) }
			val before = evaluator.evaluate(base.puppet, pose).worldPositions.getValue(d.id)
			val after = evaluator.evaluate(model, pose).worldPositions.getValue(d.id)
			assertTrue(
				error(before, after) < .005,
				"dynamic=$dynamic nonzero default must remain neutral: ${error(before,after)}",
			)
			assertEquals(x, model.parameters.single { it.id == x.id })
			assertEquals(y, model.parameters.single { it.id == y.id })
			val exported = deletionExport(model)
			assertTrue(
				error(after, evaluator.evaluate(exported, pose).worldPositions.getValue(d.id)) <
					.003,
				"static or dynamic source binding must export unchanged",
			)
			for (a in listOf(-10f, 5f, 33f, 50f)) for (b in listOf(-8f, 2f, 12f, 20f)) {
				val keyed = mapOf(x.id to a, y.id to b)
				assertTrue(
					error(
						evaluator.evaluate(model, keyed).worldPositions.getValue(d.id),
						evaluator.evaluate(exported, keyed).worldPositions.getValue(d.id),
					) < .003
				)
			}
		}
	}

	@Test
	fun realProjectArchiveAndHistoryRestoreGeneratedFieldsAndPhysics() = runBlocking {
		val p = preview()
		val base = p.authored.rig
		val target = base.puppet.drawables.first { it.mesh != null }.id.raw
		val generated = NativeInteriorAuthoring.put(p, RigEditOverlay(), request(target))
		val checkpoint =
			RigCheckpoint.encode(AuthoredRig(base, emptyList()), "native-interior-test")
		val before =
			WorkspaceDocument(
				p.analysis.source as WorkspaceSourceArt,
				emptyMap(),
				emptySet(),
				emptyMap(),
				emptyMap(),
				RigEditOverlay(authoringJournal = listOf(checkpoint)),
			)
		val after =
			before.copy(
				rigEdits = before.rigEdits.copy(interiorEffects = generated.interiorEffects)
			)
		val revision = WorkspaceRevisions.of(before)
		val history = WorkspaceHistoryTree(before, revision, revision)
		val first = history.head().node.id
		val next = WorkspaceRevisions.of(after)
		history.commit(first, after, next, next, "Interior shape", "user")
		val store = WorkspaceStore(directory.resolve("store"))
		store.persistHistory("interior", history.state())
		val file = directory.resolve("interior.psd2live")
		ProjectRepository(writeHeadCache = false)
			.save(
				ProjectSaveCapture(
					"interior",
					history.state(),
					JsonObject(emptyMap()),
					null,
					store,
				),
				file,
			)
		ProjectRepository(writeHeadCache = false).open(file).use { opened ->
			val loaded = opened.history.head().snapshot
			assertEquals(next, WorkspaceRevisions.of(loaded))
			assertEquals(
				generated.interiorEffects.single().toJson(),
				loaded.rigEdits.interiorEffects.single().toJson(),
			)
			assertTrue(
				opened.history.selectionAt(first).snapshot.rigEdits.interiorEffects.isEmpty()
			)
			val restored = loaded.rigEdits.applyTo(base.puppet)
			assertTrue(restored.drawables.single { it.id.raw == target }.blendShapes.isNotEmpty())
			val groups =
				PhysicsCatalog.active(
					p.analysis,
					p.config.copy(rigEdits = loaded.rigEdits),
					restored.parameters.map { it.id.raw }.toSet(),
				)
			assertEquals(
				4,
				groups.count { it.id.startsWith(generated.interiorEffects.single().id) },
			)
		}
	}

	@Test
	fun changedMeshKeepsTheRecipeButNeverAppliesStaleVertexData() {
		val p = preview()
		val base = p.authored.rig.puppet
		val d = base.drawables.first { it.mesh != null }
		val effect =
			NativeInteriorAuthoring.put(p, RigEditOverlay(), request(d.id.raw))
				.interiorEffects
				.single()
		val original = d.mesh!!
		val changed =
			d.copy(
				mesh =
					DrawableMesh(
						original.positions.copyOf().also { it[0] += .2f },
						original.uvs,
						original.indices,
					)
			)
		val model = base.copy(drawables = base.drawables.map { if (it.id == d.id) changed else it })
		assertNotNull(effect.issue(model))
		assertSame(model, effect.apply(model))
		assertTrue(
			effect.bindings.isNotEmpty(),
			"the recipe stays available for explicit regeneration",
		)
	}

	@Test
	fun disabledEffectsKeepEditorSettingsButDoNotExportUnusedOwnedParameters() {
		val p = preview()
		val base = p.authored.rig.puppet
		val d = base.drawables.first { it.mesh != null }
		val enabled =
			NativeInteriorAuthoring.put(
				p,
				RigEditOverlay(),
				request(d.id.raw, parameters = listOf("", "")),
			)
		val effect = enabled.interiorEffects.single()
		val off = enabled.copy(interiorEffects = listOf(effect.enabled(false)))
		val shown = DocumentGenerators.generate(base, off)
		assertTrue(effect.parameterIds.all { id -> shown.parameters.any { it.id.raw == id } })
		val current =
			p.copy(rig = p.rig.copy(puppet = shown), config = p.config.copy(rigEdits = off))
		val ir = RigIrCompiler.compile(current)
		assertTrue(effect.parameterIds.none { id -> ir.parameters.any { it.id == id } })
		assertEquals(base.parameters.map { it.id.raw }, ir.parameters.map { it.id })
		assertTrue(ir.physics.groups.none { it.id.startsWith(effect.id) })
		assertFalse(off.interiorEffects.single().enabled)
		assertEquals(effect.settings, off.interiorEffects.single().settings)
		val exported =
			PSD2LivePipeline().export(current, "inactive.psd", directory.resolve("inactive-model"))
		val diagnostic =
			exported.exportedFiles.single { it.path.fileName.toString().endsWith(".psd2live.json") }
		val report =
			Json.parseToJsonElement(java.nio.file.Files.readString(diagnostic.path)).jsonObject
		assertEquals(
			ir.parameters.size,
			report.getValue("summary").jsonObject.getValue("parameters").jsonPrimitive.int,
			"export diagnostics must count the exported model, not retained editor-only definitions",
		)
	}

	@Test
	fun deletionRetainsParametersUsedByOtherPhysicsAndCanBeReplayedFromTheJournal() {
		val p = preview()
		val base = p.authored.rig.puppet
		val d = base.drawables.first { it.mesh != null }
		val overlay =
			NativeInteriorAuthoring.put(
				p,
				RigEditOverlay(),
				request(d.id.raw, parameters = listOf("", "")),
			)
		val effect = overlay.interiorEffects.single()
		val plain = InteriorLifecycle.delete(p, overlay, "mesh:${d.id.raw}")
		assertTrue(plain.interiorEffects.isEmpty())
		val cleared = plain.applyTo(p.baseRig.puppet)
		assertTrue(effect.parameterIds.none { id -> cleared.parameters.any { it.id.raw == id } })
		val kept = effect.generated.first().id.raw
		val custom =
			effect.groups
				.first()
				.copy(
					id = "IndependentPhysics",
					inputs = listOf(PhysicsInput(kept)),
					outputs = listOf(PhysicsOutput(base.parameters.first().id.raw)),
				)
		val shared =
			overlay.copy(
				physicsEdits = listOf(custom, effect.groups.first()),
				disabledPhysicsIds = setOf(effect.groups.first().id),
			)
		val removed = InteriorLifecycle.delete(p, shared, d.id.raw)
		assertEquals(listOf(custom), removed.physicsEdits)
		assertTrue(removed.disabledPhysicsIds.isEmpty())
		val replayed = removed.applyTo(p.baseRig.puppet)
		assertTrue(replayed.parameters.any { it.id.raw == kept })
		assertTrue(replayed.parameters.any { it.id == base.parameters.first().id })
		assertTrue(
			removed.authoringJournal.isNotEmpty(),
			"external references must be adopted into the replayable authored rig",
		)
		assertEquals(shared, shared.copy(), "deletion must not mutate its input")
	}

	@Test
	fun invalidEffectsSkipPhysicsEvenWithTheirParameterDefinitionsAndReportExportLosses() {
		val p = preview()
		val base = p.authored.rig.puppet
		val d = base.drawables.first { it.mesh != null }
		val overlay =
			NativeInteriorAuthoring.put(
				p,
				RigEditOverlay(),
				request(d.id.raw, parameters = listOf("", "")),
			)
		val effect = overlay.interiorEffects.single()
		val original = d.mesh!!
		val changed =
			d.copy(
				mesh =
					DrawableMesh(
						original.positions.copyOf().also { it[0] += .2f },
						original.uvs,
						original.indices,
					)
			)
		val model =
			base.copy(
				parameters = base.parameters + effect.definitions,
				drawables = base.drawables.map { if (it.id == d.id) changed else it },
			)
		val ownedOverride = overlay.copy(physicsEdits = listOf(effect.groups.first()))
		val config = p.config.copy(rigEdits = ownedOverride)
		assertNotNull(effect.issue(model))
		assertTrue(
			PhysicsCatalog.active(
					p.analysis,
					config,
					model.parameters.mapTo(HashSet()) { it.id.raw },
					model,
				)
				.none { it.id in effect.groups.map { it.id } }
		)
		val current = p.copy(rig = p.rig.copy(puppet = model), config = config)
		val ir = RigIrCompiler.compile(current)
		assertTrue(ir.physics.groups.none { it.id.startsWith(effect.id) })
		assertTrue(effect.parameterIds.none { id -> ir.parameters.any { it.id == id } })
		val report =
			ExportService.export(
				current,
				io.github.psd2live.targets.cubism.Moc3Target,
				io.github.psd2live.format.compile.ExportOptions("invalid-interior"),
				directory.resolve("invalid-export"),
			)
		assertTrue(
			report.losses.any {
				it.objectId == d.id.raw &&
					it.feature == io.github.psd2live.format.compile.Feature.BLEND_SHAPES
			}
		)
		assertTrue(InteriorLifecycle.warnings(model, ownedOverride).isNotEmpty())
	}

	@Test
	fun generatorCacheReadsSourceTextureAndNeverRestoresOldFieldsAfterRepaint() {
		val p = preview()
		val base = p.authored.rig.puppet
		val d = base.drawables.first { it.mesh != null }
		val overlay = NativeInteriorAuthoring.put(p, RigEditOverlay(), request(d.id.raw))
		val effect = overlay.interiorEffects.single()
		GeneratorReuse.clear()
		val first = DocumentGenerators.generate(base, overlay)
		assertTrue(first.drawables.single { it.id == d.id }.blendShapes.isNotEmpty())
		val repaint =
			base.copy(
				sources =
					base.sources.map { source ->
						source.copy(
							layers =
								source.layers.map {
									it.copy(contentHash = "repainted-${it.contentHash}")
								}
						)
					}
			)
		val misses = GeneratorReuse.misses
		val next = DocumentGenerators.generate(repaint, overlay)
		assertTrue(GeneratorReuse.misses > misses)
		assertEquals(d.blendShapes, next.drawables.single { it.id == d.id }.blendShapes)
		assertEquals("interior.issue.source", effect.issue(repaint))
		val again = DocumentGenerators.generate(base, overlay)
		assertEquals(
			first.drawables.single { it.id == d.id }.blendShapes,
			again.drawables.single { it.id == d.id }.blendShapes,
		)
	}

	@Test
	fun mcpSchemasDescribeNestedFieldsAndConfigurationRoundtripsPartialUpdates() {
		val p = preview()
		val d = p.authored.rig.puppet.drawables.first { it.mesh != null }
		val old = NativeInteriorAuthoring.put(p, RigEditOverlay(), request(d.id.raw, false))
		val updated =
			NativeInteriorAuthoring.put(
					p,
					old,
					buildJsonObject {
						put("target", d.id.raw)
						putJsonObject("settings") { put("contour", .8f) }
						putJsonObject("motion") { put("responseSeconds", .25f) }
					},
				)
				.interiorEffects
				.single()
		assertEquals(.8f, updated.settings.contour)
		assertEquals(old.interiorEffects.single().settings.bulge, updated.settings.bulge)
		assertEquals(.25f, updated.motion.responseSeconds)
		val configuration = JsonObject(updated.configuration() + ("issue" to JsonNull))
		io.github.psd2live.application.validateOperationSchema(
			configuration,
			io.github.psd2live.application.WorkspaceInteriorSchemas.inspected,
			"effect",
		)
		val schema = io.github.psd2live.application.WorkspaceInteriorSchemas.put.toJson()
		val request = buildJsonObject {
			put("state", "state")
			put("target", d.id.raw)
			putJsonObject("settings") { put("contour", .8f) }
		}
		io.github.psd2live.application.validateOperationSchema(request, schema, "request")
		assertFails {
			io.github.psd2live.application.validateOperationSchema(
				JsonObject(request + ("settings" to buildJsonObject { put("contour", 2f) })),
				schema,
				"request",
			)
		}
		assertFalse(
			configuration.containsKey("bindings"),
			"inspection must not encode large shape buffers",
		)
	}

	@Test
	fun deletingAnEffectPreservesAnInputPairedWithAnExistingUserParameter() = runBlocking {
		val p = preview()
		val base = p.authored.rig.puppet
		val d = base.drawables.first { it.mesh != null }
		val overlay =
			NativeInteriorAuthoring.put(
				p,
				RigEditOverlay(),
				request(d.id.raw, false, listOf("", "")),
			)
		val effect = overlay.interiorEffects.single()
		val privateInput = effect.sources.first().id
		val linked = base.parameterLinks.flatMapTo(HashSet()) { listOf(it.horizontal, it.vertical) }
		val shared = base.parameters.first { it.id !in linked }.id
		val link = buildJsonObject {
			put("action", "link")
			put("kind", "parameter")
			put("id", privateInput.raw)
			put("partner_id", shared.raw)
			put("linked", true)
		}
		for (journal in listOf(false, true)) {
			val paired =
				if (journal)
					overlay.copy(
						authoringJournal =
							listOf(
								buildJsonObject {
									put("op", "structure")
									put("edits", JsonArray(listOf(link)))
								}
							)
					)
				else overlay.copy(structureEdits = listOf(link))
			val shown = paired.applyTo(p.baseRig.puppet)
			assertTrue(
				shown.parameterLinks.any { it.horizontal == privateInput && it.vertical == shared }
			)
			val deleted = InteriorLifecycle.delete(p, paired, d.id.raw)
			val restored = deleted.applyTo(p.baseRig.puppet)
			assertTrue(restored.parameters.any { it.id == shared })
			assertTrue(
				restored.parameters.any { it.id == privateInput },
				"journal=$journal: a user-paired input must be retained",
			)
			assertTrue(
				restored.parameterLinks.any {
					it.horizontal == privateInput && it.vertical == shared
				},
				"journal=$journal: deferred pairing must survive removal",
			)
			val unlink = buildJsonObject {
				put("op", "structure")
				put(
					"edits",
					JsonArray(listOf(JsonObject(link + ("linked" to JsonPrimitive(false))))),
				)
			}
			val unlinked =
				deleted
					.copy(authoringJournal = deleted.authoringJournal + unlink)
					.applyTo(p.baseRig.puppet)
			assertTrue(
				unlinked.parameterLinks.none {
					it.horizontal == privateInput && it.vertical == shared
				},
				"new unlink must run after the retained pairing",
			)
			val authored = deleted.authored(p.baseRig.puppet)
			val checkpoint =
				RigCheckpoint.encode(
					AuthoredRig(p.baseRig.copy(puppet = authored), emptyList()),
					"retained-panel-test",
				)
			val checkpointed =
				deleted.copy(authoringJournal = deleted.authoringJournal + checkpoint)
			val unlinkedAfterCheckpoint =
				checkpointed
					.copy(authoringJournal = checkpointed.authoringJournal + unlink)
					.applyTo(p.baseRig.puppet)
			assertTrue(
				unlinkedAfterCheckpoint.parameterLinks.none {
					it.horizontal == privateInput && it.vertical == shared
				}
			)
			val remove = buildJsonObject {
				put("op", "structure")
				put(
					"edits",
					JsonArray(
						listOf(
							buildJsonObject {
								put("action", "delete")
								put("kind", "parameter")
								put("id", privateInput.raw)
							}
						)
					),
				)
			}
			val originalDefinition = effect.sources.first()
			val recreate = buildJsonObject {
				put("op", "structure")
				put(
					"edits",
					JsonArray(
						listOf(
							buildJsonObject {
								put("action", "create")
								put("kind", "parameter")
								put("id", privateInput.raw)
								put("name", "New user input")
								put("min", originalDefinition.min)
								put("max", originalDefinition.max)
								put("default", originalDefinition.default)
								put("parameter_kind", originalDefinition.kind.name)
							}
						)
					),
				)
			}
			val recreated =
				checkpointed
					.copy(authoringJournal = checkpointed.authoringJournal + remove + recreate)
					.applyTo(p.baseRig.puppet)
			assertEquals(
				"New user input",
				recreated.parameters.single { it.id == privateInput }.name,
			)
			assertTrue(
				recreated.parameterLinks.none {
					it.horizontal == privateInput || it.vertical == privateInput
				},
				"a deleted parameter lifetime must not resurrect its old pairing",
			)

			val beforeDocument =
				WorkspaceDocument(
					p.analysis.source as WorkspaceSourceArt,
					emptyMap(),
					emptySet(),
					emptyMap(),
					emptyMap(),
					paired,
				)
			val afterDocument = beforeDocument.copy(rigEdits = deleted)
			val beforeRevision = WorkspaceRevisions.of(beforeDocument)
			val history = WorkspaceHistoryTree(beforeDocument, beforeRevision, beforeRevision)
			val beforeNode = history.head().node.id
			val afterRevision = WorkspaceRevisions.of(afterDocument)
			history.commit(
				beforeNode,
				afterDocument,
				afterRevision,
				afterRevision,
				"Delete interior",
				"user",
			)
			val store = WorkspaceStore(directory.resolve("paired-store-$journal"))
			store.persistHistory("paired", history.state())
			val archive = directory.resolve("paired-$journal.psd2live")
			ProjectRepository(writeHeadCache = false)
				.save(
					ProjectSaveCapture(
						"paired",
						history.state(),
						JsonObject(emptyMap()),
						null,
						store,
					),
					archive,
				)
			ProjectRepository(writeHeadCache = false).open(archive).use { opened ->
				val loaded = opened.history.head().snapshot
				assertEquals(afterRevision, WorkspaceRevisions.of(loaded))
				assertEquals(
					restored.parameterLinks,
					loaded.rigEdits.applyTo(p.baseRig.puppet).parameterLinks,
				)
				val undone =
					opened.history
						.selectionAt(beforeNode)
						.snapshot
						.rigEdits
						.applyTo(p.baseRig.puppet)
				assertEquals(shown.parameterLinks, undone.parameterLinks)
			}
		}
	}
}
