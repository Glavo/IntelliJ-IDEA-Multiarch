package org.glavo.build;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

public final class IJProcessor implements Closeable {

    static final Logger LOGGER = Logging.getLogger(IJProcessor.class);

    final Task task;
    final Arch baseArch;
    final Path baseTar;
    final String productCode;
    final Arch arch;
    final Path outTar;

    final String nativesZipName;
    final ZipFile nativesZip;
    final TarArchiveInputStream tarInput;
    final TarArchiveOutputStream tarOutput;

    public IJProcessor(Task task,
                       Arch baseArch, String productCode, Path baseTar,
                       Arch arch, Path nativesZipFile, Path outTar) throws Throwable {
        this.task = task;
        this.baseArch = baseArch;
        this.productCode = productCode;
        this.baseTar = baseTar;
        this.arch = arch;
        this.outTar = outTar;
        this.nativesZipName = nativesZipFile.getFileName().toString();

        var helper = new OpenHelper();
        try {
            this.nativesZip = helper.register(new ZipFile(nativesZipFile.toFile()));
            this.tarInput = helper.register(new TarArchiveInputStream(
                    helper.register(new GZIPInputStream(
                            helper.register(Files.newInputStream(baseTar))))));
            this.tarOutput = helper.register(new TarArchiveOutputStream(
                    helper.register(new GZIPOutputStream(
                            helper.register(Files.newOutputStream(outTar,
                                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))))));
            tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        } catch (Throwable e) {
            helper.onException(e);
            throw e;
        }
    }

    private void copyJRE() throws IOException {
        LOGGER.lifecycle("Copying JRE");
    }

    public void process() throws Throwable {
        String prefix;
        {
            TarArchiveEntry it = tarInput.getNextEntry();
            if (it == null || !it.isDirectory()) {
                throw new GradleException("Invalid directory entry: ${it.name}");
            }
            prefix = it.getName();
        }

        LOGGER.lifecycle("Processing {}", prefix);

        var jbrPrefix = prefix + "jbr/";

        var set = EnumSet.allOf(IJFileProcessor.class);
        if (!productCode.equals("IU")) {
            set.removeIf(it -> it.iu);
        }
        var processors = new HashMap<String, IJFileProcessor>();
        boolean processedJbr = false;

        for (IJFileProcessor processor : set) {
            processors.put(prefix + processor.path, processor);
        }

        TarArchiveEntry entry;
        while ((entry = tarInput.getNextEntry()) != null) {
            String path = entry.getName();

            if (path.startsWith(jbrPrefix)) {
                if (path.equals(jbrPrefix)) {
                    processedJbr = true;
                    copyJRE();
                } else {
                    LOGGER.info("Skip JBR entry: {}", path);
                }
            } else if (processors.get(path) instanceof IJFileProcessor processor) {
                LOGGER.lifecycle("Processing {}", path);
                processor.process(this, entry);
                set.remove(processor);
            } else if (entry.isSymbolicLink()) {
                LOGGER.info("Copying symbolic link {} -> {}", path, entry.getLinkName());
                tarOutput.putArchiveEntry(entry);
                tarOutput.closeArchiveEntry();
            } else {
                LOGGER.info("Copying {}", path);
                tarOutput.putArchiveEntry(entry);
                tarInput.transferTo(tarOutput);
                tarOutput.closeArchiveEntry();
            }
        }

        if (!set.isEmpty()) {
            throw new GradleException("These files were not found: " + set);
        } else if (!processedJbr) {
            throw new GradleException("No JBR found");
        }
    }

    @Override
    public void close() throws IOException {

    }
}
