package beam.utils.gtfs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.http.client.fluent.Async;
import org.apache.http.client.fluent.Content;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static beam.utils.UnzipUtility.unzip;

/**
 * Sends GET request to 511 NextGen API datafeeds endpoint and downloads a zip file w/ GTFS data
 * to a specified directory. Unzips file to directory and cleans up. Class is (probably) threadsafe and
 * all operations performed asynchronously.
 * <p>
 * To be used with {@link SFBayPT2MATSim};
 */
class TransitDataDownloader {

    private static final Logger log = LoggerFactory.getLogger(TransitDataDownloader.class);

    private static final int BUFFER_SIZE = 1024;
    private static TransitDataDownloader instance = null;
    private static ExecutorService threadpool;
    private final String apiKey;

    private TransitDataDownloader(String apiKey) {
        this.apiKey = apiKey;
    }

    public static TransitDataDownloader getInstance(String apiKey) {
        // Thread-safe singleton using double-checked locking to avoid
        // synchronization overhead.
        if (instance == null) {
            synchronized (TransitDataDownloader.class) {
                if (instance == null) {
                    instance = new TransitDataDownloader(apiKey);
                }
            }
        }
        return instance;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int n = input.read(buf);
        while (n >= 0) {
            output.write(buf, 0, n);
            n = input.read(buf);
        }
        output.flush();
    }

    List<Operator> getTransitOperatorList() {
        threadpool = Executors.newFixedThreadPool(2);
        // Request (GET )
        URIBuilder builder = new URIBuilder();
        builder.setScheme("http").setHost("api.511.org").setPath("/transit/operators")
                .setParameter("api_key", this.apiKey);
        URI requestURL = null;
        try {
            requestURL = builder.build();
        } catch (URISyntaxException e) {
            log.error("exception occurred due to ", e);
        }

        Async async = Async.newInstance().use(threadpool);

        assert requestURL != null;
        final Request request = Request.Get(requestURL);
        final List<Operator> ops = new ArrayList<>();
        async.execute(request, new FutureCallback<Content>() {
            @Override
            public void completed(Content result) {
                Gson gson = new Gson();
                ops.addAll(gson.fromJson(result.asString(), new TypeToken<List<Operator>>() {
                }.getType()));
                threadpool.shutdown();
            }

            @Override
            public void failed(Exception ex) {

            }

            @Override
            public void cancelled() {

            }
        });

        while (true) {
            if (threadpool.isShutdown()) break;
        }

        return ops;
    }

    Future<Content> getGTFSZip(String outDir, String agencyId) {
        threadpool = Executors.newFixedThreadPool(2);
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.scripts.SimpleLog");

        System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");

        System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.commons.httpclient", "info");

        // Request (GET )
        URIBuilder builder = new URIBuilder();
        builder.setScheme("http").setHost("api.511.org").setPath("/transit/datafeeds")
                .setParameter("api_key", this.apiKey)
                .setParameter("operator_id", agencyId);
        URI requestURL = null;
        try {
            requestURL = builder.build();
        } catch (URISyntaxException e) {
            log.error("exception occurred due to ", e);
        }


        Async async = Async.newInstance().use(threadpool);

        assert requestURL != null;
        final Request request = Request.Get(requestURL);

        log.info("Downloading requested file.");

        // TODO: Refactor callback hell to RESTClient. Replace w/ retrofit or SpringREST.
        return async.execute(request, new FutureCallback<Content>() {
            @Override
            public void completed(Content result) {
                InputStream in = result.asStream();
                File outDirPath = new File(outDir + File.separator);
                if (outDirPath.exists() || outDirPath.mkdirs()) {
                    String fileSuffix = File.separator + agencyId + "_gtfs";
                    String zipFilePath = outDir + fileSuffix + ".zip";
                    try (FileOutputStream out = new FileOutputStream(zipFilePath)) {
                        threadpool.submit(() -> {
                            try {
                                copy(in, out);
                                log.info("Download done.");
                                threadpool.submit(() -> {
                                    log.info("Unzipping now... ");
                                    String destDirectory = outDirPath + fileSuffix + File.separator;
                                    try {
                                        unzip(zipFilePath, destDirectory, true);
                                        log.info("Done.");
                                        close();
                                    } catch (Exception e) {
                                        log.error("exception occurred due to ", e);
                                    }
                                });
                            } catch (IOException e) {
                                log.error("exception occurred due to ", e);
                            }
                        });
                    } catch (IOException e) {
                        log.error("exception occurred due to ", e);
                    }
                }
            }

            @Override
            public void failed(Exception ex) {
                log.info(ex.getMessage() + ": " + request);
                close();
            }

            @Override
            public void cancelled() {
            }
        });
    }

    private void close() {
        threadpool.shutdown();
    }
}
