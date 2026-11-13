package io.github.psd2live.application

/** The supported open-mouth chains, in their actual evaluation order. */
internal object WorkspaceMouthProfiles {
    enum class Source { SHARED, SINGLE_EYE, LAYERED }

    private data class Profile(
        val speech: List<Pair<String, Int>>,
        val plain: List<Pair<String, Int>>,
        val depth: List<Pair<String, Int>>,
        val tongue: List<Pair<String, Int>>,
    )

    private val profiles = mapOf(
        Source.SHARED to Profile(
            speech = speech(fitted = 6, outline = 8, vowel = 7, capture = 6),
            plain = motion(jaw = 8, lids = 8, spring = 9),
            depth = motion(jaw = 8, lids = 8, depth = 6, spring = 9),
            tongue = motion(tongue = 6, jaw = 9, lids = 15, depth = 7, spring = 17),
        ),
        Source.SINGLE_EYE to Profile(
            speech = speech(fitted = 7, outline = 10, vowel = 9, capture = 8),
            plain = motion(jaw = 11, lids = 11, spring = 12),
            depth = motion(jaw = 11, lids = 11, depth = 11, spring = 16),
            tongue = motion(tongue = 8, jaw = 13, lids = 13, depth = 10, spring = 15),
        ),
        Source.LAYERED to Profile(
            speech = speech(fitted = 8, outline = 11, vowel = 8, capture = 7),
            plain = motion(jaw = 10, lids = 10, spring = 11),
            depth = motion(jaw = 10, lids = 10, depth = 9, spring = 14),
            tongue = motion(tongue = 7, jaw = 12, lids = 12, depth = 8, spring = 13),
        ),
    )

    val selectableAdditions = setOf("mouth.vowel", "mouth.capture", "mouth.tongue", "mouth.jaw",
        "mouth.cheek-lid", "mouth.interior-depth", "mouth.spring")

    fun plain(source: Source): Map<String, Int> = profiles.getValue(source).let { (it.speech + it.plain).toMap() }

    fun depth(source: Source): Map<String, Int> = profiles.getValue(source).let { (it.speech + it.depth).toMap() }

    fun preset(source: Source, planned: Collection<String>): Map<String, Int> {
        val profile = profiles.getValue(source)
        val motion = when {
            "mouth.tongue" in planned -> profile.tongue
            "mouth.interior-depth" in planned -> profile.depth
            else -> profile.plain
        }
        return (profile.speech + motion).takeWhile { (type, _) -> type in planned }.toMap()
    }

    fun next(type: String, preceding: List<Pair<String, Int>>): Int? {
        val profile = profiles.values.singleOrNull { it.speech.first() == preceding.firstOrNull() } ?: return null
        return listOf(profile.plain, profile.depth, profile.tongue).mapNotNull { motion ->
            val chain = profile.speech + motion
            chain.getOrNull(preceding.size)?.takeIf { it.first == type && chain.take(preceding.size) == preceding }?.second
        }.distinct().singleOrNull()
    }

    private fun speech(fitted: Int, outline: Int, vowel: Int, capture: Int) =
        listOf("mouth/fitted" to fitted, "mouth.outline" to outline, "mouth.vowel" to vowel, "mouth.capture" to capture)

    private fun motion(jaw: Int, lids: Int, tongue: Int? = null, depth: Int? = null, spring: Int? = null) = buildList {
        tongue?.let { add("mouth.tongue" to it) }
        add("mouth.jaw" to jaw)
        add("mouth.cheek-lid" to lids)
        depth?.let { add("mouth.interior-depth" to it) }
        spring?.let { add("mouth.spring" to it) }
    }
}
