package beam.playground.metasim.services;

import java.util.LinkedList;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.utils.collections.QuadTree;

import com.google.inject.Inject;

public interface LocationalServices {
	public Link getNearestRoadLink(Coord coord);
	public void finalizeInitialization();

	public class Default implements LocationalServices{
		private BeamServices beamServices;
		private QuadTree<Link> roadQuadTree;

		@Inject
		public Default(BeamServices beamServices){
			this.beamServices = beamServices;
		}
		
		public void finalizeInitialization(){
			/*
			 * Build spatial indices on network
			 */
			Network network = beamServices.getMatsimServices().getScenario().getNetwork();
			LinkedList<Link> linksToUseInRoadQuadTree = new LinkedList<>();
			Double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
			for(Link link : beamServices.getMatsimServices().getScenario().getNetwork().getLinks().values()){
				if(link.getAllowedModes().contains("car")){
					linksToUseInRoadQuadTree.add(link);
					if(link.getCoord().getX() > maxX)maxX = link.getCoord().getX();
					if(link.getCoord().getY() > maxY)maxY = link.getCoord().getY();
					if(link.getCoord().getX() < minX)minX = link.getCoord().getX();
					if(link.getCoord().getY() < minY)minY = link.getCoord().getY();
				}
			}
			roadQuadTree = new QuadTree<>(minX, minY, maxX, maxY);
			for(Link link : linksToUseInRoadQuadTree){
				roadQuadTree.put(link.getCoord().getX(),link.getCoord().getY(),link);
			}
		}

		@Override
		public Link getNearestRoadLink(Coord coord) {
			return roadQuadTree.getClosest(coord.getX(), coord.getY());
		}

	}

}
