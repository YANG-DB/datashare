package org.icij.datashare;

import org.icij.datashare.tasks.ResumeNerTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.tasks.SpewTask;
import org.icij.task.Options;

import java.nio.file.Path;
import java.util.Properties;

public interface TaskFactory {
    SpewTask createSpewTask(final Options<String> options);
    ScanTask createScanTask(final Path path, final Options<String> options);
    ResumeNerTask resumeNerTask(Properties properties);
}
