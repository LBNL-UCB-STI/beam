package beam.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @author mrieser / Senozon AG
 */
/*package*/ class GUnZipper {

    private static final Logger log = LoggerFactory.getLogger(GUnZipper.class);
    private static final int MB = 1024 * 1024;
    private static final int KB = 1024;

    static void gzipFile() {
        JFileChooser chooser = new JFileChooser();
        int openResult = chooser.showOpenDialog(null);
        if (openResult == JFileChooser.APPROVE_OPTION) {
            File srcFile = chooser.getSelectedFile();

            chooser = new SaveFileSaver();
            chooser.setSelectedFile(new File(srcFile.getParentFile(), srcFile.getName() + ".gz"));
            int saveResult = chooser.showSaveDialog(null);
            if (saveResult == JFileChooser.APPROVE_OPTION) {
                File destFile = chooser.getSelectedFile();

                doGzip(srcFile, destFile);
            }
        }
    }

    static void gunzipFile() {
        JFileChooser chooser = new JFileChooser();
        int openResult = chooser.showOpenDialog(null);
        if (openResult == JFileChooser.APPROVE_OPTION) {
            File srcFile = chooser.getSelectedFile();

            chooser = new SaveFileSaver();
            chooser.setSelectedFile(new File(srcFile.getParentFile(), srcFile.getName().replace(".gz", "")));
            int saveResult = chooser.showSaveDialog(null);
            if (saveResult == JFileChooser.APPROVE_OPTION) {
                File destFile = chooser.getSelectedFile();

                doGunzip(srcFile, destFile);
            }
        }
    }

    private static void doGzip(final File srcFile, final File destFile) {
        new Thread(() -> {
            try (FileInputStream srcStream = new FileInputStream(srcFile);
                 FileOutputStream destStream = new FileOutputStream(destFile);
                 BufferedInputStream bSrcStream = new BufferedInputStream(srcStream, 4 * MB);
                 BufferedOutputStream bDestStream = new BufferedOutputStream(new GZIPOutputStream(destStream, 64 * KB), 4 * MB)) {
                AsyncFileInputProgressDialog gui = new AsyncFileInputProgressDialog(srcStream);
                try {
                    doCopy(bSrcStream, bDestStream);
                } finally {
                    gui.close();
                }
            } catch (IOException e) {
                log.error(e.getMessage(), e);
                JOptionPane.showMessageDialog(null,
                        e.getMessage(),
                        "Error while gzipping",
                        JOptionPane.ERROR_MESSAGE);
            }
        }, "gzipper").start();
    }

    private static void doGunzip(final File srcFile, final File destFile) {
        new Thread(() -> {
            try (FileInputStream srcStream = new FileInputStream(srcFile);
                 FileOutputStream destStream = new FileOutputStream(destFile);
                 BufferedInputStream bSrcStream = new BufferedInputStream(new GZIPInputStream(srcStream, 64 * KB), 4 * MB);
                 BufferedOutputStream bDestStream = new BufferedOutputStream(destStream, 4 * MB)) {
                AsyncFileInputProgressDialog gui = new AsyncFileInputProgressDialog(srcStream);
                try {
                    doCopy(bSrcStream, bDestStream);
                } finally {
                    gui.close();
                }
            } catch (IOException e) {
                log.error(e.getMessage(), e);
                JOptionPane.showMessageDialog(null,
                        e.getMessage(),
                        "Error while gunzipping",
                        JOptionPane.ERROR_MESSAGE);
            }
        }, "gunzipper").start();

    }

    private static void doCopy(InputStream src, OutputStream dest) throws IOException {
        byte[] buffer = new byte[64 * KB];
        int bytesRead;
        while ((bytesRead = src.read(buffer)) != -1) {
            dest.write(buffer, 0, bytesRead);
        }
        dest.flush();
    }

    public static void main(String[] args) {
        final JFrame frame = new JFrame();
        frame.setBounds(100, 100, 600, 500);
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            System.out.println("let's go");
            gzipFile();
            System.out.println("let's continue");
            gunzipFile();
            System.out.println("and we're done");
            frame.setVisible(false);
        });
    }
}
