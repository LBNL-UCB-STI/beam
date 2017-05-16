package beam.sim.traveltime;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * BEAM
 */
public class TripInfoCacheMapDB {
    private static final Logger log = Logger.getLogger(TripInfoCacheMapDB.class);

    private File dbFile, dbTempFile;
    public DB dbDisk, dbMemory;
    public HTreeMap<String, TripInformation> onDisk, inMemory;

    public TripInfoCacheMapDB(String dbPath) {
        String permanentDbPath = dbPath;
        String temporaryDbPath = dbPath + "-LIVE";
        dbFile = new File(permanentDbPath);
        dbTempFile = new File(temporaryDbPath);
        if (dbFile.exists()) {
            try {
                FileUtils.copyFile(dbFile, dbTempFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (dbTempFile.exists()) {
            FileUtils.deleteQuietly(dbTempFile);
        }
        openDBs();
        log.info("In memory cache opened of size: "+inMemory.size()+" with on disk overflow of size "+onDisk.size());
    }

    public void openDBs(){
        dbDisk = DBMaker
                .fileDB(dbTempFile)
                .make();

        dbMemory = DBMaker
                .memoryDB()
                .make();

        // Big map populated with data expired from cache
        onDisk = (HTreeMap<String, TripInformation>)dbDisk
                .hashMap("onDisk")
                .keySerializer(Serializer.STRING)
                .createOrOpen();

        // fast in-memory collection with limited size
        inMemory = (HTreeMap<String, TripInformation>) dbMemory
                .hashMap("inMemory")
                .keySerializer(Serializer.STRING)
                .expireStoreSize(64*1024*1024*1024)
                .expireAfterCreate()
                //this registers overflow to `onDisk`
                .expireOverflow((HTreeMap) onDisk)
                //good idea is to enable background expiration
                .expireExecutor(Executors.newScheduledThreadPool(2))
                .create();

    }

    public synchronized TripInformation getTripInformation(String key){
        return inMemory.isClosed() ? null : inMemory.get(key);
    }

    public Integer getCacheSize() {
        return inMemory.size();
    }

    public String cacheSizeAsString() {
        return "InMem size: "+(inMemory.isClosed()?"<closed>":inMemory.size())+
                " DiskOverflow size: "+(onDisk.isClosed()?"<closed>":onDisk.size());
    }

    public synchronized void putTripInformation(String key, TripInformation tripInfo){
        if(!inMemory.isClosed())inMemory.put(key,tripInfo);
    }
    public String toString(){
        return "MapDB Cache contains "+inMemory.size()+" trips.";
    }

    public synchronized void persistStore(){
        log.info("In memory cache about to be persisted of size: "+inMemory.size()+" with on disk overflow of size "+onDisk.size());
        close();
        try {
            FileUtils.copyFile(dbTempFile,dbFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        openDBs();
        log.info("In memory cache now persisted of size: "+inMemory.size()+" with on disk overflow of size "+onDisk.size());
    }
    public void close() {
        inMemory.clearWithExpire();
        inMemory.close();
        dbMemory.close();
        onDisk.close();
        dbDisk.close();
    }
}
