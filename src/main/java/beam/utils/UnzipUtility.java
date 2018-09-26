package beam.utils;

import java.io.*;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.delete;

/**
 * This predictedUtility extracts files and directories of a standard zip file to
 * a destination directory.
 *
 * @author www.codejava.net
 * @author sfeygin (modifying)
 */
public class UnzipUtility {
    /**
     * Size of the buffer to read/write data
     */
    private static final int BUFFER_SIZE = 4096;

    /**
     * Extracts a zip file specified by the zipFilePath to a directory specified by
     * destDirectory (will be created if does not exists)
     *
     * @param zipFilePath   Source directory for zip file
     * @param destDirectory Target directory for unzipping.
     * @throws IOException Error on failure.
     */
    public static void unzip(String zipFilePath, String destDirectory, boolean delete) throws IOException {
        createDirectories(Paths.get(destDirectory));

        ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry entry = zipIn.getNextEntry();
        // iterates over entries in the zip file
        while (entry != null) {
            String filePath = destDirectory + File.separator + entry.getName();
            if (!entry.isDirectory()) {
                createDirectories(Paths.get(filePath).getParent());
                // if the entry is a file, extracts it
                extractFile(zipIn, filePath);
            } else {
                // if the entry is a directory, make the directory
                createDirectories(Paths.get(filePath));
            }
            zipIn.closeEntry();
            entry = zipIn.getNextEntry();
        }
        zipIn.close();
        if (delete) {
            delete(Paths.get(zipFilePath));
        }
    }

    // TODO: double check, if this is redundant
    /**
     * Extracts a zip file specified by the zipFilePath to a directory specified by
     * destDirectory (will be created if does not exists)
     *
     * @param compressedFile   Source directory for zip file
     * @param decompressedFile Target directory for unzipping.
     * @throws IOException Error on failure.
     */
    public static void unGunzipFile(String compressedFile, String decompressedFile, boolean delete) throws IOException {
        FileInputStream fileIn = new FileInputStream(compressedFile);
        GZIPInputStream gZIPInputStream = new GZIPInputStream(fileIn);
        FileOutputStream fileOutputStream = new FileOutputStream(decompressedFile);

        byte[] buffer = new byte[BUFFER_SIZE];

        int bytes_read;

        while ((bytes_read = gZIPInputStream.read(buffer)) > 0) {
            fileOutputStream.write(buffer, 0, bytes_read);
        }

        gZIPInputStream.close();
        fileOutputStream.close();

        if (delete) {
            delete(Paths.get(compressedFile));
        }
    }

    /**
     * Extracts a zip entry (file entry)
     *
     * @param zipIn    input file
     * @param filePath target directory
     * @throws IOException error on failure
     */
    private static void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
        byte[] bytesIn = new byte[BUFFER_SIZE];
        int read;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }
}