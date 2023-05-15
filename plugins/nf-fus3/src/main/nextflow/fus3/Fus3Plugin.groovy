package nextflow.fus3

import com.google.common.base.Preconditions
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import java.nio.file.Path
import java.nio.file.Paths
import nextflow.cloud.aws.batch.AwsBatchExecutor
import nextflow.cloud.aws.batch.AwsBatchFileCopyStrategy
import nextflow.cloud.aws.batch.AwsBatchTaskHandler
import nextflow.cloud.aws.batch.AwsOptions
import nextflow.cloud.aws.nio.S3Path
import nextflow.executor.BashWrapperBuilder
import nextflow.plugin.BasePlugin
import nextflow.processor.TaskBean
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.util.Escape
import nextflow.util.ServiceName
import org.pf4j.ExtensionPoint
import org.pf4j.PluginWrapper

@Slf4j
@CompileStatic
class Fus3FileCopyStrategy extends AwsBatchFileCopyStrategy {
    static Path mountRoot = Paths.get("/fus3mount")

    private List<String> outputFiles // for optimizing outputs before getUnstageOutputFilesScript is called

    Fus3FileCopyStrategy(TaskBean task, AwsOptions opts) {
        super(task, opts)
        this.outputFiles = task.outputFiles
    }

    @Override
    String getStageInputFilesScript(Map<String, Path> inputFiles) {
        Map<String, S3Path> s3InputFiles = inputFiles.collectEntries((String target, Path path) -> [target, (path as S3Path)])

        def stageScripts = [
            downloadOrLink(s3InputFiles.get(TaskRun.CMD_SCRIPT), TaskRun.CMD_SCRIPT),
            downloadOrLink(s3InputFiles.get(TaskRun.CMD_RUN), TaskRun.CMD_RUN),
        ]

        s3InputFiles = s3InputFiles.findAll((Map.Entry<String, S3Path> it) -> it.key != TaskRun.CMD_SCRIPT && it.key != TaskRun.CMD_RUN)
        stageScripts += mountAndLinkInputPaths(s3InputFiles)
        stageScripts += mountAndLinkOutputPaths()
        log.info "STAGE SCRIPT:\n${stageScripts.join("\n")}"
        stageScripts.join("\n")
    }

    List mountAndLinkInputPaths(Map<String, S3Path> inputFiles) {
        List<String> stageScripts = []
        Map<String, S3Path> s3InputFilesParents = inputFiles.collectEntries((String target, S3Path path) -> [target, path.getParent() as S3Path])
        stageScripts += mountS3Paths(s3InputFilesParents, true)
        stageScripts += inputFiles.collect((Map.Entry<String, S3Path> entry) -> downloadOrLink(entry.value, entry.key))
        stageScripts
    }

    List mountS3Paths(Map<String, S3Path> inputFiles, boolean readOnly) {
        Set<S3Path> s3Paths = inputFiles.collect((Map.Entry<String, S3Path> inputFile) -> inputFile.value).toSet()

        // check that none are prefixes of each other, otherwise they'll clobber each other
        for (S3Path path1 : s3Paths) {
            for (S3Path path2 : s3Paths) {
                if (path1 == path2) {
                    continue
                }
                Preconditions.checkArgument(!path1.startsWith(path2) && !path2.startsWith(path1), "mounting paths that are prefixes of other paths is unsupported, path1=$path1 path2=$path2")
            }
        }

        List mountCmds = []

        for (S3Path path : s3Paths) {
            Path target = s3ToLocal(path)
            mountCmds << "mkdir -p ${Escape.path(target)}"
            mountCmds << "goofys ${readOnly ? '-o ro ' : ' '}'${path.bucket}:${path.key}' ${Escape.path(target)}"
        }

        mountCmds
    }

    String downloadOrLink(S3Path path, String targetName) {
        // don't bother mounting the script or run files
        if (targetName == TaskRun.CMD_SCRIPT || targetName == TaskRun.CMD_RUN) {
            return "nxf_s3_download s3:/${Escape.path(path)} ${Escape.path(targetName)}"
        }

        "ln -s ${Escape.path(s3ToLocal(path))} ${Escape.path(targetName)}"
    }

    Path s3ToLocal(S3Path s3Path) {
        mountRoot.resolve(s3Path.bucket).resolve(s3Path.key)
    }

    List mountAndLinkOutputPaths() {
        List mountCmds = []

        for (String outputFile : outputFiles) {
            // to start out, only optimize some/nested/dir/* and some/nested/foo.txt
            if (canOptimizeOutput(outputFile)) {
                if (mountCmds.isEmpty()) {
                    mountCmds += mountS3Paths([outputFile: targetDir as S3Path], false)
                }

                Path parent = Paths.get(outputFile).getParent()
                S3Path outputPath = targetDir.resolve(parent.toString()) as S3Path
                mountCmds << "mkdir -p ${s3ToLocal(outputPath)}"
                mountCmds << downloadOrLink(outputPath, parent.toString())
            }
        }

        return mountCmds
    }

    boolean canOptimizeOutput(String output) {
        Paths.get(output).getNameCount() > 1
    }

    @Override
    String getUnstageOutputFilesScript(List<String> outputFiles, Path targetDir) {
        List<String> unoptimizedUnstages = []

        for (String outputFile : outputFiles) {
            if (!canOptimizeOutput(outputFile)) {
                unoptimizedUnstages << outputFile
                log.warn "s3-fus3: cannot fuse optimize unstage of $outputFile because it is not written to a directory"
            }
        }

        return super.getUnstageOutputFilesScript(unoptimizedUnstages, targetDir)
    }
}

@CompileStatic
class AwsBatchFus3ScriptLauncher extends BashWrapperBuilder {
    AwsBatchFus3ScriptLauncher(TaskBean bean, AwsOptions opts) {
        super(bean, new Fus3FileCopyStrategy(bean, opts))

        // enable the copying of output file to the S3 work dir
        if (scratch == null) {
            scratch = true
        }

        // include script/run files
        bean.inputFiles[TaskRun.CMD_SCRIPT] = bean.workDir.resolve(TaskRun.CMD_SCRIPT)
        bean.inputFiles[TaskRun.CMD_RUN] = bean.workDir.resolve(TaskRun.CMD_RUN)
    }

    @Override
    protected boolean fixOwnership() {
        return containerConfig?.fixOwnership
    }
}

class AwsBatchFus3TaskHandler extends AwsBatchTaskHandler {
    AwsBatchFus3TaskHandler(TaskRun task, AwsBatchExecutor executor) {
        super(task, executor)
    }

    @Override
    protected void buildTaskWrapper() {
        new AwsBatchFus3ScriptLauncher(task.toTaskBean(), getAwsOptions()).build()
    }
}

@Slf4j
@ServiceName('awsbatchfus3')
@CompileStatic
class AwsBatchFus3Executor extends AwsBatchExecutor implements ExtensionPoint {
    @Override
    TaskHandler createTaskHandler(TaskRun task) {
        return new AwsBatchFus3TaskHandler(task,this)
    }
}

@CompileStatic
class Fus3Plugin extends BasePlugin {
    Fus3Plugin(PluginWrapper wrapper) {
        super(wrapper)
    }
}
