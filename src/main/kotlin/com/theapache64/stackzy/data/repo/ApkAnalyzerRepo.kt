package com.theapache64.stackzy.data.repo

import com.theapache64.stackzy.data.local.AnalysisReport
import com.theapache64.stackzy.data.local.Platform
import com.theapache64.stackzy.data.remote.Library
import com.theapache64.stackzy.utils.StringUtils
import com.toxicbakery.logging.Arbor
import java.io.File
import javax.inject.Inject

class ApkAnalyzerRepo @Inject constructor() {

    companion object {
        private val PHONEGAP_FILE_PATH_REGEX = "temp/smali(?:_classes\\d+)?/com(?:/adobe)?/phonegap".toRegex()
        private val FLUTTER_FILE_PATH_REGEX = "smali/io/flutter/embedding/engine/FlutterJNI.smali".toRegex()

        private val DIR_REGEX_FORMAT = "smali(_classes\\d+)?\\/%s"
        private val APP_LABEL_MANIFEST_REGEX = "<application.+?label=\"(.+?)\"".toRegex()
    }

    /**
     * To get final report
     */
    fun analyze(
        packageName: String,
        decompiledDir: File,
        allLibraries: List<Library>
    ): AnalysisReport {
        val platform = getPlatform(decompiledDir)
        val (untrackedLibs, libraries) = getLibraries(platform, decompiledDir, allLibraries)
        return AnalysisReport(
            appName = getAppName(decompiledDir) ?: packageName,
            packageName = packageName,
            platform = platform,
            libraries = libraries.sortedBy { it.category == Library.CATEGORY_OTHER },
            untrackedLibraries = untrackedLibs
        )
    }

    /**
     * To get libraries used in the given decompiled app.
     *
     * Returns (untrackedLibs, usedLibs) in a Pair
     */
    fun getLibraries(
        platform: Platform,
        decompiledDir: File,
        allRemoteLibraries: List<Library>
    ): Pair<Set<String>, Set<Library>> {
        return when (platform) {
            is Platform.NativeJava,
            is Platform.NativeKotlin -> {

                // Get all used libraries
                var (appLibraries, untrackedLibs) = getAppLibraries(decompiledDir, allRemoteLibraries)
                appLibraries = mergeDep(appLibraries)

                return Pair(untrackedLibs, appLibraries)
            }
            else -> {
                // TODO : Support other platforms
                Pair(setOf(), setOf())
            }
        }
    }

    /**
     * To merge dependencies.
     */
    private fun mergeDep(
        appLibSet: Set<Library>
    ): MutableSet<Library> {
        val appLibraries = appLibSet.toMutableSet()
        val mergePairs = appLibSet
            .filter { it.replacementPackage != null }
            .map {
                Pair(it.replacementPackage, it.packageName)
            }
        for ((libToRemove, replacement) in mergePairs) {
            val hasDepLib = appLibraries.find { it.packageName.toLowerCase() == replacement } != null
            if (hasDepLib) {
                // remove that lib
                val library = appLibraries.find { it.packageName == libToRemove }
                if (library != null) {
                    appLibraries.removeIf { it.id == library.id }
                }
            }
        }

        return appLibraries
    }

    /**
     * To get app name from decompiled directory
     */
    fun getAppName(decompiledDir: File): String? {
        // Get label key from AndroidManifest.xml
        val label = getAppNameLabel(decompiledDir)
        if (label == null) {
            Arbor.w("Could not retrieve app name label")
            return null
        }
        var appName = if (label.contains("@string/")) {
            getStringXmlValue(decompiledDir, label)
        } else {
            label
        }
        if (appName == null) {
            Arbor.w("Could not retrieve app name")
            return null
        }
        appName = StringUtils.removeApostrophe(appName)
        return appName
    }

    /**
     * To get the appropriate value for the given labelKey from string.xml in the decompiledDir.
     *
     * Returns null if not found
     */
    fun getStringXmlValue(decompiledDir: File, labelKey: String): String? {
        val stringXmlFile = File("${decompiledDir.absolutePath}/res/values/strings.xml")
        val stringXmlContent = stringXmlFile.readText()
        val stringKey = labelKey.replace("@string/", "")
        val regEx = "<string name=\"$stringKey\">(.+?)</string>".toRegex()
        return regEx.find(stringXmlContent)?.groups?.get(1)?.value
    }

