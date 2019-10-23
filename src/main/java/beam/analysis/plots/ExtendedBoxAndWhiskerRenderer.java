package beam.analysis.plots;

import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.entity.ChartEntity;
import org.jfree.chart.entity.EntityCollection;
import org.jfree.chart.event.RendererChangeEvent;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.Outlier;
import org.jfree.chart.renderer.category.BoxAndWhiskerRenderer;
import org.jfree.chart.renderer.category.CategoryItemRendererState;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.statistics.BoxAndWhiskerCategoryDataset;
import org.jfree.ui.RectangleEdge;

public class ExtendedBoxAndWhiskerRenderer extends BoxAndWhiskerRenderer {
	
	private static final long serialVersionUID = -8932682032311494648L;

	/** The color used to paint the outliers. */
    private transient Paint outlierPaint;
    
    /** The color used to paint the farout values. */
    private transient Paint faroutPaint;      
    
	public ExtendedBoxAndWhiskerRenderer(){
		super();
		this.outlierPaint = Color.red;
		this.faroutPaint = Color.gray;		
	}
	
	 /**
     * Returns the paint used to color the outliers.
     * 
     * @return The paint used to draw the outliers (never
     *     <code>null</code>).
     *
     * @see #setOutlierPaint(Paint)
     */
	public Paint getOutlierPaint(){
		return this.outlierPaint;
	}
	
	 /**
     * Sets the paint used to color outliers and sends
     * a {@link RendererChangeEvent} to all registered listeners.
     * 
     * @param paint  the paint (<code>null</code> not permitted).
     *
     * @see #getOutlierPaint()
     */
	public void setOutlierPaint(Paint paint){
		if (paint == null) {
			throw new IllegalArgumentException("Null 'paint' argument.");
		}
	    this.outlierPaint = paint;
	    notifyListeners(new RendererChangeEvent(this));
	}
	
	 /**
     * Returns the paint used to color the farout values.
     * 
     * @return The paint used to draw the farout values (never
     *     <code>null</code>).
     *
     * @see #setFaroutPaint(Paint)
     */
	public Paint getFaroutPaint(){
		return this.faroutPaint;
	}
	
	 /**
     * Sets the paint used to color farout values and sends
     * a {@link RendererChangeEvent} to all registered listeners.
     * 
     * @param paint  the paint (<code>null</code> not permitted).
     *
     * @see #getFaroutPaint()
     */
	public void setFaroutPaint(Paint paint){
		if (paint == null) {
			throw new IllegalArgumentException("Null 'paint' argument.");
	    }
        this.faroutPaint = paint;
        notifyListeners(new RendererChangeEvent(this));
	}
			
