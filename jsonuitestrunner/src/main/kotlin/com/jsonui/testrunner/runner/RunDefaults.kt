package com.jsonui.testrunner.runner

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Run-scoped defaults, read from the bundle the CLI installed.
 *
 * `jsonui-test validate` writes `jsonui-test-run.json` beside the installed
 * tests on EVERY install, including when it declares nothing. That is what
 * keeps two absences apart here: a missing file means the bundle was
 * installed by a CLI too old to have the feature, an empty [orientation]
 * means the project declared no default. Folding the first into the second
 * would put them back into one observation — which matters more on this
 * driver than on web, because `x-requires-driver` cannot gate an Android
 * driver at all (no version of it is readable from a project tree), so the
 * sidecar is the ONLY place the two separate.
 *
 * The file carries the TABLE (tier -> orientation), never a resolved value:
 * one installed bundle is executed by every lane — phone and tablet run the
 * same files against different devices — so a value resolved at install time
 * could be right for at most one of them. The resolution happens at run time,
 * against the tier this device actually falls in.
 */
@Serializable
data class RunDefaults(
    @SerialName("schemaVersion") val schemaVersion: Int,
    /** tier -> orientation. Present but empty when nothing is declared. */
    @SerialName("orientation") val orientation: Map<String, String> = emptyMap()
)

/** Why [RunDefaultsLoader.load] returned nothing, so a caller can say which. */
enum class RunDefaultsMiss {
    /** No sidecar: this bundle was installed by a CLI without the feature. */
    ABSENT,

    /** Present but not readable as the object this driver expects. */
    UNREADABLE,

    /** Present, but written by a newer CLI than this driver understands. */
    UNKNOWN_VERSION
}

/** [RunDefaultsLoader.load]'s answer: a table, or a miss that names itself. */
data class RunDefaultsLoad(
    val defaults: RunDefaults?,
    val miss: RunDefaultsMiss? = null,
    /** Always set when [defaults] is null. */
    val reason: String? = null
)

object RunDefaultsLoader {

    /** Name the CLI writes, at the root of each installed bundle. */
    const val FILENAME = "jsonui-test-run.json"

    /** The only shape this driver knows how to read. */
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read the sidecar from the assets directory holding the installed tests.
     *
     * Never throws: a driver that cannot read its defaults still has a run to
     * perform, and the caller decides what to say. It also never guesses — an
     * unreadable or newer-versioned file yields null WITH A REASON, not an
     * empty table, because "no default declared" is a different fact from
     * "this driver could not tell".
     */
    fun load(context: Context, assetsPath: String): RunDefaultsLoad {
        val path = if (assetsPath.isEmpty()) FILENAME else "$assetsPath/$FILENAME"
        val text = runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrElse {
            return RunDefaultsLoad(
                defaults = null,
                miss = RunDefaultsMiss.ABSENT,
                reason = "no $path in assets — this bundle was installed by a " +
                    "jsonui-test too old to write one, so no run default could " +
                    "be read (which is not the same as none being declared)"
            )
        }
        return parse(text, path)
    }

    /**
     * The parsing half, separated so it can be exercised without a Context —
     * the version check and the miss taxonomy are the parts worth pinning,
     * and an instrumentation test is a far coarser instrument for them.
     */
    fun parse(text: String, path: String = FILENAME): RunDefaultsLoad {
        val parsed = runCatching { json.decodeFromString<RunDefaults>(text) }.getOrElse { e ->
            return RunDefaultsLoad(
                defaults = null,
                miss = RunDefaultsMiss.UNREADABLE,
                reason = "$path could not be read as run defaults: ${e.message}"
            )
        }
        if (parsed.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return RunDefaultsLoad(
                defaults = null,
                miss = RunDefaultsMiss.UNKNOWN_VERSION,
                reason = "$path declares schemaVersion ${parsed.schemaVersion}; this " +
                    "driver reads $SUPPORTED_SCHEMA_VERSION. Ignoring it rather than " +
                    "guessing at a shape it was not written for — upgrade the driver"
            )
        }
        return RunDefaultsLoad(defaults = parsed)
    }

    /**
     * The default orientation for a tier, or null when none is declared.
     *
     * A value this driver cannot name is dropped rather than forwarded: the
     * CLI validates the table before writing it, so anything else here means
     * the file was hand-edited, and a driver that passed it on would ask for
     * an orientation no test file could have requested.
     */
    fun forTier(defaults: RunDefaults?, tier: String): String? =
        defaults?.orientation?.get(tier)?.takeIf { it == "portrait" || it == "landscape" }
}
