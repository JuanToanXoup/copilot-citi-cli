package com.speckit.plugin.tools

import java.io.File

object PathSandbox {
    fun resolve(basePath: String, relativePath: String): String? {
        val base = File(basePath).canonicalFile
        val resolved = File(base, relativePath).canonicalFile
        return if (resolved.path.startsWith(base.path + File.separator)
                   || resolved.path == base.path) resolved.path else null
    }

    fun isSafeName(name: String): Boolean =
        !name.contains('/') && !name.contains('\\') &&
        !name.contains("..") && name.isNotBlank()

    fun resolveWorkDir(basePath: String, path: String): String? = when {
        path == "." -> basePath
        path.startsWith("/") -> {
            val canonical = File(path).canonicalPath
            val base = File(basePath).canonicalPath
            if (canonical.startsWith(base)) canonical else null
        }
        else -> resolve(basePath, path)
    }
}