	/**
     * Draws the visual representation of a single data item when the plot has 
     * a vertical orientation.
     *
     * @param g2  the graphics device.
     * @param state  the renderer state.
     * @param dataArea  the area within which the plot is being drawn.
     * @param plot  the plot (can be used to obtain standard color information 
     *              etc).
     * @param domainAxis  the domain axis.
     * @param rangeAxis  the range axis.
     * @param dataset  the dataset.
     * @param row  the row index (zero-based).
     * @param column  the column index (zero-based).
     */
    public void drawVerticalItem(Graphics2D g2, 
                                 CategoryItemRendererState state,
                                 Rectangle2D dataArea,
                                 CategoryPlot plot, 
                                 CategoryAxis domainAxis, 
                                 ValueAxis rangeAxis,
                                 CategoryDataset dataset, 
                                 int row, 
                                 int column) {
    	
    	//do nothing if item is not visible
        if (!getItemVisible(row, column)) {
            return;   
        }
       
    	 //Determine the catgory start and end.
    	 BoxAndWhiskerCategoryDataset bawDataset = (BoxAndWhiskerCategoryDataset) dataset;
 
		 double categoryEnd = domainAxis.getCategoryEnd(column, 
		         getColumnCount(), dataArea, plot.getDomainAxisEdge());
		 double categoryStart = domainAxis.getCategoryStart(column, 
		         getColumnCount(), dataArea, plot.getDomainAxisEdge());
		 double categoryWidth = categoryEnd - categoryStart;

		 double xx = categoryStart;
		 int seriesCount = getRowCount();
		 int categoryCount = getColumnCount();

		 if (seriesCount > 1) {
		     double seriesGap = dataArea.getWidth() * getItemMargin() 
		                        / (categoryCount * (seriesCount - 1));
		     double usedWidth = (state.getBarWidth() * seriesCount) 
		                        + (seriesGap * (seriesCount - 1));
		     // offset the start of the boxes if the total width used is smaller
		     // than the category width
		     double offset = (categoryWidth - usedWidth) / 2;
		     xx = xx + offset + (row * (state.getBarWidth() + seriesGap));
		 } 
		 else {
		     // offset the start of the box if the box width is smaller than the 
		     // category width
		     double offset = (categoryWidth - state.getBarWidth()) / 2;
		     xx = xx + offset;
		 } 
		 double xxmid = xx + state.getBarWidth() / 2.0;
		 
		 //Draw the box.
		 Paint p = getItemPaint(row, column);
         if (p != null) {
        	 g2.setPaint(p);
         }
	     Stroke s = getItemStroke(row, column);
	     g2.setStroke(s);
	     
	     RectangleEdge location = plot.getRangeAxisEdge();
         Shape box = null;
                 
	     Number yQ1 = bawDataset.getQ1Value(row, column);
		 Number yQ3 = bawDataset.getQ3Value(row, column);
		 Number yMax = bawDataset.getMaxRegularValue(row, column);
		 Number yMin = bawDataset.getMinRegularValue(row, column);
        
         if (yQ1 != null && yQ3 != null && yMax != null && yMin != null) {
     		
		     double yyQ1 = rangeAxis.valueToJava2D(yQ1.doubleValue(), dataArea, location);
		     double yyQ3 = rangeAxis.valueToJava2D(yQ3.doubleValue(), dataArea, location);
		     double yyMax = rangeAxis.valueToJava2D(yMax.doubleValue(), dataArea, location);
		     double yyMin = rangeAxis.valueToJava2D(yMin.doubleValue(), dataArea, location);

		     // draw the upper whisker
		     g2.draw(new Line2D.Double(xxmid, yyMax, xxmid, yyQ3));
		     g2.draw(new Line2D.Double(xx, yyMax, xx + state.getBarWidth(), yyMax));
		
		     // draw the lower whisker
		     g2.draw(new Line2D.Double(xxmid, yyMin, xxmid, yyQ1));
		     g2.draw(new Line2D.Double(xx, yyMin, xx + state.getBarWidth(), yyMin));
		
		     // draw the body
		     box = new Rectangle2D.Double(xx, Math.min(yyQ1, yyQ3), 
		             state.getBarWidth(), Math.abs(yyQ1 - yyQ3));
		     
		     if (getFillBox()) {
		         g2.fill(box);
		     }
		     g2.draw(box);		    		
		 }                 
                
         // draw mean 
         g2.setPaint(getArtifactPaint());
         double yyAverage;
         double aRadius = 2.0; // mean radius                       
         Number yMean = bawDataset.getMeanValue(row, column);
         if (yMean != null) {
             yyAverage = rangeAxis.valueToJava2D(yMean.doubleValue(), dataArea, location);             
             Ellipse2D.Double avgEllipse = new Ellipse2D.Double((xxmid - aRadius), 
                     (yyAverage - aRadius), aRadius * 2, aRadius * 2);            
             g2.fill(avgEllipse);
             g2.draw(avgEllipse);
         }
         
         //draw median
         double yyMedian = 0.0;
         Number yMedian = bawDataset.getMedianValue(row, column);		 
		 if (yMedian != null) {
		     yyMedian = rangeAxis.valueToJava2D(yMedian.doubleValue(), dataArea, location);
		     g2.draw(new Line2D.Double(xx, yyMedian, xx + state.getBarWidth(), yyMedian));
		 }
		
		 //ensure that tooltip is generated if box is null and median is not null.
		 if(box == null && yMedian != null){			 			 
			 box = new Rectangle2D.Double(xx, yyMedian, state.getBarWidth(), yyMedian);			 
		 }
         
         //Outliers and Farouts		 			 
		 double oRadius = 2.0; //outlier radius
		 double foRadius = 4.0; //farout radius
			
		 // From outlier array sort out which are outliers and put these into a 
		 // list. If there are any farouts, add them to the farout list		 
		 //Draw the outliers and farouts only if they are within the data area.
		 double yyOutlier;
		 double yyFarout;		 
		 List<Outlier> outliers = new ArrayList<>();
		 List<Outlier> farOutValues = new ArrayList<>();
		 List yOutliers = bawDataset.getOutliers(row, column);		 		 
		 if (yOutliers != null) {
		     for (Object outlierObject : yOutliers) {
		    	 Number outlierNum = (Number) outlierObject;
		         double outlier = outlierNum.doubleValue();
		         Number minOutlier = bawDataset.getMinOutlier(row, column);
		         Number maxOutlier = bawDataset.getMaxOutlier(row, column);
		         Number minRegular = bawDataset.getMinRegularValue(row, column);
		         Number maxRegular = bawDataset.getMaxRegularValue(row, column);
		         if (outlier > maxOutlier.doubleValue() || outlier < minOutlier.doubleValue()) {		             
		        	 yyFarout = rangeAxis.valueToJava2D(outlier, dataArea, location);
		        	 Outlier faroutToAdd = new Outlier(xxmid, yyFarout, foRadius);
		        	 if(dataArea.contains(faroutToAdd.getPoint())) {
		        		 farOutValues.add(faroutToAdd);
		        	 }
		         } 		        
		         else if (outlier > maxRegular.doubleValue() || outlier < minRegular.doubleValue()) {
		             yyOutlier = rangeAxis.valueToJava2D(outlier, dataArea, location);
		             Outlier outlierToAdd = new Outlier(xxmid, yyOutlier, oRadius);
		             if(dataArea.contains(outlierToAdd.getPoint())) {
		            	 outliers.add(outlierToAdd);
		             }
		         }		        		         
		     }			   
		     
		     //draw the outliers
		     g2.setPaint(this.outlierPaint);
		     outliers.forEach(outlier -> {
				 Point2D point = outlier.getPoint();
				 Shape dot = createEllipse(point, oRadius);
				 g2.draw(dot);
			 });

		     //draw the farout values
		     g2.setPaint(this.faroutPaint);
		     farOutValues.forEach(outlier -> {
				 Point2D point = outlier.getPoint();
				 Shape triangle = createTriangleVertical(point, foRadius);
				 g2.draw(triangle);
			 });
		 }	
		 
		 //collect entity and tool tip information...		
		 if (state.getInfo() != null) { 
			 EntityCollection entities = state.getEntityCollection();
			 if (entities != null) {
				 //box tooltip				 
	        	 if(box != null) {
	        		 addItemEntity(entities, dataset, row, column, box);
	        	 }
	        	
	        	//outlier tooltips 	        	        
	        	for(Outlier outlier: outliers) {
	        		 Point2D point = outlier.getPoint();
			    	 Shape dot = createEllipse(point, oRadius);			    	        	
			    	 String outlierTooltip = "Outlier : ";		
			    	 double outlierY = rangeAxis.java2DToValue(outlier.getY() + oRadius, dataArea, location);
			    	 outlierTooltip = outlierTooltip.concat(Double.toString(outlierY));	
		     		 addOutlierEntity(outlierTooltip, entities, dataset, row, column, dot );		     		 
		        }
	        	
	        	//farout tooltips	        	        
	        	for(Outlier farout: farOutValues) {
	        		 Point2D point = farout.getPoint();
			    	 Shape triangle = createTriangleVertical(point, foRadius);	        	 
			    	 String faroutTooltip = "Farout : ";		
			    	 double faroutY = rangeAxis.java2DToValue(farout.getY() + foRadius, dataArea, location);
			    	 faroutTooltip = faroutTooltip.concat(Double.toString(faroutY));
		     		 addFaroutEntity(faroutTooltip, entities, dataset, row, column, triangle );		     		 
		        }	        	
		     }	        
		}		
    }
    
