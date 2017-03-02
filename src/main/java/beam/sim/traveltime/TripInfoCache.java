package beam.sim.traveltime;

import beam.EVGlobalData;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.*;
import com.google.common.collect.TreeMultimap;
import org.apache.log4j.Logger;
import org.matsim.core.utils.collections.Tuple;

import java.io.*;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * BEAM
 */
public class TripInfoCache {
    private static final Logger log = Logger.getLogger(TripInfoCache.class);

    public Connection connection;
    public Boolean useDB = true;
    public Kryo kryo;
    public int maxNumTrips;
    public LinkedHashMap<String, TripInfoAndCount> hotCache = new LinkedHashMap<>() ;
    public TreeMultimap<Integer, String> hotCacheUtilization = TreeMultimap.create();
    public ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    public Output output = new Output(outputStream);
    public Input input = new Input();

    public TripInfoCache() {
        maxNumTrips = EVGlobalData.data.ROUTER_CACHE_IN_MEMORY_TRIP_LIMIT;
        try {
            Class.forName("org.postgresql.Driver");
            connection = DriverManager.getConnection("jdbc:postgresql://"+System.getenv("PSQL_HOST")+"/beam", System.getenv("PSQL_USER"), System.getenv("PSQL_PASS"));
        } catch (SQLException | ClassNotFoundException e) {
            log.warn("No postgres host found, proceeding without Tier 2 route cache. psql connection message: " + e.getMessage());
            useDB = false;
        }
        if(useDB){
            log.info("Postgres host found and connection made successfully.");
            kryo = new Kryo();
            kryo.register(TripInfoAndCount.class, 0);
        }
    }
    public TripInformation getTripInformation(String key){
        TripInformation foundTrip = null;
        if(hotCache.containsKey(key)){
            TripInfoAndCount tripAndCount = hotCache.get(key);
            hotCacheUtilization.remove(tripAndCount.count,key);
            tripAndCount.count++;
            hotCacheUtilization.put(tripAndCount.count,key);
            foundTrip = tripAndCount.tripInfo;
        }else if(useDB){
            TripInfoAndCount tripAndCount = readFromTable(key);
            if(tripAndCount!=null && hotCache.size()<maxNumTrips){
                tripAndCount.count++;
                hotCache.put(key,tripAndCount);
                hotCacheUtilization.put(tripAndCount.count,key);
                foundTrip = tripAndCount.tripInfo;
            }
            if(hotCache.size()>=maxNumTrips)flushHotCache();
        }
        return foundTrip;
    }

    private void flushHotCache() {
        int numMoved = 0;
        int numToFlush = maxNumTrips / 20; // 5% flush
        int divisor = 32;
        LinkedList<Tuple<Integer,String>> removedKeys = new LinkedList<>();

        while(numMoved < numToFlush){
            for(Integer utilizationCount : hotCacheUtilization.keySet()){
                for(String key : hotCacheUtilization.get(utilizationCount)){
                    removedKeys.add(new Tuple<Integer, String>(utilizationCount,key));
                    TripInfoAndCount tripAndCount = hotCache.get(key);
                    if(useDB) {
                        insertIntoTable(key, tripAndCount);
                    }
                    hotCache.remove(key);
                    if(++numMoved >= numToFlush)break;
                    if(numMoved % divisor == 0){
                        log.info(numMoved + " of " + numToFlush + " trips flushed out of in-memory cache");
                        divisor *= 2;
                    }
                }
                if(numMoved >= numToFlush)break;
            }
        }
        for(Tuple<Integer,String> keyTuple : removedKeys){
            hotCacheUtilization.remove(keyTuple.getFirst(),keyTuple.getSecond());
        }
    }

