package org.jboss.eapqe.clustering.jmeter.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Semaphore;

public class LogLoader {
    private static final Logger LOG = LoggerFactory.getLogger(LogLoader.class);
    private static final Semaphore semaphore = new Semaphore(1);

    public static void loadLogs() {
        try {
            if (semaphore.tryAcquire()) {
                File jmOutDir = Paths.get(".").toFile();
                LOG.trace("{} : {}", jmOutDir.getAbsolutePath(), jmOutDir.isDirectory());

                if (!jmOutDir.exists() || !jmOutDir.isDirectory()) {
                    throw new IOException("Where is folder /tmp/tests-clustering/jm-out ?");
                }

                for (File file : jmOutDir.listFiles()) {
                    if (file.isFile() && file.getName().endsWith(".log") && file.getName().startsWith(org.jboss.eapqe.config.Constants.CLUSTERING_LIFECYCLE_MARKER)) {
                        try {
                            if (file.exists() && file.isFile() && file.length() > 0) {
                                List<String> lines = Files.readAllLines(Paths.get(file.getAbsolutePath()));
                                if (lines != null && lines.size() > 0) {
                                    String read = lines.get(0);
                                    LOG.info(read);
                                    file.delete();
                                }
                            }
                        } catch (NoSuchFileException ignore) {
                        } catch (IOException e) {
                            LOG.error("Error reading file {}", file.getAbsolutePath(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error reading dir {}", Paths.get("").toFile().getAbsolutePath(), e);
        } finally {
            semaphore.release();
        }
    }
}