    /**
     * Draws the visual representation of a single data item when the plot has 
     * a horizontal orientation.
     *
     * @param g2  the graphics device.
     * @param state  the renderer state.
     * @param dataArea  the area within which the plot is being drawn.
     * @param plot  the plot (can be used to obtain standard color 
     *              information etc).
     * @param domainAxis  the domain axis.
     * @param rangeAxis  the range axis.
     * @param dataset  the dataset.
     * @param row  the row index (zero-based).
     * @param column  the column index (zero-based).
     */
    public void drawHorizontalItem(Graphics2D g2,
                                   CategoryItemRendererState state,
                                   Rectangle2D dataArea,
                                   CategoryPlot plot,
                                   CategoryAxis domainAxis,
                                   ValueAxis rangeAxis,
                                   CategoryDataset dataset,
                                   int row,
                                   int column) {
    	
    	//do nothing if item is not visible
        if (!getItemVisible(row, column)) {
            return;   
        }
        
    	//Determine the catgory start and end.
    	BoxAndWhiskerCategoryDataset bawDataset = (BoxAndWhiskerCategoryDataset) dataset;

		double categoryEnd = domainAxis.getCategoryEnd(column, 
		        getColumnCount(), dataArea, plot.getDomainAxisEdge());
		double categoryStart = domainAxis.getCategoryStart(column, 
		        getColumnCount(), dataArea, plot.getDomainAxisEdge());
		double categoryWidth = Math.abs(categoryEnd - categoryStart);

		double yy = categoryStart;
		int seriesCount = getRowCount();
		int categoryCount = getColumnCount();

		if (seriesCount > 1) {
		    double seriesGap = dataArea.getWidth() * getItemMargin()
		                       / (categoryCount * (seriesCount - 1));
		    double usedWidth = (state.getBarWidth() * seriesCount) 
		                       + (seriesGap * (seriesCount - 1));
		    // offset the start of the boxes if the total width used is smaller
		    // than the category width
		    double offset = (categoryWidth - usedWidth) / 2;
		    yy = yy + offset + (row * (state.getBarWidth() + seriesGap));
		} 
		else {
		    // offset the start of the box if the box width is smaller than 
		    // the category width
		    double offset = (categoryWidth - state.getBarWidth()) / 2;
		    yy = yy + offset;
		}
		double yymid = yy + state.getBarWidth() / 2.0;
		
		//Draw the box.
		Paint p = getItemPaint(row, column);
        if (p != null) {
        	g2.setPaint(p);
        }
	    Stroke s = getItemStroke(row, column);
	    g2.setStroke(s);
	     
	    RectangleEdge location = plot.getRangeAxisEdge();
        Shape box = null;
        
        Number xQ1 = bawDataset.getQ1Value(row, column);
        Number xQ3 = bawDataset.getQ3Value(row, column);
        Number xMax = bawDataset.getMaxRegularValue(row, column);
        Number xMin = bawDataset.getMinRegularValue(row, column);
        if (xQ1 != null && xQ3 != null && xMax != null && xMin != null) {
            double xxQ1 = rangeAxis.valueToJava2D(xQ1.doubleValue(), dataArea, location);
            double xxQ3 = rangeAxis.valueToJava2D(xQ3.doubleValue(), dataArea, location);
            double xxMax = rangeAxis.valueToJava2D(xMax.doubleValue(), dataArea, location);
            double xxMin = rangeAxis.valueToJava2D(xMin.doubleValue(), dataArea, location);
                        
            // draw the upper whisker
            g2.draw(new Line2D.Double(xxMax, yymid, xxQ3, yymid));
            g2.draw(new Line2D.Double(xxMax, yy, xxMax, yy + state.getBarWidth()));

            // draw the lower whisker
            g2.draw(new Line2D.Double(xxMin, yymid, xxQ1, yymid));
            g2.draw(new Line2D.Double(xxMin, yy, xxMin, yy + state.getBarWidth()));

            // draw the box...
            box = new Rectangle2D.Double(Math.min(xxQ1, xxQ3), yy, 
                    Math.abs(xxQ1 - xxQ3), state.getBarWidth());
            
            if (getFillBox()) {
                g2.fill(box);
            } 
            g2.draw(box);
        }
               
        //Draw the mean
        g2.setPaint(getArtifactPaint());
        double aRadius = 2.0;// average radius
        double xxMean;
        Number xMean = bawDataset.getMeanValue(row, column);
        if (xMean != null) {
            xxMean = rangeAxis.valueToJava2D(xMean.doubleValue(), dataArea, location);            
            Ellipse2D.Double avgEllipse = new Ellipse2D.Double((xxMean - aRadius), 
            		(yymid - aRadius), aRadius * 2, aRadius * 2);
            g2.fill(avgEllipse);
            g2.draw(avgEllipse);
        }
        
        //Draw the median        
        Number xMedian = bawDataset.getMedianValue(row, column);
        double xxMedian = 0.0;
        if (xMedian != null) {
            xxMedian = rangeAxis.valueToJava2D(xMedian.doubleValue(), dataArea, location);
            g2.draw(new Line2D.Double(xxMedian, yy, xxMedian, yy + state.getBarWidth()));
        }
                
        //ensure that tooltip is generated if box is null and median is not null.
		if(box == null && xMedian != null){			 			 
			box = new Rectangle2D.Double(xxMedian, yy, xxMedian, state.getBarWidth());			 
		}
		 
        //Outliers and Farouts		 			 		 
        double oRadius = 2.0; //outlier radius			
        double foRadius = 4.0; //farout radius
		// From outlier array sort out which are outliers and put these into a 
		// list. If there are any farouts, add them to the farout list		 
		double xxOutlier;
		double xxFarout;
		List<Outlier> outliers = new ArrayList<>();
		List<Outlier> farOutValues = new ArrayList<>();
		List xOutliers = bawDataset.getOutliers(row, column);		 		
		if (xOutliers != null) {
			for (Object outlierObject: xOutliers) {
		         double outlier = ((Number) outlierObject).doubleValue();
		         Number minOutlier = bawDataset.getMinOutlier(row, column);
		         Number maxOutlier = bawDataset.getMaxOutlier(row, column);
		         Number minRegular = bawDataset.getMinRegularValue(row, column);
		         Number maxRegular = bawDataset.getMaxRegularValue(row, column);
		         if (outlier > maxOutlier.doubleValue() || outlier < minOutlier.doubleValue()) {		             
		        	 xxFarout = rangeAxis.valueToJava2D(outlier, dataArea, location);		        	 
		        	 Outlier faroutToAdd = new Outlier(xxFarout + (2*foRadius), yymid, foRadius);
		        	 if(dataArea.contains(faroutToAdd.getPoint())) {
		        		 farOutValues.add(faroutToAdd);
		        	 }		            
		         } 		        
		         else if (outlier > maxRegular.doubleValue() || outlier < minRegular.doubleValue()) {
		             xxOutlier = rangeAxis.valueToJava2D(outlier, dataArea, location);
		             Outlier outlierToAdd =new Outlier(xxOutlier, yymid, oRadius);
		             if(dataArea.contains(outlierToAdd.getPoint())) {
		            	 outliers.add(outlierToAdd);
		             }		            
		         }		        		         
		    }	
		     
		    //draw the outliers
		    g2.setPaint(this.outlierPaint);

			outliers.forEach(outlier -> {
				Point2D point = outlier.getPoint();
				Shape dot = createEllipse(point, oRadius);
				g2.draw(dot);
			});

		    //draw the farout values
		    g2.setPaint(this.faroutPaint);		     

		    farOutValues.forEach(outlier -> {
				Point2D point = outlier.getPoint();
				Shape triangle = createTriangleHorizontal(point, foRadius );
				g2.draw(triangle);
			});
		}
		
		//collect entity and tooltip information		    	
		if (state.getInfo() != null) { 
			 EntityCollection entities = state.getEntityCollection();
			 if (entities != null) {
				 //box tooltip
	        	 if(box != null) {
	        		 addItemEntity(entities, dataset, row, column, box);
	        	 }
	        	
	        	//outlier tooltips	        	 
	        	for(Outlier outlier: outliers) {
			    	 Point2D point = outlier.getPoint();
			    	 Shape dot = createEllipse(point, oRadius);			    	        	
			    	 String outlierTooltip = "Outlier : ";		
			    	 double outlierX = rangeAxis.java2DToValue(outlier.getX() + oRadius, dataArea, location);		     		 
			    	 outlierTooltip = outlierTooltip.concat(Double.toString(outlierX));
		     		 addOutlierEntity(outlierTooltip, entities, dataset, row, column, dot );		     		 
		        }
	        		        	 
	        	//farout tooltips	        	
	        	for(Outlier farout: farOutValues) {
	        		 Point2D point = farout.getPoint();
			    	 Shape triangle = createTriangleHorizontal(point, foRadius);	        	 
			    	 String faroutTooltip = "Farout : ";		
			    	 double faroutX = rangeAxis.java2DToValue(farout.getX() - foRadius, dataArea, location);			    	 
			    	 faroutTooltip = faroutTooltip.concat(Double.toString(faroutX));
			    	 addFaroutEntity(faroutTooltip, entities, dataset, row, column, triangle );		     		 
		        }	        	
	        }	
		}
    }
    
