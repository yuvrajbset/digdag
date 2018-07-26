package io.digdag.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.immutables.value.Value;

@Value.Immutable
public interface CommandExecutorRequest
{
    // This Path must be a relative path from CommandExecutorContent.getLocalProjectPath()
    Path directory();  // => cmd/

    Map<String, String> environments();

    List<String> command();

    // Files in this dir in the local workspace will be uploaded to
    // the process container when the process starts.
    // Files in this dir in the process container will be downloaded
    // to the local workspace when the process finishes (meaning that
    // CommandExecutor.poll or .run returned CommandStatus with isFinished=true).
    Path ioDirectory();  // => .digdag/tmp/random/
}
