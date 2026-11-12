package com.musigatto.fosscore.pdfscore

/**
 * The SMuFL symbols of everyday scores, the ones recognition looks for: limiting the candidates to
 * these keeps a note from being read as a rare symbol of the same look (plainchant, lute, mensural
 * notation). When two names draw the same, the first one listed is the one given.
 */
object CommonSymbols {
    val NAMES: List<String> = listOf(
        // noteheads
        "noteheadBlack", "noteheadHalf", "noteheadWhole", "noteheadDoubleWhole",
        "noteheadXBlack", "noteheadXHalf", "noteheadXWhole", "noteheadCircleX",
        "noteheadDiamondBlack", "noteheadDiamondHalf", "noteheadDiamondWhole",
        "noteheadTriangleUpBlack", "noteheadSlashHorizontalEnds", "noteheadSlashWhiteHalf",
        "noteheadParenthesisLeft", "noteheadParenthesisRight",
        // rests
        "restDoubleWhole", "restWhole", "restHalf", "restQuarter", "rest8th", "rest16th", "rest32nd", "rest64th", "rest128th",
        "restHBar", "restHBarLeft", "restHBarMiddle", "restHBarRight", "restWholeLegerLine", "restHalfLegerLine",
        // flags
        "flag8thUp", "flag8thDown", "flag16thUp", "flag16thDown", "flag32ndUp", "flag32ndDown",
        "flag64thUp", "flag64thDown", "flag128thUp", "flag128thDown",
        // accidentals
        "accidentalSharp", "accidentalFlat", "accidentalNatural", "accidentalDoubleSharp", "accidentalDoubleFlat",
        "accidentalParensLeft", "accidentalParensRight",
        // clefs
        "gClef", "gClef8vb", "gClef8va", "gClef15mb", "gClef15ma", "fClef", "fClef8vb", "fClef8va", "fClef15mb", "fClef15ma",
        "cClef", "cClef8vb", "unpitchedPercussionClef1", "unpitchedPercussionClef2", "6stringTabClef",
        "gClefChange", "fClefChange", "cClefChange",
        // time signatures
        "timeSig0", "timeSig1", "timeSig2", "timeSig3", "timeSig4", "timeSig5", "timeSig6", "timeSig7", "timeSig8", "timeSig9",
        "timeSigCommon", "timeSigCutCommon", "timeSigPlus", "timeSigFractionalSlash",
        // dots, articulations, holds
        "augmentationDot", "repeatDot",
        "articAccentAbove", "articAccentBelow", "articStaccatoAbove", "articStaccatoBelow",
        "articTenutoAbove", "articTenutoBelow", "articStaccatissimoAbove", "articStaccatissimoBelow",
        "articMarcatoAbove", "articMarcatoBelow", "articTenutoStaccatoAbove", "articTenutoStaccatoBelow",
        "articAccentStaccatoAbove", "articAccentStaccatoBelow",
        "fermataAbove", "fermataBelow", "fermataShortAbove", "fermataLongAbove", "breathMarkComma", "caesura",
        // repeats and jumps
        "segno", "coda", "codaSquare", "repeatLeft", "repeatRight", "repeatRightLeft", "repeat1Bar", "repeat2Bars",
        "repeatBarSlash", "dalSegno", "daCapo",
        // dynamics
        "dynamicPiano", "dynamicMezzo", "dynamicForte", "dynamicSforzando", "dynamicZ", "dynamicRinforzando", "dynamicNiente",
        "dynamicPP", "dynamicPPP", "dynamicMP", "dynamicMF", "dynamicFF", "dynamicFFF", "dynamicFortePiano", "dynamicSforzato",
        "dynamicCrescendoHairpin", "dynamicDiminuendoHairpin",
        // octaves
        "ottava", "ottavaAlta", "ottavaBassa", "ottavaBassaBa", "ottavaBassaVb", "quindicesima", "quindicesimaAlta",
        "quindicesimaBassa", "ventiduesima", "octaveParensLeft", "octaveParensRight",
        // ornaments, tremolos, pedal, grace notes, arpeggios
        "ornamentTrill", "ornamentTurn", "ornamentTurnInverted", "ornamentMordent", "ornamentShortTrill",
        "tremolo1", "tremolo2", "tremolo3", "tremolo4",
        "keyboardPedalPed", "keyboardPedalUp", "graceNoteSlashStemUp", "graceNoteSlashStemDown",
        "graceNoteAcciaccaturaStemUp", "graceNoteAcciaccaturaStemDown", "wiggleArpeggiatoUp", "wiggleTrill",
        // brackets, the tempo's notes
        "brace", "bracketTop", "bracketBottom",
        "metNoteWhole", "metNoteHalfUp", "metNoteQuarterUp", "metNote8thUp", "metNote16thUp", "metAugmentationDot",
    )

    /**
     * Symbols drawn alike in every font, told apart by where they sit, not by their look: a dot
     * after a note or over it, a whole rest hanging from a line or a half rest sitting on it. The
     * first of each is the name given; the others come with it ([SymbolMatch.alike]) for the reader
     * of the staff to choose.
     */
    val LOOK_ALIKE: List<List<String>> = listOf(
        listOf("augmentationDot", "articStaccatoAbove", "articStaccatoBelow", "repeatDot", "metAugmentationDot"),
        listOf("restWhole", "restHalf"),
        listOf("noteheadWhole", "metNoteWhole"),
        listOf("articAccentAbove", "articAccentBelow"),
        listOf("articTenutoAbove", "articTenutoBelow"),
        listOf("noteheadParenthesisLeft", "accidentalParensLeft", "octaveParensLeft"),
        listOf("noteheadParenthesisRight", "accidentalParensRight", "octaveParensRight"),
        listOf("noteheadSlashHorizontalEnds", "repeatBarSlash"),
        listOf("wiggleTrill", "wiggleArpeggiatoUp"),
    )

    private val alikeOf: Map<String, List<String>> = LOOK_ALIKE.flatMap { group -> group.map { it to group } }.toMap()

    /** The name a symbol is given among those drawn like it ([LOOK_ALIKE]): the first of its group. */
    fun canonical(name: String): String = alikeOf[name]?.first() ?: name

    /** The names drawn like this one, itself first; just itself when it has no look-alike. */
    fun alike(name: String): List<String> = alikeOf[name] ?: listOf(name)

    private val rank = NAMES.withIndex().associate { it.value to it.index }

    /** Where a name comes in [NAMES] (the lower, the plainer), past the end when not listed. */
    fun rank(name: String): Int = rank[name] ?: NAMES.size
}