    /**
     * To get `label`'s value from AndroidManifest.xml
     */
    fun getAppNameLabel(decompiledDir: File): String? {
        val manifestFile = File("${decompiledDir.absolutePath}/AndroidManifest.xml")
        val manifestContent = manifestFile.readText()
        val match = APP_LABEL_MANIFEST_REGEX.find(manifestContent)
        return if (match != null && match.groupValues.isNotEmpty()) {
            match.groups[1]?.value
        } else {
            null
        }
    }

    /**
     * To get platform from given decompiled APK directory
     */
    fun getPlatform(decompiledDir: File): Platform {
        return when {
            isPhoneGap(decompiledDir) -> Platform.PhoneGap()
            isCordova(decompiledDir) -> Platform.Cordova()
            isXamarin(decompiledDir) -> Platform.Xamarin()
            isReactNative(decompiledDir) -> Platform.ReactNative()
            isFlutter(decompiledDir) -> Platform.Flutter()
            isWrittenKotlin(decompiledDir) -> Platform.NativeKotlin()
            else -> Platform.NativeJava()
        }
    }

    private fun isWrittenKotlin(decompiledDir: File): Boolean {
        return File("${decompiledDir.absolutePath}/kotlin").exists()
    }

    private fun isFlutter(decompiledDir: File): Boolean {
        return decompiledDir.walk()
            .find {
                it.name == "libflutter.so" ||
                        FLUTTER_FILE_PATH_REGEX.find(it.absolutePath) != null
            } != null
    }

    private fun isReactNative(decompiledDir: File): Boolean {
        return getAssetsDir(decompiledDir).listFiles()?.find { it.name == "index.android.bundle" } != null
    }

    private fun isXamarin(decompiledDir: File): Boolean {
        return decompiledDir.walk().find {
            it.name == "libxamarin-app.so" || it.name == "libmonodroid.so"
        } != null
    }

    private fun isCordova(decompiledDir: File): Boolean {
        val assetsDir = getAssetsDir(decompiledDir)
        val hasWWW = assetsDir.listFiles()?.find {
            it.name == "www"
        } != null

        if (hasWWW) {
            return File("${assetsDir.absolutePath}/www/cordova.js").exists()
        }

        return false
    }

    private fun isPhoneGap(decompiledDir: File): Boolean {
        val hasWWW = getAssetsDir(decompiledDir).listFiles()?.find {
            it.name == "www"
        } != null

        if (hasWWW) {
            return File("${decompiledDir.absolutePath}/smali/com/adobe/phonegap/").exists() || decompiledDir.walk()
                .find { file ->
                    val filePath = file.absolutePath
                    isPhoneGapDirectory(filePath)
                } != null
        }
        return false
    }

    private fun getAssetsDir(decompiledDir: File): File {
        return File("${decompiledDir.absolutePath}/assets/")
    }

    private fun isPhoneGapDirectory(filePath: String) = PHONEGAP_FILE_PATH_REGEX.find(filePath) != null

    fun getAppLibraries(
        decompiledDir: File,
        allLibraries: List<Library>
    ): Pair<Set<Library>, Set<String>> {
        val appLibs = mutableSetOf<Library>()
        val untrackedLibs = mutableSetOf<String>()

        decompiledDir.walk().forEach { file ->
            if (file.isDirectory) {
                var isLibFound = false
                for (remoteLib in allLibraries) {
                    val packageAsPath = remoteLib.packageName.replace(".", File.separator)
                    val dirRegEx = getDirRegExFormat(packageAsPath)
                    if (isMatch(dirRegEx, file.absolutePath.replace("\\", "/"))) {
                        appLibs.add(remoteLib)
                        isLibFound = true
                        break
                    }
                }

                // Listing untracked libs
                if (isLibFound.not()) {
                    val filesInsideDir = file.listFiles { it -> !it.isDirectory }?.size ?: 0
                    if (filesInsideDir > 0 && file.absolutePath.contains("${File.separator}smali")) {
                        val afterSmali = file.absolutePath.split("${File.separator}smali")[1]
                        val firstSlash = afterSmali.indexOf(File.separator)
                        val packageName = afterSmali.substring(firstSlash + 1).replace(File.separator, ".")
                        untrackedLibs.add(packageName)
                    }
                }
            }
        }

        return Pair(appLibs, untrackedLibs)
    }

    private fun isMatch(dirRegEx: Regex, absolutePath: String): Boolean {
        return dirRegEx.find(absolutePath) != null
    }

    private fun getDirRegExFormat(packageAsPath: String): Regex {
        return String.format(
            DIR_REGEX_FORMAT,
            packageAsPath.replace("\\", "/") // replace f-slash with b-slash for windows
        ).toRegex()
    }

}