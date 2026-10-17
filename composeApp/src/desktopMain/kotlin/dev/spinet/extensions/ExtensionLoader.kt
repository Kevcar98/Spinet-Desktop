package dev.spinet.extensions

import dev.spinet.extension.ApiVersion
import dev.spinet.extension.MusicSource
import dev.spinet.extension.SourceFactory
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.jar.JarFile

object ExtensionLoader {
    private const val MANIFEST_API = "Spinet-Extension-Api"

    data class Loaded(val file: File, val sources: List<MusicSource>)
    data class Rejected(val file: File, val reason: String)
    data class Result(val loaded: List<Loaded>, val rejected: List<Rejected>)

    fun extensionsDir(dataDir: File): File =
        File(dataDir, "extensions").apply { if (!exists()) mkdirs() }

    fun loadAll(dataDir: File): Result {
        val dir = extensionsDir(dataDir)

        val jars = dir.listFiles { f: File ->
            f.isFile && f.extension.equals("jar", true) && !File(dir, f.name + REMOVE_MARKER).exists()
        }
            ?.sortedBy { it.name }
            ?: emptyList()

        val loaded = mutableListOf<Loaded>()
        val rejected = mutableListOf<Rejected>()

        for (jar in jars) {
            val api = runCatching { declaredApi(jar) }.getOrNull()
            if (api == null) {
                rejected += Rejected(jar, "no $MANIFEST_API in the jar manifest")
                continue
            }
            if (api > ApiVersion.CURRENT) {
                rejected += Rejected(
                    jar,
                    "needs extension api $api; this build has ${ApiVersion.CURRENT}"
                )
                continue
            }

            runCatching { sourcesIn(jar) }.fold(
                onSuccess = { sources ->
                    if (sources.isEmpty()) {
                        rejected += Rejected(jar, "not built for this version of Spinet. Install the latest version of this extension")
                    } else {
                        loaded += Loaded(jar, sources)
                    }
                },
                onFailure = { e ->
                    rejected += Rejected(jar, e.message ?: e::class.simpleName ?: "failed to load")
                }
            )
        }
        return Result(loaded, rejected)
    }

    private fun declaredApi(jar: File): Int? =
        JarFile(jar).use { it.manifest?.mainAttributes?.getValue(MANIFEST_API)?.trim()?.toIntOrNull() }

    private fun sourcesIn(jar: File): List<MusicSource> {
        val loader = ExtensionClassLoader(jar, ExtensionLoader::class.java.classLoader)
        return ServiceLoader.load(SourceFactory::class.java, loader)
            .flatMap { it.create() }
    }
}

internal class ExtensionClassLoader(jar: File, parent: ClassLoader) :
    URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            if (isShared(name)) return super.loadClass(name, resolve)
            val own = try {
                findClass(name)
            } catch (e: ClassNotFoundException) {
                null
            }
            return own?.also { if (resolve) resolveClass(it) } ?: super.loadClass(name, resolve)
        }
    }

    override fun getResource(name: String) = findResource(name) ?: super.getResource(name)

    override fun getResources(name: String): java.util.Enumeration<java.net.URL> {
        val own = findResources(name).toList()
        val app = parent?.getResources(name)?.toList().orEmpty()
        return java.util.Collections.enumeration(own + app)
    }

    companion object {
        private val SHARED_PREFIXES = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.")
        private const val CONTRACT_PACKAGE = "dev.spinet.extension."

        fun isShared(name: String): Boolean =
            SHARED_PREFIXES.any { name.startsWith(it) } ||
                (name.startsWith(CONTRACT_PACKAGE) && '.' !in name.removePrefix(CONTRACT_PACKAGE))
    }
}
