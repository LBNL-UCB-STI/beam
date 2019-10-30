package beam.matsim;

import beam.sim.BeamConfigChangesObservable;
import beam.sim.config.BeamConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.config.groups.LinkStatsConfigGroup;
import scala.Tuple2;

import java.util.Observable;
import java.util.Observer;

@Singleton
public class MatsimConfigUpdater implements Observer {
    private ControlerConfigGroup controlerConfigGroup;
    private LinkStatsConfigGroup linkStatsConfigGroup;

    @Inject
    MatsimConfigUpdater(BeamConfigChangesObservable beamConfigChangesObservable, LinkStatsConfigGroup linkStatsConfigGroup, ControlerConfigGroup controlerConfigGroup) {
        this.controlerConfigGroup = controlerConfigGroup;
        this.linkStatsConfigGroup = linkStatsConfigGroup;

        beamConfigChangesObservable.addObserver(this);
    }

    @Override
    public void update(Observable observable, Object o) {
        if (o instanceof Tuple2) {
            Tuple2 t = (Tuple2) o;
            if (t._2 instanceof BeamConfig) {
                BeamConfig beamConfig = (BeamConfig) t._2;

                controlerConfigGroup.setWritePlansInterval(beamConfig.beam().physsim().writePlansInterval());
                linkStatsConfigGroup.setWriteLinkStatsInterval(beamConfig.matsim().modules().linkStats().writeLinkStatsInterval());
            }
        }
    }
}
