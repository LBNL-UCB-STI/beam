//package beam.runners;
//
//import beam.sim.traveltime.ExogenousTravelTime;
//import beam.sim.traveltime.TripInformation;
//import org.apache.commons.io.FileUtils;
//import org.mapdb.DB;
//import org.mapdb.DBMaker;
//import org.mapdb.HTreeMap;
//
//import java.io.File;
//import java.io.IOException;
//import java.util.Iterator;
//import java.util.Map;
//
///**
// * CombineCaches
// *
// * Takes a base path and a list of DBMap caches as args.
// *
// * First cache in the list is loaded and all subequent caches are added to the first,
// * then the first is saved.
// */
//public class CombineCaches {
//    // Generate and serialize the travel time by link data
//    public static void main(String[] args) {
//
//        String basePath = args[0];
//
//        DB db, dbTemp;
//        HTreeMap<String, TripInformation> cache, cacheTemp;
//
//        // Load first cache and use as the main
//        String dbFilePath = args[1];
//        File dbFile = new File(basePath + dbFilePath);
//        if(!dbFile.exists())throw new RuntimeException("File not found: "+basePath+dbFilePath);
//        db = DBMaker.fileDB(dbFile).checksumHeaderBypass().make();
//        cache = (HTreeMap<String, TripInformation>) db.hashMap("cache").createOrOpen();
//        System.out.println("Cache #1 "+args[1]+" length "+cache.getSize());
//
//        for(int i = 2; i < args.length; i++){
//            String dbFilePathTemp = args[i];
//            File dbFileTmep = new File(basePath + dbFilePathTemp);
//            if(!dbFileTmep.exists())throw new RuntimeException("File not found: "+basePath+dbFilePathTemp);
//            dbTemp = DBMaker.fileDB(dbFileTmep).make();
//            cacheTemp = (HTreeMap<String, TripInformation>) dbTemp.hashMap("cache").createOrOpen();
//            System.out.println("Cache #"+i+" "+args[i]+" length "+cacheTemp.getSize());
//            Iterator<String> iter = cacheTemp.keySet().iterator();
//            while(iter.hasNext()){
//                String key = iter.next();
//                cache.put(key,cacheTemp.get(key));
//            }
//            cacheTemp.close();
//            dbTemp.close();
//        }
//        System.out.println("Cache #1 "+args[1]+" length "+cache.getSize());
//        cache.close();
//        db.close();
//
//        System.out.println("Complete");
//    }
//
//}
