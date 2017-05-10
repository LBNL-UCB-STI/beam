package beam.sim.traveltime;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import java.io.File;
import java.io.IOException;

/**
 * BEAM
 */
public class TripInfoCacheMapDB {
    private static final Logger log = Logger.getLogger(TripInfoCacheMapDB.class);

    private File dbFile, dbTempFile;
    public DB db;
    public HTreeMap<String, TripInformation> cache;

    public TripInfoCacheMapDB(String dbPath) {
        String permanentDbPath = dbPath;
        String temporaryDbPath = dbPath + "-LIVE";
        dbFile = new File(permanentDbPath);
        dbTempFile = new File(temporaryDbPath);
        if(dbFile.exists()){
            try {
                FileUtils.copyFile(dbFile,dbTempFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else if(dbTempFile.exists()){
            FileUtils.deleteQuietly(dbTempFile);
        }
        db = DBMaker.fileDB(dbTempFile).make();
        cache = (HTreeMap<String, TripInformation>) db.hashMap("cache").createOrOpen();
//            log.info("Postgres host found and connection made successfully.");
//            kryo = new Kryo();
//            kryo.register(TripInfoAndCount.class, 0);
    }
    public TripInformation getTripInformation(String key){
        return cache.get(key);
    }

    public Integer getCacheSize() {
        return cache.size();
    }

    private void flushHotCache() {
    }

    public void putTripInformation(String key, TripInformation tripInfo){
        cache.put(key,tripInfo);
    }
    public String toString(){
        return "MapDB Cache contains "+cache.size()+" trips.";
    }

    public void persistStore(){
        db.close();
        try {
            FileUtils.copyFile(dbTempFile,dbFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        db = DBMaker.fileDB(dbTempFile).make();
        cache = (HTreeMap<String, TripInformation>) db.hashMap("cache").expireMaxSize(50 * 1024 * 1024 * 1024).createOrOpen();
    }
    public void close(){
        db.close();
    }
}
