package beam.utils.clustering.kmeans;

import java.util.List;

public class Point2D {
      
      private float x;
      private float y;
      
      public Point2D(float x, float y) {
          this.x = x;
          this.y = y;
      }
      
      public double getDistance(Point2D other) {
          return Math.sqrt(Math.pow(this.x - other.x, 2)
                  + Math.pow(this.y - other.y, 2));
      }
      
      public int getNearestPointIndex(List<Point2D> points) {
          int index = -1;
          double minDist = Double.MAX_VALUE;
          for (int i = 0; i < points.size(); i++) {
              double dist = this.getDistance(points.get(i));
              if (dist < minDist) {
                  minDist = dist;
                  index = i;
              }
          }
          return index;
      }
      
      public static Point2D getMean(List<Point2D> points) {
          float accumX = 0;
          float accumY = 0;
          if (points.size() == 0) return new Point2D(accumX, accumY);
          for (Point2D point : points) {
              accumX += point.x;
              accumY += point.y;
          }
          return new Point2D(accumX / points.size(), accumY / points.size());
      }
      
      @Override
      public String toString() {
          return "[" + this.x + "," + this.y + "]";
      }
      
      @Override
      public boolean equals(Object obj) {
          if (obj == null || !(obj.getClass() != Point2D.class)) {
              return false;
          }
          Point2D other = (Point2D) obj;
          return this.x == other.x && this.y == other.y;
      }
      
  }