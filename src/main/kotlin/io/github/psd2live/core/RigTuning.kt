package io.github.psd2live.core

/**
 * How far the generated rig moves each part at the parameters' full values, before the head turn and body
 * strengths scale them: the head's turn on the face surface, the head shell, the face contour and the
 * feature displacement, each feature's redraw ([NinePoseFaceRig]), the gaze, blink, iris and brow keys,
 * the mouth, the hair, the face following the lean, and how far [BodyStance] turns, opens, leans and resizes
 * the body, with the Body Z lean and the breath ([RigBuilder.bodySecondaryWarpPoint]).
 *
 * Every value is in the unit the model presets show it in - degrees, percent or a multiple of the torso's
 * length - so the panel, the MCP `settings` tool and the project file all read the same numbers. A percent
 * is of the part the value's description names: the face's radius, a feature's own size, the head, the
 * torso. [fields] lists them in the panel's order with their groups, ranges and whether the panel folds
 * them under Advanced.
 */
data class RigTuning(
	// Head
	/** Angle X: the face surface's roll across the turn, percent of the face's half width. */
	val faceTurn: Float = 11.2f,
	/** Angle X: the turn every point of the face shares, percent of its half width. */
	val faceTurnBase: Float = 2f,
	/** Angle Y: the face's rows spread looking up and gather looking down, percent of its half height. */
	val faceTilt: Float = 6.2f,
	/** Angle Y: the whole face moved up or down, percent of its half height. */
	val faceLift: Float = 1.8f,
	/** Angle Y: the rows bowed into a ^ looking up and a V looking down, percent of its half height. */
	val faceArch: Float = 4f,
	/** The corner poses' own correction over Angle X plus Angle Y, percent. */
	val faceCorner: Float = 100f,
	/** How far the head layers outside the skin follow the turn as one piece, percent of the face's half size. */
	val faceOutsideFollow: Float = 1.8f,
	/** Angle X at which the face's turn eases: smaller turns more early and less late, degrees. */
	val yawEase: Float = 32f,
	/** Angle Y at which the face's tilt eases, degrees. */
	val pitchEase: Float = 22f,
	/** The corner poses' tilt of the eyes, brows and mouth line, percent. */
	val featurePlane: Float = 5f,
	/** Angle X: the head shell's shift, percent of the head's width. */
	val headShellTurn: Float = 0.9f,
	/** Angle X: the crown's extra shift over the shell's, percent of the head's width. */
	val headShellCrown: Float = 0.4f,
	/** Angle Y: the head shell's shift, percent of the head's height. */
	val headShellTilt: Float = 0.8f,
	/** Angle X: the far cheek's dent inward, percent of the face's width. */
	val faceContour: Float = 1.8f,
	/** Body lean: degrees of Angle Y the face turns down leaning in. */
	val faceLeanDown: Float = 12f,
	/** Body lean: degrees of Angle Y the face turns up leaning back. */
	val faceLeanUp: Float = 8f,
	/** Feature displacement, Angle X: the features' shift toward the turn, percent of the face's width. */
	val displacementShift: Float = 5.5f,
	/** Feature displacement, Angle X: the features drawn closer together, percent. */
	val displacementNarrow: Float = 15f,
	/** Feature displacement, Angle Y: the features' shift, percent of the face's height. */
	val displacementTilt: Float = 5f,
	/** Feature displacement, corner poses: degrees the features turn. */
	val displacementTwist: Float = 3f,

	// Eyes
	/** Angle X: the far eye drawn narrower, percent. */
	val eyeFarShrink: Float = 8.5f,
	/** Angle X: the near eye drawn wider, percent. */
	val eyeNearGrow: Float = 1.2f,
	/** Angle X: the eyes' shift toward the turn, percent of the face's half width. */
	val eyeShift: Float = 2f,
	/** Angle Y: the eyes' shift, percent of their height. */
	val eyeTilt: Float = 8f,
	/** Angle Y: the lids' extra shift at the eyes' middle, percent of their height. */
	val eyeLidCurve: Float = 3f,
	/** Angle Y: the eyes drawn taller looking up and shorter looking down, percent. */
	val eyeTiltStretch: Float = 7f,
	/** Angle X: the iris's shift against the turn, percent of its width. */
	val irisShift: Float = 5.5f,
	/** Angle X: the far iris drawn wider against the eye white narrowing round it, percent; the near one a fifth of it. */
	val irisKeepWidth: Float = 5f,
	/** Angle Y: the iris's shift, percent of its height. */
	val irisTilt: Float = 3.5f,
	/** Eyeball X: the iris's travel, percent of its warp's width. */
	val gazeX: Float = 10f,
	/** Eyeball Y: the iris's travel, percent of its warp's height. */
	val gazeY: Float = 8.5f,
	/** Eye open at 0: how far the closed lid's curve sags at its middle, percent of the eye white's height. */
	val blinkDepth: Float = 38f,
	/** Eye open at 0: how low the closed lid's corners sit, percent of the eye white's height. */
	val blinkCorner: Float = 34f,
	/** Eye open at 0: the lash's thickness kept, percent. */
	val eyelashSquash: Float = 88f,
	/** Eyeball form: the iris drawn taller, percent. */
	val irisJellyStretch: Float = 11f,
	/** Eyeball form: the iris drawn narrower as it stretches, percent. */
	val irisJellySquash: Float = 4.5f,

	// Brows
	/** Brow Y: the brow's travel, percent of its height. */
	val browLift: Float = 12f,
	/** Angle X: the far brow drawn narrower, percent. */
	val browFarShrink: Float = 10.5f,
	/** Angle X: the near brow drawn wider, percent. */
	val browNearGrow: Float = 1.8f,
	/** Angle X: the brows' shift toward the turn, percent of the face's half width. */
	val browShift: Float = 1.8f,
	/** Angle Y: the brows' shift, percent of their height. */
	val browTilt: Float = 10.5f,

	// Mouth
	/** Mouth form: the corners raised or lowered, percent of the mouth's height. */
	val mouthSmile: Float = 10.5f,
	/** Mouth form: the whole mouth raised or lowered, percent of its height. */
	val mouthSmileBase: Float = 1.8f,
	/** Mouth form: the mouth drawn wider smiling, narrower frowning, percent. */
	val mouthFormWiden: Float = 7f,
	/** Mouth open at 0: the mouth drawn narrower, percent. */
	val mouthClosedNarrow: Float = 8f,
	/** Mouth open at 0: where the lips meet, percent of the mouth's height from its top. */
	val mouthSeam: Float = 48f,
	/** Mouth open: the teeth and tongue fully shown from this opening on, percent. */
	val teethFade: Float = 15f,
	/** Angle X: the mouth's shift toward the turn, percent of the face's half width. */
	val mouthShift: Float = 5.8f,
	/** Angle X: how far the far corner lags behind, percent of the mouth's width. */
	val mouthCornerLag: Float = 8.5f,
	/** Angle X: how far the near corner lags behind, percent of the mouth's width. */
	val mouthNearLag: Float = 3.5f,
	/** Angle X: the mouth's middle lowered, percent of its height. */
	val mouthTurnArch: Float = 6f,
	/** Angle Y: the mouth's shift, percent of its height. */
	val mouthTilt: Float = 13f,

	// Nose and ears
	/** Angle X: the nose's shift toward the turn, percent of the face's half width. */
	val noseShift: Float = 10.5f,
	/** Angle Y: the nose's shift, percent of its height. */
	val noseTilt: Float = 10f,
	/** Angle X: the far ear faded, percent. */
	val earFade: Float = 48f,
	/** Angle X: the far ear drawn narrower, percent. */
	val earFarShrink: Float = 28f,
	/** Angle X: the ears' shift against the turn, percent of the face's half width. */
	val earShift: Float = 3.5f,

	// Hair
	/** Angle X: the front hair's shift against the turn, percent of the head's width. */
	val frontHairTurn: Float = 2f,
	/** Angle Y: the front hair's shift against the tilt, percent of the head's height; negative goes with it. */
	val frontHairTilt: Float = 0.6f,
	/** Angle X: the front hair's middle drawn toward the turn, percent. */
	val frontHairPerspective: Float = 10f,
	/** Angle X: the back hair's shift against the turn, percent of the head's width. */
	val backHairTurn: Float = 1.8f,
	/** Angle Y: the back hair's shift against the tilt, percent of the head's height; negative goes with it. */
	val backHairTilt: Float = -0.4f,
	/** Front hair sway: the tips' swing, percent of the hair's smaller side. */
	val frontHairSway: Float = 12f,
	/** Front hair sway: the tips lifted at a full swing, percent of the hair's smaller side. */
	val frontHairCurl: Float = 3f,
	/** Back hair sway: the tips' swing, percent of the hair's smaller side. */
	val backHairSway: Float = 10f,
	/** Back hair sway: the tips lifted at a full swing, percent of the hair's smaller side. */
	val backHairCurl: Float = 2.5f,
	/** Angle Z: how much of the head's tilt hair below the chin turns back, percent at its tips; long hair bends instead of swinging out whole. */
	val hairRollHold: Float = 80f,

	// Body
	/** Body X: degrees the upper body turns. */
	val turnDegrees: Float = 12f,
	/** Body X: degrees each arm swings about its shoulder against the turn, so the hand trails the body. */
	val armSwingDegrees: Float = 4f,
	/** Body X: the hips sideways, percent of the legs' length. */
	val hipShift: Float = 2.5f,
	/** Body X: how far below the waist the turn eases out, in torso lengths. */
	val turnFade: Float = 0.5f,
	/** Body Y down: the hips lowered, percent of the legs' length. */
	val sink: Float = 3.5f,
	/** Body Y up: the hips raised, percent of the legs' length. */
	val rise: Float = 1.2f,
	/** Body Y down: degrees the bending knees turn in, beyond [kneesInRest]. */
	val kneesIn: Float = 35f,
	/** Degrees the knees turn in as they bend, however little the body sinks. */
	val kneesInRest: Float = 10f,
	/** Body Y down: the back shortened, percent of the torso's length. */
	val openShorten: Float = 1.5f,
	/** Body Y down: the shoulders opened, percent of their width. */
	val openWiden: Float = 2f,
	/** Body Y up: the back stretched, percent of the torso's length. */
	val standStretch: Float = 1.5f,
	/** Body Y up: the shoulders drawn in, percent of their width. */
	val standNarrow: Float = 1.5f,
	/** Body lean: degrees the body bows leaning in. */
	val leanDegrees: Float = 28f,
	/** Body lean: degrees the body bows leaning back. */
	val leanBackDegrees: Float = 12f,
	/** Body lean: the share of the bow the pelvis takes about the hip joints, percent. */
	val pelvisShare: Float = 30f,
	/** Proportion at full chibi: the head drawn larger, percent. */
	val headGrow: Float = 15f,
	/** Proportion at full chibi: the chest drawn wider at the shoulders, percent. */
	val chestGrow: Float = 8f,
	/** Proportion at full chibi: the torso and the arms drawn shorter, percent. */
	val bodyShrink: Float = 6f,
	/** Proportion at full chibi: the legs and the skirt drawn shorter, percent. */
	val legShrink: Float = 10f,
	/** Body Z: degrees the upper body leans sideways about the waist. */
	val bodyZDegrees: Float = 3f,
	/** Breath: the shoulders lifted at a full breath, percent of the torso's length. */
	val breathLift: Float = 3f,
	/** Breath: the chest widened at a full breath, percent of its width. */
	val breathWiden: Float = 2.5f,
	/** The torso's depth front to back, percent of its width; the turn and the lean read it as a solid this deep. */
	val torsoDepth: Float = 70f,
	/** How far in front of the torso the turn is seen from, in torso lengths: nearer draws the near side larger. */
	val turnCameraDistance: Float = 3f,
	/** How far in front of the torso the lean is seen from, in torso lengths: nearer draws a lean in larger. */
	val leanCameraDistance: Float = 9f,
) {
	/** The panel's groups, in order, each in the rig area ([io.github.psd2live.core.recipe.RigArea]) whose card shows it. */
	enum class Group(val area: String) {
		HEAD("face"), EYES("eye"), BROWS("brow"), MOUTH("mouth"), NOSE_EARS("face"), HAIR("hair"),
		TURN("body"), OPEN("body"), LEAN("body"), PROPORTION("body"), BREATH("body"), DEPTH("body"),
	}

	/** The unit a value is shown in. */
	enum class Unit { DEGREES, PERCENT, TORSO_LENGTHS }

	/**
	 * One value: its [id] in the project file and the MCP tool, its [group], [unit] and [range], its [step]
	 * in the panel, and whether the panel folds it under Advanced.
	 */
	class Field(
		val id: String,
		val group: Group,
		val unit: Unit,
		val range: ClosedFloatingPointRange<Float>,
		val step: Float,
		val advanced: Boolean,
		val get: (RigTuning) -> Float,
		val set: (RigTuning, Float) -> RigTuning,
	) {
		val default: Float get() = get(RigTuning())
	}

	/** This tuning with the value [id] set to [value], clamped to its range. */
	fun with(id: String, value: Float): RigTuning {
		val field = requireNotNull(fieldById[id]) { "Unknown rig value: $id" }
		require(value.isFinite()) { "$id must be a number" }
		return field.set(this, value.coerceIn(field.range))
	}

	/** Every value by its id, in the panel's order. */
	fun toMap(): Map<String, Float> = fields.associateTo(LinkedHashMap()) { it.id to it.get(this) }

	companion object {
		/**
		 * The values a rig module or base ([io.github.psd2live.core.recipe.RigModules]) draws its own way, by the module's
		 * or base's id: while it is built they move nothing. The nine-pose turn of the face, its features and the hair
		 * gives way to the turn's style (`face.depth-turn`); the drawn blink, brow lift and mouth shapes to the fitted
		 * eyes, brows and mouth; the one sway of the front and back hair to the hair's chains. A rig without them (a
		 * project saved before the modules, or with them off) still reads them.
		 */
		val replacedBy: Map<String, String> = listOf(
			"faceTurn", "faceTilt", "faceTurnBase", "faceLift", "faceArch", "faceCorner", "faceOutsideFollow", "yawEase", "pitchEase",
			"featurePlane", "faceContour", "displacementShift", "displacementNarrow", "displacementTilt", "displacementTwist",
			"eyeFarShrink", "eyeNearGrow", "eyeShift", "eyeTilt", "eyeLidCurve", "eyeTiltStretch", "irisShift", "irisKeepWidth", "irisTilt",
			"browFarShrink", "browNearGrow", "browShift", "browTilt",
			"mouthShift", "mouthCornerLag", "mouthNearLag", "mouthTurnArch", "mouthTilt",
			"noseShift", "noseTilt", "earFarShrink", "earShift",
			"frontHairTurn", "frontHairTilt", "frontHairPerspective", "backHairTurn", "backHairTilt",
		).associateWith { "face.depth-turn" } +
			listOf("blinkDepth", "blinkCorner", "eyelashSquash").associateWith { "eye/fitted" } +
			mapOf("browLift" to "brow/fitted") +
			listOf("mouthSmile", "mouthFormWiden", "mouthClosedNarrow", "mouthSmileBase", "mouthSeam").associateWith { "mouth/fitted" } +
			listOf("frontHairSway", "backHairSway", "frontHairCurl", "backHairCurl").associateWith { "hair/chains" }

		private const val COMMON = false
		private const val ADVANCED = true
		private val D = Unit.DEGREES
		private val P = Unit.PERCENT
		private val L = Unit.TORSO_LENGTHS

		val fields: List<Field> = listOf(
			Field("faceTurn", Group.HEAD, P, 0f..30f, 0.1f, COMMON, { it.faceTurn }) { t, v -> t.copy(faceTurn = v) },
			Field("faceTilt", Group.HEAD, P, 0f..20f, 0.1f, COMMON, { it.faceTilt }) { t, v -> t.copy(faceTilt = v) },
			Field("faceLeanDown", Group.HEAD, D, 0f..30f, 0.5f, COMMON, { it.faceLeanDown }) { t, v -> t.copy(faceLeanDown = v) },
			Field("faceLeanUp", Group.HEAD, D, 0f..30f, 0.5f, COMMON, { it.faceLeanUp }) { t, v -> t.copy(faceLeanUp = v) },
			Field("faceTurnBase", Group.HEAD, P, 0f..10f, 0.1f, ADVANCED, { it.faceTurnBase }) { t, v -> t.copy(faceTurnBase = v) },
			Field("faceLift", Group.HEAD, P, 0f..10f, 0.1f, ADVANCED, { it.faceLift }) { t, v -> t.copy(faceLift = v) },
			Field("faceArch", Group.HEAD, P, 0f..15f, 0.1f, ADVANCED, { it.faceArch }) { t, v -> t.copy(faceArch = v) },
			Field("faceCorner", Group.HEAD, P, 0f..200f, 1f, ADVANCED, { it.faceCorner }) { t, v -> t.copy(faceCorner = v) },
			Field("faceOutsideFollow", Group.HEAD, P, 0f..10f, 0.1f, ADVANCED, { it.faceOutsideFollow }) { t, v -> t.copy(faceOutsideFollow = v) },
			Field("yawEase", Group.HEAD, D, 10f..90f, 1f, ADVANCED, { it.yawEase }) { t, v -> t.copy(yawEase = v) },
			Field("pitchEase", Group.HEAD, D, 10f..90f, 1f, ADVANCED, { it.pitchEase }) { t, v -> t.copy(pitchEase = v) },
			Field("featurePlane", Group.HEAD, P, 0f..15f, 0.1f, ADVANCED, { it.featurePlane }) { t, v -> t.copy(featurePlane = v) },
			Field("headShellTurn", Group.HEAD, P, 0f..5f, 0.1f, ADVANCED, { it.headShellTurn }) { t, v -> t.copy(headShellTurn = v) },
			Field("headShellCrown", Group.HEAD, P, 0f..5f, 0.1f, ADVANCED, { it.headShellCrown }) { t, v -> t.copy(headShellCrown = v) },
			Field("headShellTilt", Group.HEAD, P, 0f..5f, 0.1f, ADVANCED, { it.headShellTilt }) { t, v -> t.copy(headShellTilt = v) },
			Field("faceContour", Group.HEAD, P, 0f..8f, 0.1f, ADVANCED, { it.faceContour }) { t, v -> t.copy(faceContour = v) },
			Field("displacementShift", Group.HEAD, P, 0f..15f, 0.1f, ADVANCED, { it.displacementShift }) { t, v -> t.copy(displacementShift = v) },
			Field("displacementNarrow", Group.HEAD, P, 0f..40f, 0.5f, ADVANCED, { it.displacementNarrow }) { t, v -> t.copy(displacementNarrow = v) },
			Field("displacementTilt", Group.HEAD, P, 0f..15f, 0.1f, ADVANCED, { it.displacementTilt }) { t, v -> t.copy(displacementTilt = v) },
			Field("displacementTwist", Group.HEAD, D, 0f..10f, 0.1f, ADVANCED, { it.displacementTwist }) { t, v -> t.copy(displacementTwist = v) },

			Field("eyeFarShrink", Group.EYES, P, 0f..30f, 0.1f, COMMON, { it.eyeFarShrink }) { t, v -> t.copy(eyeFarShrink = v) },
			Field("gazeX", Group.EYES, P, 0f..30f, 0.1f, COMMON, { it.gazeX }) { t, v -> t.copy(gazeX = v) },
			Field("gazeY", Group.EYES, P, 0f..30f, 0.1f, COMMON, { it.gazeY }) { t, v -> t.copy(gazeY = v) },
			Field("blinkDepth", Group.EYES, P, 0f..80f, 0.5f, COMMON, { it.blinkDepth }) { t, v -> t.copy(blinkDepth = v) },
			Field("eyeNearGrow", Group.EYES, P, 0f..10f, 0.1f, ADVANCED, { it.eyeNearGrow }) { t, v -> t.copy(eyeNearGrow = v) },
			Field("eyeShift", Group.EYES, P, 0f..10f, 0.1f, ADVANCED, { it.eyeShift }) { t, v -> t.copy(eyeShift = v) },
			Field("eyeTilt", Group.EYES, P, 0f..20f, 0.1f, ADVANCED, { it.eyeTilt }) { t, v -> t.copy(eyeTilt = v) },
			Field("eyeLidCurve", Group.EYES, P, 0f..10f, 0.1f, ADVANCED, { it.eyeLidCurve }) { t, v -> t.copy(eyeLidCurve = v) },
			Field("eyeTiltStretch", Group.EYES, P, 0f..20f, 0.1f, ADVANCED, { it.eyeTiltStretch }) { t, v -> t.copy(eyeTiltStretch = v) },
			Field("irisShift", Group.EYES, P, 0f..15f, 0.1f, ADVANCED, { it.irisShift }) { t, v -> t.copy(irisShift = v) },
			Field("irisKeepWidth", Group.EYES, P, 0f..20f, 0.1f, ADVANCED, { it.irisKeepWidth }) { t, v -> t.copy(irisKeepWidth = v) },
			Field("irisTilt", Group.EYES, P, 0f..10f, 0.1f, ADVANCED, { it.irisTilt }) { t, v -> t.copy(irisTilt = v) },
			Field("blinkCorner", Group.EYES, P, 0f..80f, 0.5f, ADVANCED, { it.blinkCorner }) { t, v -> t.copy(blinkCorner = v) },
			Field("eyelashSquash", Group.EYES, P, 0f..150f, 1f, ADVANCED, { it.eyelashSquash }) { t, v -> t.copy(eyelashSquash = v) },
			Field("irisJellyStretch", Group.EYES, P, 0f..30f, 0.1f, ADVANCED, { it.irisJellyStretch }) { t, v -> t.copy(irisJellyStretch = v) },
			Field("irisJellySquash", Group.EYES, P, 0f..20f, 0.1f, ADVANCED, { it.irisJellySquash }) { t, v -> t.copy(irisJellySquash = v) },

			Field("browLift", Group.BROWS, P, 0f..40f, 0.5f, COMMON, { it.browLift }) { t, v -> t.copy(browLift = v) },
			Field("browFarShrink", Group.BROWS, P, 0f..30f, 0.1f, ADVANCED, { it.browFarShrink }) { t, v -> t.copy(browFarShrink = v) },
			Field("browNearGrow", Group.BROWS, P, 0f..10f, 0.1f, ADVANCED, { it.browNearGrow }) { t, v -> t.copy(browNearGrow = v) },
			Field("browShift", Group.BROWS, P, 0f..10f, 0.1f, ADVANCED, { it.browShift }) { t, v -> t.copy(browShift = v) },
			Field("browTilt", Group.BROWS, P, 0f..30f, 0.1f, ADVANCED, { it.browTilt }) { t, v -> t.copy(browTilt = v) },

			Field("mouthSmile", Group.MOUTH, P, 0f..30f, 0.1f, COMMON, { it.mouthSmile }) { t, v -> t.copy(mouthSmile = v) },
			Field("mouthFormWiden", Group.MOUTH, P, 0f..20f, 0.1f, COMMON, { it.mouthFormWiden }) { t, v -> t.copy(mouthFormWiden = v) },
			Field("mouthClosedNarrow", Group.MOUTH, P, 0f..30f, 0.1f, COMMON, { it.mouthClosedNarrow }) { t, v -> t.copy(mouthClosedNarrow = v) },
			Field("mouthSmileBase", Group.MOUTH, P, 0f..10f, 0.1f, ADVANCED, { it.mouthSmileBase }) { t, v -> t.copy(mouthSmileBase = v) },
			Field("mouthSeam", Group.MOUTH, P, 20f..80f, 0.5f, ADVANCED, { it.mouthSeam }) { t, v -> t.copy(mouthSeam = v) },
			Field("teethFade", Group.MOUTH, P, 1f..99f, 1f, ADVANCED, { it.teethFade }) { t, v -> t.copy(teethFade = v) },
			Field("mouthShift", Group.MOUTH, P, 0f..15f, 0.1f, ADVANCED, { it.mouthShift }) { t, v -> t.copy(mouthShift = v) },
			Field("mouthCornerLag", Group.MOUTH, P, 0f..20f, 0.1f, ADVANCED, { it.mouthCornerLag }) { t, v -> t.copy(mouthCornerLag = v) },
			Field("mouthNearLag", Group.MOUTH, P, 0f..20f, 0.1f, ADVANCED, { it.mouthNearLag }) { t, v -> t.copy(mouthNearLag = v) },
			Field("mouthTurnArch", Group.MOUTH, P, 0f..20f, 0.1f, ADVANCED, { it.mouthTurnArch }) { t, v -> t.copy(mouthTurnArch = v) },
			Field("mouthTilt", Group.MOUTH, P, 0f..30f, 0.1f, ADVANCED, { it.mouthTilt }) { t, v -> t.copy(mouthTilt = v) },

			Field("noseShift", Group.NOSE_EARS, P, 0f..30f, 0.1f, COMMON, { it.noseShift }) { t, v -> t.copy(noseShift = v) },
			Field("earFade", Group.NOSE_EARS, P, 0f..100f, 1f, COMMON, { it.earFade }) { t, v -> t.copy(earFade = v) },
			Field("noseTilt", Group.NOSE_EARS, P, 0f..30f, 0.1f, ADVANCED, { it.noseTilt }) { t, v -> t.copy(noseTilt = v) },
			Field("earFarShrink", Group.NOSE_EARS, P, 0f..60f, 0.5f, ADVANCED, { it.earFarShrink }) { t, v -> t.copy(earFarShrink = v) },
			Field("earShift", Group.NOSE_EARS, P, 0f..15f, 0.1f, ADVANCED, { it.earShift }) { t, v -> t.copy(earShift = v) },

			Field("frontHairTurn", Group.HAIR, P, 0f..10f, 0.1f, COMMON, { it.frontHairTurn }) { t, v -> t.copy(frontHairTurn = v) },
			Field("backHairTurn", Group.HAIR, P, 0f..10f, 0.1f, COMMON, { it.backHairTurn }) { t, v -> t.copy(backHairTurn = v) },
			Field("frontHairSway", Group.HAIR, P, 0f..40f, 0.5f, COMMON, { it.frontHairSway }) { t, v -> t.copy(frontHairSway = v) },
			Field("backHairSway", Group.HAIR, P, 0f..40f, 0.5f, COMMON, { it.backHairSway }) { t, v -> t.copy(backHairSway = v) },
			Field("frontHairTilt", Group.HAIR, P, -3f..3f, 0.1f, ADVANCED, { it.frontHairTilt }) { t, v -> t.copy(frontHairTilt = v) },
			Field("backHairTilt", Group.HAIR, P, -3f..3f, 0.1f, ADVANCED, { it.backHairTilt }) { t, v -> t.copy(backHairTilt = v) },
			Field("frontHairPerspective", Group.HAIR, P, 0f..30f, 0.5f, ADVANCED, { it.frontHairPerspective }) { t, v -> t.copy(frontHairPerspective = v) },
			Field("frontHairCurl", Group.HAIR, P, 0f..15f, 0.1f, ADVANCED, { it.frontHairCurl }) { t, v -> t.copy(frontHairCurl = v) },
			Field("backHairCurl", Group.HAIR, P, 0f..15f, 0.1f, ADVANCED, { it.backHairCurl }) { t, v -> t.copy(backHairCurl = v) },
			Field("hairRollHold", Group.HAIR, P, 0f..100f, 1f, COMMON, { it.hairRollHold }) { t, v -> t.copy(hairRollHold = v) },

			Field("turnDegrees", Group.TURN, D, 0f..30f, 0.5f, COMMON, { it.turnDegrees }) { t, v -> t.copy(turnDegrees = v) },
			Field("armSwingDegrees", Group.TURN, D, -15f..15f, 0.5f, COMMON, { it.armSwingDegrees }) { t, v -> t.copy(armSwingDegrees = v) },
			Field("hipShift", Group.TURN, P, 0f..10f, 0.1f, COMMON, { it.hipShift }) { t, v -> t.copy(hipShift = v) },
			Field("turnFade", Group.TURN, L, 0.1f..2f, 0.1f, ADVANCED, { it.turnFade }) { t, v -> t.copy(turnFade = v) },
			Field("sink", Group.OPEN, P, 0f..10f, 0.1f, COMMON, { it.sink }) { t, v -> t.copy(sink = v) },
			Field("rise", Group.OPEN, P, 0f..5f, 0.1f, COMMON, { it.rise }) { t, v -> t.copy(rise = v) },
			Field("kneesIn", Group.OPEN, D, 0f..60f, 1f, ADVANCED, { it.kneesIn }) { t, v -> t.copy(kneesIn = v) },
			Field("kneesInRest", Group.OPEN, D, 0f..30f, 1f, ADVANCED, { it.kneesInRest }) { t, v -> t.copy(kneesInRest = v) },
			Field("openShorten", Group.OPEN, P, 0f..6f, 0.1f, ADVANCED, { it.openShorten }) { t, v -> t.copy(openShorten = v) },
			Field("openWiden", Group.OPEN, P, 0f..6f, 0.1f, ADVANCED, { it.openWiden }) { t, v -> t.copy(openWiden = v) },
			Field("standStretch", Group.OPEN, P, 0f..6f, 0.1f, ADVANCED, { it.standStretch }) { t, v -> t.copy(standStretch = v) },
			Field("standNarrow", Group.OPEN, P, 0f..6f, 0.1f, ADVANCED, { it.standNarrow }) { t, v -> t.copy(standNarrow = v) },
			Field("leanDegrees", Group.LEAN, D, 0f..45f, 0.5f, COMMON, { it.leanDegrees }) { t, v -> t.copy(leanDegrees = v) },
			Field("leanBackDegrees", Group.LEAN, D, 0f..30f, 0.5f, COMMON, { it.leanBackDegrees }) { t, v -> t.copy(leanBackDegrees = v) },
			Field("pelvisShare", Group.LEAN, P, 0f..100f, 1f, ADVANCED, { it.pelvisShare }) { t, v -> t.copy(pelvisShare = v) },
			Field("headGrow", Group.PROPORTION, P, 0f..40f, 0.5f, COMMON, { it.headGrow }) { t, v -> t.copy(headGrow = v) },
			Field("chestGrow", Group.PROPORTION, P, 0f..20f, 0.5f, COMMON, { it.chestGrow }) { t, v -> t.copy(chestGrow = v) },
			Field("bodyShrink", Group.PROPORTION, P, 0f..20f, 0.5f, COMMON, { it.bodyShrink }) { t, v -> t.copy(bodyShrink = v) },
			Field("legShrink", Group.PROPORTION, P, 0f..30f, 0.5f, COMMON, { it.legShrink }) { t, v -> t.copy(legShrink = v) },
			Field("bodyZDegrees", Group.BREATH, D, 0f..10f, 0.1f, COMMON, { it.bodyZDegrees }) { t, v -> t.copy(bodyZDegrees = v) },
			Field("breathLift", Group.BREATH, P, 0f..10f, 0.1f, COMMON, { it.breathLift }) { t, v -> t.copy(breathLift = v) },
			Field("breathWiden", Group.BREATH, P, 0f..8f, 0.1f, COMMON, { it.breathWiden }) { t, v -> t.copy(breathWiden = v) },
			Field("torsoDepth", Group.DEPTH, P, 10f..150f, 1f, ADVANCED, { it.torsoDepth }) { t, v -> t.copy(torsoDepth = v) },
			Field("turnCameraDistance", Group.DEPTH, L, 1.5f..20f, 0.1f, ADVANCED, { it.turnCameraDistance }) { t, v -> t.copy(turnCameraDistance = v) },
			Field("leanCameraDistance", Group.DEPTH, L, 2f..30f, 0.1f, ADVANCED, { it.leanCameraDistance }) { t, v -> t.copy(leanCameraDistance = v) },
		)

		val fieldById: Map<String, Field> = fields.associateBy { it.id }

		/** [values] by id over [base]: unknown ids are skipped and every value is clamped to its range. */
		fun fromMap(values: Map<String, Float>, base: RigTuning = RigTuning()): RigTuning =
			values.entries.fold(base) { tuning, (id, value) ->
				val field = fieldById[id]
				if (field == null || !value.isFinite()) tuning else field.set(tuning, value.coerceIn(field.range))
			}
	}
}
