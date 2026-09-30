package com.fongmi.gradle

import org.gradle.api.Project

class AbiApkPackaging {

    static void configure(Project project, Object apkArtifact) {
        def android = project.extensions.getByName('android')
        def components = project.extensions.getByName('androidComponents')
        components.onVariants(components.selector().withBuildType('release')) { variant ->
            def apkName = configureOutputFileNames(variant)
            configureFinalizer(project, android, components, variant, apkArtifact)
            configureReleaseExport(project, variant, apkName, apkArtifact)
        }
    }

    private static String configureOutputFileNames(def variant) {
        def flavors = variant.productFlavors.collectEntries { [(it.first): it.second] }
        def device = flavors['device'] == 'leanback' ? 'tv' : (flavors['device'] ?: 'device')
        def apkName = "TVBox-${device}-arm64_v8a.apk"
        variant.outputs.each { output ->
            // ABI filtering is configured in app.defaultConfig for both APK and AAB.
            output.outputFileName.set(apkName)
        }
        return apkName
    }

    private static void configureFinalizer(Project project, def android, def components, def variant, Object apkArtifact) {
        def windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
        def buildToolsDir = components.sdkComponents.sdkDirectory.get().dir("build-tools/${android.buildToolsVersion}").asFile
        def signingConfig = android.signingConfigs.release
        def finalizeTask = project.tasks.register("finalize${variant.name.capitalize()}Apks", FinalizeApksTask) { task ->
            task.zipalignFile.set(new File(buildToolsDir, windows ? 'zipalign.exe' : 'zipalign'))
            task.apksignerJar.set(new File(buildToolsDir, 'lib/apksigner.jar'))
            task.javaExecutable.set(new File(System.getProperty('java.home'), windows ? 'bin/java.exe' : 'bin/java'))
            task.signingStoreFile.set(signingConfig.storeFile)
            task.keyAlias.set(signingConfig.keyAlias)
            task.storePassword.set(signingConfig.storePassword)
            task.keyPassword.set(signingConfig.keyPassword)
        }
        def request = variant.artifacts.use(finalizeTask)
                .wiredWithDirectories({ task -> task.inputDirectory }, { task -> task.outputDirectory })
                .toTransformMany(apkArtifact)
        finalizeTask.configure { task ->
            task.transformationRequest.set(request)
        }
    }

    private static void configureReleaseExport(Project project, def variant, String apkName, Object apkArtifact) {
        def taskName = "assemble${variant.name.capitalize()}"
        def apkDirectory = variant.artifacts.get(apkArtifact)
        project.tasks.matching { it.name == taskName }.configureEach {
            doLast {
                project.copy {
                    from apkDirectory
                    include apkName
                    into project.rootProject.file('Release/apk')
                    eachFile { it.path = it.name }
                    includeEmptyDirs = false
                }
            }
        }
    }
}
