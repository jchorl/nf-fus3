# nf-fus3

**This project is not affiliated with the official Nextflow project**

## Purpose

This is a nextflow plugin to optimize file-transfer on Batch:
- Instead of using `aws s3 cp` to stage inputs, it uses `goofys` to mount the remote file via `fuse`
- Instead of using `aws s3 cp` to unstage outputs, it uses `goofys` to mount the output dir

For large inputs and outputs, this can cut down on large transfer times to start and finish a process.

## Usage

### Container Images

Note that `nf-fus3` relies on a `goofys` binary to be present in your container image and on your `$PATH`.

You can see an example `Dockerfile` at [`nfproc.Dockerfile`](nfproc.Dockerfile). Build/push the image to a container registry, and specify it in `nextflow.config` (or in the `process` directives). See the [official docs](https://www.nextflow.io/docs/latest/docker.html) for more details.

### Nextflow Configuration

In `nextflow.config`:

```
plugins {
    id 'nf-fus3'
}

process {
    // standard aws batch configs, such as queue...
    container = '<container with goofys>'
    executor = 'awsbatchfus3'
    containerOptions = '--privileged'
}
```

It may be possible to run without `--privileged`, see [this issue](https://github.com/docker/for-linux/issues/321#issuecomment-677744121).

## Limitations

### AWS Batch

At present time, the plugin only works with the `awsbatch` executor. In fact, you must specify `awsbatchfus3` executor.

### Unstaging

Tl;dr: unstaging is only optimized if you output to a directory, such as:

```
output:
    path 'foo/*'
```

See [FAQ](#FAQ) for details.

## Building from Source

Building/development is slightly complex. We can leverage Docker.

First, ensure that `nf-fus3` and `nextflow` are in a common directory. From within the `nf-fus3` directory:

```shell
$ docker run -it --rm \
    -v "$(pwd)":/work/nf-fus3 \
    -v "$(pwd)/../nextflow":/work/nextflow \
    -w /work/nf-fus3 \
    amazoncorretto:17 \
    bash

# ./gradlew copyPluginZip # this FAILS, I'm not sure why
```

## FAQ

1. Why create a new executor?

    It is a thin wrapper around `awsbatch` that shims the staging/unstaging codegen. It seemed to be the easiest way to accomplish the goal of transparently using `goofys`. If there is a better integration mechanism, I'd be open to migrating. Ideally we would use the `awsbatch` executor as-is, and more elegantly shim the stage/unstaging. I explored `metaprogramming`. At time of writing, `AwsBatchExecutor` is `@CompileStatic`, so shimming components is difficult.

1. Why are only nested directories supported for unstaging?

    Mounting files has different semantics than mounting directories. While `goofys` doesn't seem to support mounting files, we can simulate this with a `goofys` mount and a `bind` mount:

    ```shell
    $ touch srcfile dstfile
    $ sudo mount --bind /some/path/dstfile /some/path/srcfile
    $ echo foo > dstfile 
    $ cat srcfile
    foo
    $ python3
    >>> with open("dstfile", "w") as f:
    ...     f.write("bar")
    3
    >>>
    $ cat srcfile
    bar%
    ```

    However, the files must exist before mounting, which can lead to bugs where a process fails to generate a file but nextflow sees an empty file.

    Additionally, the semantics of creating and deleting files that are mounted are difficult to reason about.

    Therefore, at present time, "naked" outputs (outputs to files in the work dir) will get uploaded via normal transfer mechanisms, and "nested" outputs (outputs to a nested dir) will be optimized.
