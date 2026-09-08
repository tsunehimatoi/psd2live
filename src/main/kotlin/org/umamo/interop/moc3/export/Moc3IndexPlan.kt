package org.umamo.interop.moc3.export

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * Every ordering and index decision an export makes, resolved ONCE.
 *
 * A `.moc3` is entirely index-addressed - a drawable names its part and its deformer by list
 * position, a deformer names its parent, a render-order child names a drawable.  So the file
 * order of each object list is not a detail: it is the addressing scheme, and every lowering has
 * to agree on it exactly.  Resolving it here means a lowering can only ask "what index is this
 * id?" and never invent an answer.
 *
 * Two orderings are load-bearing rather than arbitrary.  Parts and deformers are emitted
 * PARENT-BEFORE-CHILD because every corpus file is written that way (parent index < own index,
 * universally, v1 through v6) and the runtime's own single forward pass over the deformer list
 * depends on it - a child seen before its parent would read an unresolved transform.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class Moc3IndexPlan private constructor(
	val parts: List<Part>,
	val deformers: List<Deformer>,
	val drawables: List<Drawable>,
	val parameters: List<Parameter>,
	private val partIndexById: Map<PartId, Int>,
	private val deformerIndexById: Map<DeformerId, Int>,
	private val drawableIndexById: Map<DrawableId, Int>,
	private val parameterIndexById: Map<ParameterId, Int>,
) {
	/** The file index of [id], or -1 when it names nothing (the moc's own "absent" encoding). */
	fun partIndex(id: PartId?): Int = id?.let { partIndexById[it] } ?: -1

	/** The file index of [id], or -1 when it names nothing. */
	fun deformerIndex(id: DeformerId?): Int = id?.let { deformerIndexById[it] } ?: -1

	/** The file index of [id], or -1 when it names nothing. */
	fun drawableIndex(id: DrawableId?): Int = id?.let { drawableIndexById[it] } ?: -1

	/** The file index of [id], or -1 when it names nothing. */
	fun parameterIndex(id: ParameterId?): Int = id?.let { parameterIndexById[it] } ?: -1

	companion object {
		/**
		 * Resolves the file order of every object list in [puppet].
		 *
		 * @param PuppetModel puppet              The rig to export.
		 * @param List        exportableDrawables The drawables that will actually be written.
		 * @param Set         exportableParts     The parts that will actually be written.
		 * @return Moc3IndexPlan The resolved plan.
		 */
		fun of(
			puppet: PuppetModel,
			exportableDrawables: List<Drawable> = puppet.drawables,
			exportableParts: Set<PartId> = puppet.parts.mapTo(HashSet()) { part -> part.id },
		): Moc3IndexPlan {
			// Same reason the drawable list is filtered: a part the export omits must not hold an index,
			// or every part after it - and every drawable's owning-part reference - names the wrong one.
			val orderedParts = partsParentFirst(puppet).filter { part -> part.id in exportableParts }
			val orderedDeformers = deformersParentFirst(puppet.deformers)
			return Moc3IndexPlan(
				parts = orderedParts,
				deformers = orderedDeformers,
				// Drawables and parameters carry no intra-list dependency, so their runtime order is
				// already a valid file order and keeping it makes the export's output stable.
				//
				// The list is the drawables that will ACTUALLY be written, not every drawable the rig has.
				// A MOC3 addresses drawables by position, so if the export drops one - a mesh-less drawable,
				// say - and the plan still counted it, every index after it would name the wrong drawable
				// and every mask reference would silently shift.
				drawables = exportableDrawables,
				parameters = puppet.parameters,
				partIndexById = orderedParts.withIndex().associate { (index, part) -> part.id to index },
				deformerIndexById =
					orderedDeformers.withIndex().associate { (index, deformer) -> deformer.id to index },
				drawableIndexById =
					exportableDrawables.withIndex().associate { (index, drawable) -> drawable.id to index },
				parameterIndexById =
					puppet.parameters.withIndex().associate { (index, parameter) -> parameter.id to index },
			)
		}

		/**
		 * The parts in org-tree pre-order, so a parent always precedes its children.
		 *
		 * Walks the TREE rather than sorting the flat list, because the tree is the authoritative
		 * hierarchy - the flat [PuppetModel.parts] is a lookup list whose order carries no meaning.  A
		 * part the tree never reaches (an orphan) is appended so the export cannot silently drop it.
		 *
		 * @param PuppetModel puppet The rig.
		 * @return List<Part> The parts in file order.
		 */
		private fun partsParentFirst(puppet: PuppetModel): List<Part> {
			val partById = puppet.parts.associateBy { part -> part.id }
			val ordered = ArrayList<Part>(puppet.parts.size)
			val visited = HashSet<PartId>()

			fun walk(children: List<OrgChild>) {
				for (child in children) {
					if (child !is OrgChild.Part) {
						continue
					}
					val part = partById[child.id] ?: continue
					// The guard also breaks a malformed cycle rather than recursing forever.
					if (!visited.add(part.id)) {
						continue
					}
					ordered.add(part)
					walk(part.children)
				}
			}
			walk(puppet.rootChildren)
			for (part in puppet.parts) {
				if (visited.add(part.id)) {
					ordered.add(part)
				}
			}
			return ordered
		}

		/**
		 * The deformers ordered so a parent always precedes its children.
		 *
		 * A stable depth-first emit rather than a general topological sort: it preserves the input's
		 * relative order among siblings, which keeps the output stable across runs and keeps a
		 * round-tripped file close to its source instead of arbitrarily permuted.
		 *
		 * @param List deformers The rig's deformers.
		 * @return List<Deformer> The deformers in file order.
		 */
		private fun deformersParentFirst(deformers: List<Deformer>): List<Deformer> {
			val byId = deformers.associateBy { deformer -> deformer.id }
			val childrenByParent = deformers.groupBy { deformer -> deformer.parent }
			val ordered = ArrayList<Deformer>(deformers.size)
			val visited = HashSet<DeformerId>()

			fun walk(deformer: Deformer) {
				if (!visited.add(deformer.id)) {
					return
				}
				ordered.add(deformer)
				childrenByParent[deformer.id]?.forEach(::walk)
			}
			// Roots are deformers with no parent, plus any whose parent id does not resolve - an
			// unresolvable parent is treated as root exactly as the import treats it.
			for (deformer in deformers) {
				if (deformer.parent == null || byId[deformer.parent] == null) {
					walk(deformer)
				}
			}
			// Anything still unvisited sits in a parent cycle; emit it so nothing is dropped.
			for (deformer in deformers) {
				walk(deformer)
			}
			return ordered
		}
	}
}