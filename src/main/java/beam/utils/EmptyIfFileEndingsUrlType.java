package beam.utils;

import com.google.common.collect.Lists;
import org.reflections.vfs.Vfs;

import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 * BEAM
 */
public class EmptyIfFileEndingsUrlType implements Vfs.UrlType {

    private final List<String> fileEndings;

    public EmptyIfFileEndingsUrlType(final String... fileEndings) {

        this.fileEndings = Lists.newArrayList(fileEndings);
    }

    private static Vfs.Dir emptyVfsDir(final URL url) {

        return new Vfs.Dir() {
            @Override
            public String getPath() {

                return url.toExternalForm();
            }

            @Override
            public Iterable<Vfs.File> getFiles() {

                return Collections.emptyList();
            }

            @Override
            public void close() {

            }
        };
    }

    public boolean matches(URL url) {

        final String protocol = url.getProtocol();
        final String externalForm = url.toExternalForm();
        if (!protocol.equals("file")) {
            return false;
        }
        for (String fileEnding : fileEndings) {
            if (externalForm.endsWith(fileEnding))
                return true;
        }
        return false;
    }

    public Vfs.Dir createDir(final URL url) {

        return emptyVfsDir(url);
    }
}
