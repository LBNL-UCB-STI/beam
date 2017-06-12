package beam.utils.gtfs;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This predictedUtility extracts files and directories of a standard zip file to
 * a destination directory.
 * @author www.codejava.net
 * @author sfeygin (modifying)
 *
 */
class UnzipUtility {
    /**
     * Size of the buffer to read/write data
     */
    private static final int BUFFER_SIZE = 4096;
    /**
     * Extracts a zip file specified by the zipFilePath to a directory specified by
     * destDirectory (will be created if does not exists)
     * @param zipFilePath Source directory for zip file
     * @param destDirectory Target directory for unzipping.
     * @throws IOException Error on failure.
     */
    static void unzip(String zipFilePath, String destDirectory, boolean delete) throws IOException {
        File destDir = new File(destDirectory);
        if (!destDir.exists()) {
            boolean unused = destDir.mkdir();
        }
        ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry entry = zipIn.getNextEntry();
        // iterates over entries in the zip file
        while (entry != null) {
            String filePath = destDirectory + File.separator + entry.getName();
            if (!entry.isDirectory()) {
                // if the entry is a file, extracts it
                extractFile(zipIn, filePath);
            } else {
                // if the entry is a directory, make the directory
                File dir = new File(filePath);
                boolean unused = dir.mkdir();
            }
            zipIn.closeEntry();
            entry = zipIn.getNextEntry();
        }
        zipIn.close();
        if(delete){
            boolean unused = new File(zipFilePath).delete();
        }
    }
    /**
     * Extracts a zip entry (file entry)
     *
     * @param zipIn input file
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