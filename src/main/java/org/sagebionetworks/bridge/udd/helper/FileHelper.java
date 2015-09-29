package org.sagebionetworks.bridge.udd.helper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Helper to abstract away file system operations. This allows us to run mock unit tests without hitting the real file
 * system.
 */
@Component
public class FileHelper {
    private static final Logger LOG = LoggerFactory.getLogger(FileHelper.class);

    //
    // The following methods should be mocked, as they represent primitive operations to the file system.
    //

    // CREATE

    /** Non-static createTempDir directory. */
    public File createTempDir() {
        return Files.createTempDir();
    }

    /** Non-static File constructor. */
    public File newFile(File parent, String filename) {
        return new File(parent, filename);
    }

    // READ

    /** Non-static get input (read) stream. */
    public InputStream getInputStream(File file) throws FileNotFoundException {
        return new FileInputStream(file);
    }

    // WRITE

    /** Non-static get output (write) stream. */
    public OutputStream getOutputStream(File file) throws FileNotFoundException {
        return new FileOutputStream(file);
    }

    // DELETE

    /**
     * Delete the specified directory. This is used so that mock file systems can keep track of files. Even though
     * this is identical to deleteFile(), having a separate deleteDir() makes mocking and testing easier.
     */
    public void deleteDir(File dir) {
        boolean success = dir.delete();
        if (!success) {
            LOG.error("Failed to delete directory: " + dir.getAbsolutePath());
        }
    }

    /** Delete the specified file. This is used so that mock file systems can keep track of files. */
    public void deleteFile(File file) {
        boolean success = file.delete();
        if (!success) {
            LOG.error("Failed to delete file: " + file.getAbsolutePath());
        }
    }

    // MISC

    /**
     * Tests if the file exists. Should only be used for files, not for directories. This is because our mock file
     * system tracks directories and files separately (to make it easier to mock and test).
     */
    public boolean fileExists(File file) {
        return file.exists();
    }

    /**
     * Non-static move method. Should only be used for files and not directories. This is because our mock file system
     * tracks directories and files separately (to make it easier to mock and test).
     */
    public void moveFiles(File from, File to) throws IOException {
        Files.move(from, to);
    }

    //
    // These methods can be built up from primitives. As such, they shouldn't be mocked. Rather you should mock the
    // primitives and use the real implementations of these methods below.
    //

    /** Convenience method to get a reader. Calls through to {@link #getInputStream}. */
    public final BufferedReader getReader(File file) throws FileNotFoundException {
        return new BufferedReader(new InputStreamReader(getInputStream(file), Charsets.UTF_8));
    }

    /** Convenience method to get a writer. Calls through to {@link #getOutputStream}. */
    public final BufferedWriter getWriter(File file) throws FileNotFoundException {
        return new BufferedWriter(new OutputStreamWriter(getOutputStream(file), Charsets.UTF_8));
    }
}
