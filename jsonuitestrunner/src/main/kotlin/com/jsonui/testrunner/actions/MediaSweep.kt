package com.jsonui.testrunner.actions

/**
 * The text of the orphaned-row sweep `addMedia` runs through the shell —
 * pure functions, so the SQL and the shell escaping are pinned on the JVM
 * (the sweep itself is measured on a device: AddMediaOnDeviceTest).
 */
object MediaSweep {
    /** SQL literal: single quotes doubled. */
    fun sql(s: String): String = "'" + s.replace("'", "''") + "'"

    /** For use inside a double-quoted shell word. */
    fun shell(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")

    /**
     * Rows named like the fixture — the exact name and MediaStore's
     * `name (n).ext` respelling — that are orphaned (owner NULL) or this
     * package's own. A live row of another package is not matched.
     */
    fun whereClause(fileName: String, packageName: String): String {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        return "(_display_name = ${sql(fileName)} OR _display_name LIKE ${sql("$stem (%)$ext")}) " +
            "AND (owner_package_name IS NULL OR owner_package_name = ${sql(packageName)})"
    }

    /** Counts, deletes, counts again; prints `swept=<before-after>`. */
    fun script(collection: String, where: String): String {
        val w = "\"" + shell(where) + "\""
        return """
            |b=$(content query --uri $collection --projection _id --where $w | grep -c '^Row:')
            |content delete --uri $collection --where $w
            |a=$(content query --uri $collection --projection _id --where $w | grep -c '^Row:')
            |echo "swept=$((b-a))"
            |""".trimMargin()
    }

    fun sweptCount(output: String): Int =
        Regex("""swept=(-?\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
}
