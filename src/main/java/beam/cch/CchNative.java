package beam.cch;

import java.util.Map;

public class CchNative {
    public native void init(String filePath);
    public native void lock();
    public native void unlock();
    public native CchNativeResponse route(long bin, double fromX, double fromY, double toX, double toY);
    public native void createBinQueries(long bin, Map<String, String> linkIdToWeight);
}
