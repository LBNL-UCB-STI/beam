package beam.utils.clustering.kmeans;

import beam.utils.clustering.kmeans.Point2D;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

// original code based on: https://picoledelimao.github.io/blog/2016/03/12/multithreaded-k-means-in-java/
// TODO: add weights

public class KMeans {

  private static final int REPLICATION_FACTOR = 200;



  public static List<Point2D> getDataset(String inputFile) throws Exception {
      List<Point2D> dataset = new ArrayList<>();
      BufferedReader br = new BufferedReader(new FileReader(inputFile));
      String line;
      while ((line = br.readLine()) != null) {
          String[] tokens = line.split(",");
          float x = Float.valueOf(tokens[0]);
          float y = Float.valueOf(tokens[1]);
          Point2D point = new Point2D(x, y);
          for (int i = 0; i < REPLICATION_FACTOR; i++)
              dataset.add(point);
      }
      br.close();
      return dataset;
  }

  public static List<Point2D> initializeRandomCenters(int n, int lowerBound, int upperBound) {
      List<Point2D> centers = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
          float x = (float)(Math.random() * (upperBound - lowerBound) + lowerBound);
          float y = (float)(Math.random() * (upperBound - lowerBound) + lowerBound);
          Point2D point = new Point2D(x, y);
          centers.add(point);
      }
      return centers;
  }

  public static List<Point2D> getNewCenters(List<Point2D> dataset, List<Point2D> centers) {
      List<List<Point2D>> clusters = new ArrayList<>(centers.size());
      for (int i = 0; i < centers.size(); i++) {
          clusters.add(new ArrayList<Point2D>());
      }
      for (Point2D data : dataset) {
          int index = data.getNearestPointIndex(centers);
          clusters.get(index).add(data);
      }
      List<Point2D> newCenters = new ArrayList<>(centers.size());
      for (List<Point2D> cluster : clusters) {
          newCenters.add(Point2D.getMean(cluster));
      }
      return newCenters;
  }
  
  public static double getDistance(List<Point2D> oldCenters, List<Point2D> newCenters) {
      double accumDist = 0;
      for (int i = 0; i < oldCenters.size(); i++) {
          double dist = oldCenters.get(i).getDistance(newCenters.get(i));
          accumDist += dist;
      }
      return accumDist;
  }
  
  public static List<Point2D> kmeans(List<Point2D> centers, List<Point2D> dataset, int k) {
      boolean converged;
      do {
          List<Point2D> newCenters = getNewCenters(dataset, centers);
          double dist = getDistance(centers, newCenters);
          centers = newCenters;
          converged = dist == 0;
      } while (!converged);
      return centers;
  }

  public static void main(String[] args) {
      int k = 10;
      List<Point2D> dataset = new LinkedList<>();
      List<Point2D> centers = initializeRandomCenters(k, 0, 1000000);
      long start = System.currentTimeMillis();
      kmeans(centers, dataset, k);
      System.out.println("Time elapsed: " + (System.currentTimeMillis() - start) + "ms");
      System.exit(0);
  }

}