    private Shape createEllipse(Point2D point, double oRadius) {
    	return new Ellipse2D.Double(point.getX(), point.getY(), oRadius*2.0, oRadius*2.0);
    }
           
    private Shape createTriangleVertical(Point2D point, double foRadius) {
    	double side = foRadius * 2;
    	double x = point.getX();
    	double y = point.getY();
    	
    	int[] xpoints = {(int)(x), (int)(x + side), (int)(x +(side /2.0)) };
    	int[] ypoints = {(int)(y), (int)(y), (int) (y + side)};
    	
    	return new Polygon(xpoints, ypoints, 3); 
    }   
    
    private Shape createTriangleHorizontal(Point2D point, double foRadius) {
    	double side = foRadius * 2;
    	double x = point.getX();
    	double y = point.getY();
    	
    	int[] xpoints = {(int)(x), (int)(x), (int)(x - side) };
    	int[] ypoints = {(int)(y), (int)(y + side), (int) (y + (side/2.0))};
    	
    	return new Polygon(xpoints, ypoints, 3); 
    }
    
    public void addOutlierEntity(String tooltip, EntityCollection entities, CategoryDataset dataset,
    							int row, int column, Shape hotspot){
    	 String url = null;
         if (getItemURLGenerator(row, column) != null) {
        	url = getItemURLGenerator(row, column).generateURL(dataset, row, column);
         }	                   	
         
         OutlierEntity entity = new OutlierEntity(hotspot, tooltip, url);
 		 entities.add(entity);
    }
    
    public void addFaroutEntity(String tooltip, EntityCollection entities, CategoryDataset dataset,
    							int row, int column, Shape hotspot){
    	 String url = null;
         if (getItemURLGenerator(row, column) != null) {
        	url = getItemURLGenerator(row, column).generateURL(dataset, row, column);
         }	                   	
         
         FaroutEntity entity = new FaroutEntity(hotspot, tooltip, url);
         entities.add(entity);
    }

	public static class FaroutEntity extends ChartEntity {

		private static final long serialVersionUID = 1204971308440301740L;

		public FaroutEntity(Shape area, String toolTipText, String urlText) {
			super(area, toolTipText, urlText);
		}
	}

	public static class OutlierEntity extends ChartEntity implements Cloneable, Serializable {

		private static final long serialVersionUID = 8595743822914721782L;

		public OutlierEntity(Shape area, String toolTipText, String urlText) {
			super(area, toolTipText, urlText);
		}
	}
}