    public void putTripInformation(String key, TripInformation tripInfo){
        hotCache.put(key,new TripInfoAndCount(tripInfo,1));
        hotCacheUtilization.put(1,key);
        if(hotCache.size() >= maxNumTrips) {
            flushHotCache();
        }
    }
    public void insertIntoTable(String key, TripInfoAndCount theTrip) {
        kryo.writeObject(output,theTrip);
        output.flush();

        //TODO if we are using Postgres 9.5, we can do a single UPSERT statement to avoid the hideousness below
        try {
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT COUNT(*) FROM trips WHERE key = ?");
            preparedStatement.setString(1, key);
            ResultSet result = preparedStatement.executeQuery();
            result.next();
            if(result.getInt(1) == 1){
                PreparedStatement statement = connection.prepareStatement("UPDATE trips SET hitcount = ? WHERE key = ?");
                statement.setInt(1, theTrip.count);
                statement.setString(2, key);
                statement.executeUpdate();
                statement.close();
            }else {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO trips (key,trip,hitcount) VALUES (?, ?, ?) ");
                statement.setString(1, key);
                statement.setBytes(2, outputStream.toByteArray());
                statement.setInt(3, theTrip.count);
                statement.executeUpdate();
                statement.close();
            }
            preparedStatement.close();
            outputStream.flush();
        } catch (SQLException | IOException e) {
            e.printStackTrace();
        }
    }
    public TripInfoAndCount readFromTable(String key) {

        try{
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT trip,hitcount FROM trips WHERE key = ?");
            preparedStatement.setString(1, key);
            ResultSet result = preparedStatement.executeQuery();
            if(result.next()){
                byte[] tripBytes = result.getBytes(1);
                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(tripBytes);
                Input input = new Input(byteArrayInputStream, tripBytes.length);
                TripInfoAndCount theTrip = (TripInfoAndCount) kryo.readObject(input,TripInfoAndCount.class);
                theTrip.count = result.getInt(2);
                result.close();
                preparedStatement.close();
                return theTrip;
            }
            result.close();
            preparedStatement.close();
        }catch(SQLException e){
            log.warn(e.getMessage());
            return(null);
        }
        return null;
    }
    public void serializeHotCacheKryo(String serialPath){
        log.info("Writing in-memory routing cache to file: " + serialPath);
        log.info(this.toString());
        try {
            Runtime runtime = Runtime.getRuntime();
            double gb = 1024.0*1024*1024;
            log.info("Used Memory:" + (runtime.totalMemory() - runtime.freeMemory()) / gb);
            int counter = 0;
            FileOutputStream fileOut = new FileOutputStream(serialPath);
            GZIPOutputStream zout = new GZIPOutputStream(new BufferedOutputStream(fileOut));
            Output out = new Output(zout);
            Kryo kryo = new Kryo();
            kryo.register(String.class);
            kryo.register(TripInfoAndCount.class);
            for(String key : hotCache.keySet()){
                kryo.writeObject(out, key);
                kryo.writeObject(out,hotCache.get(key));
                if(counter++ % 10000 == 0) {
                    out.flush();
                }
                if(counter++ % 10000 == 0){
                    log.info("Used Memory after " + counter + ": " + (runtime.totalMemory() - runtime.freeMemory()) / gb + " GB");
                }
            }
            out.close();
            zout.close();
            fileOut.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void deserializeHotCacheKryo(String serialPath){
        try {
            Runtime runtime = Runtime.getRuntime();
            double gb = 1024.0*1024*1024;
            FileInputStream fileIn = new FileInputStream(serialPath);
            GZIPInputStream zin = new GZIPInputStream(fileIn);
            Input in = new Input(zin);
            Kryo kryo = new Kryo();
            int i = 0, divisor = 32;
            while(!in.eof()) {
                String key = (String) kryo.readObject(in,String.class);
                TripInfoAndCount tripInfoAndCount = (TripInfoAndCount) kryo.readObject(in,TripInfoAndCount.class);
                hotCache.put(key, tripInfoAndCount);
                hotCacheUtilization.put(tripInfoAndCount.count,key);
                if(++i % divisor == 0){
                    log.info(i + " trips loaded into in-memory cache, app using "+ Math.round((runtime.totalMemory() - runtime.freeMemory()) / gb * 10)/10.0 + " GB");
                    divisor *= 2;
                }
                if(hotCache.size()>=maxNumTrips){
                    flushHotCache();
                    break;
                }
            }
            in.close();
            zin.close();
            fileIn.close();
        } catch (Exception e) {
            log.warn("In Memory Cache not loaded from: "+serialPath);
            e.printStackTrace();
        }
    }
    public String toString(){
        return "In-Memory Cache contains "+hotCache.size()+" trips.";
    }

}
