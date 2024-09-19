package org.glavo.build.processor;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.glavo.build.Arch;
import org.glavo.build.Product;
import org.glavo.build.tasks.TransformIDE;
import org.glavo.build.util.OpenHelper;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

public final class IDEProcessor implements AutoCloseable {

    static final Logger LOGGER = Logging.getLogger(IDEProcessor.class);

    final TransformIDE task;
    final Arch baseArch;
    final Path baseTar;
    final Product product;
    final Arch arch;
    final @Nullable Path jreFile;
    final Path outTar;

    final String nativesZipName;
    final ZipFile nativesZip;
    final TarArchiveInputStream tarInput;
    final TarArchiveOutputStream tarOutput;

    private static final int BUFFER_SIZE = 32 * 1024;
    private final byte[] buffer = new byte[BUFFER_SIZE];

    private final OpenHelper helper = new OpenHelper();

    public IDEProcessor(TransformIDE task) throws Throwable {
        this.task = task;
        this.baseArch = task.getIDEBaseArch().get();
        this.product = task.getIDEProduct().get();
        this.baseTar = task.getIDEBaseTar().get().getAsFile().toPath();
        this.arch = task.getIDEArch().get();
        this.jreFile = task.getJDKArchive().get().getAsFile().toPath();
        this.outTar = task.getTargetFile().get().getAsFile().toPath();
        this.nativesZipName = task.getIDENativesZipFile().get().getAsFile().getName();

        try {
            this.nativesZip = helper.register(new ZipFile(task.getIDENativesZipFile().get().getAsFile()));
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

    void copy(InputStream input, OutputStream output) throws IOException {
        int read;
        while ((read = input.read(buffer, 0, BUFFER_SIZE)) >= 0) {
            output.write(buffer, 0, read);
        }
    }

    private void copyJRE(String jbrPrefix, TarArchiveInputStream jreTar) throws IOException {
        assert jreFile != null;

        TarArchiveEntry entry = jreTar.getNextEntry();
        if (entry == null) {
            throw new GradleException(jreFile + " is empty");
        }

        int idx = entry.getName().indexOf('/');
        if (idx < 0) {
            throw new GradleException("Invalid first entry: " + entry.getName());
        }

        String prefix = entry.getName().substring(0, idx + 1);

        do {
            if (!entry.getName().startsWith(prefix)) {
                throw new GradleException("Invalid directory entry: " + entry.getName());
            }
            String newName = jbrPrefix + entry.getName().substring(prefix.length());

            LOGGER.info("Copying {}/{} to {}", jreFile.getFileName(), entry.getName(), newName);
            entry.setName(newName);
            tarOutput.putArchiveEntry(entry);
            copy(jreTar, tarOutput);
            tarOutput.closeArchiveEntry();
        } while ((entry = jreTar.getNextEntry()) != null);
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

        var set = EnumSet.noneOf(IJFileProcessor.class);
        var processors = new HashMap<String, IJFileProcessor>();

        for (IJFileProcessor processor : task.getFileProcessors().get()) {
            if (processor.isSupported(this)) {
                set.add(processor);
                processors.put(processor.getPath(this, prefix), processor);
            }
        }

        boolean processedJbr = false;

        TarArchiveEntry entry;
        while ((entry = tarInput.getNextEntry()) != null) {
            String path = entry.getName();

            if (path.startsWith(jbrPrefix)) {
                if (path.equals(jbrPrefix)) {
                    processedJbr = true;
                    if (jreFile == null) {
                        LOGGER.warn("No JRE provided");
                    } else {
                        LOGGER.lifecycle("Copying JRE from {}", jreFile);
                        try (var jreTar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(jreFile)))) {
                            copyJRE(jbrPrefix, jreTar);
                        }
                    }
                } else {
                    LOGGER.info("Skip JBR entry: {}", path);
                }
            } else if (processors.get(path) instanceof IJFileProcessor processor) {
                LOGGER.lifecycle("Processing {}", path);
                processor.process(this, entry, prefix);
                set.remove(processor);
            } else if (entry.isSymbolicLink()) {
                LOGGER.info("Copying symbolic link {} -> {}", path, entry.getLinkName());
                tarOutput.putArchiveEntry(entry);
                tarOutput.closeArchiveEntry();
            } else {
                LOGGER.info("Copying {}", path);
                tarOutput.putArchiveEntry(entry);
                copy(tarInput, tarOutput);
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
    public void close() throws Exception {
        helper.close();
    }
}
