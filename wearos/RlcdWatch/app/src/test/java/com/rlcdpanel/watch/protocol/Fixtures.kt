package com.rlcdpanel.watch.protocol

import java.io.File

/** Finds a shared file of `tests/fixtures` from the working directory of the unit test. */
object Fixtures {
    fun file(name: String): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "tests/fixtures/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Fixture $name not found")
    }
}
