package com.fongmi.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface FinalizeApkParameters extends WorkParameters {

    RegularFileProperty getInputApk()
    RegularFileProperty getOutputApk()
    RegularFileProperty getZipalignFile()
    RegularFileProperty getApksignerJar()
    RegularFileProperty getJavaExecutable()
    RegularFileProperty getSigningStoreFile()
    Property<String> getKeyAlias()
    Property<String> getStorePassword()
    Property<String> getKeyPassword()
}

abstract class FinalizeApkWorkAction implements WorkAction<FinalizeApkParameters> {

    private final ExecOperations execOperations

    @Inject
    FinalizeApkWorkAction(ExecOperations execOperations) {
        this.execOperations = execOperations
    }

    @Override
    void execute() {
        def inputApk = parameters.inputApk.get().asFile
        def outputApk = parameters.outputApk.get().asFile
        def aligned = new File(outputApk.parentFile, "${outputApk.name}.aligned")
        def signed = new File(outputApk.parentFile, "${outputApk.name}.signed")
        try {
            outputApk.parentFile.mkdirs()
            Files.deleteIfExists(outputApk.toPath())
            align(inputApk, aligned)
            sign(aligned, signed)
            Files.move(signed.toPath(), outputApk.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(aligned.toPath())
            Files.deleteIfExists(signed.toPath())
        }
    }

    private void align(File inputApk, File outputApk) {
        execOperations.exec {
            commandLine parameters.zipalignFile.get().asFile.absolutePath, '-P', '16', '-f', '4', inputApk.absolutePath, outputApk.absolutePath
        }
    }

    private void sign(File inputApk, File outputApk) {
        execOperations.exec {
            environment 'APK_KS_PASS', parameters.storePassword.get()
            environment 'APK_KEY_PASS', parameters.keyPassword.get()
            commandLine parameters.javaExecutable.get().asFile.absolutePath,
                    '-jar', parameters.apksignerJar.get().asFile.absolutePath,
                    'sign', '--ks', parameters.signingStoreFile.get().asFile.absolutePath,
                    '--ks-key-alias', parameters.keyAlias.get(), '--ks-pass', 'env:APK_KS_PASS',
                    '--key-pass', 'env:APK_KEY_PASS', '--v1-signing-enabled', 'false',
                    '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'false',
                    '--v4-signing-enabled', 'false', '--out', outputApk.absolutePath, inputApk.absolutePath
        }
    }
}

abstract class FinalizeApksTask extends DefaultTask {

    private final WorkerExecutor workerExecutor

    @Inject
    FinalizeApksTask(WorkerExecutor workerExecutor) {
        this.workerExecutor = workerExecutor
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getInputDirectory()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getZipalignFile()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getApksignerJar()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getJavaExecutable()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getSigningStoreFile()

    @Input
    abstract Property<String> getKeyAlias()

    @Internal
    abstract Property<String> getStorePassword()

    @Internal
    abstract Property<String> getKeyPassword()

    @Internal
    abstract Property<Object> getTransformationRequest()

    @TaskAction
    void finalizeApks() {
        transformationRequest.get().submit(this, workerExecutor.noIsolation(), FinalizeApkWorkAction) { artifact, outputLocation, params ->
            def inputFile = new File(artifact.outputFile)
            params.inputApk.set(inputFile)
            params.outputApk.set(new File(outputLocation.asFile, inputFile.name))
            params.zipalignFile.set(zipalignFile)
            params.apksignerJar.set(apksignerJar)
            params.javaExecutable.set(javaExecutable)
            params.signingStoreFile.set(signingStoreFile)
            params.keyAlias.set(keyAlias)
            params.storePassword.set(storePassword)
            params.keyPassword.set(keyPassword)
            params.outputApk.get().asFile
        }
    }
}
