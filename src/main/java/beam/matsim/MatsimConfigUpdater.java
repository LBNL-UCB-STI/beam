package beam.matsim;

import beam.sim.BeamConfigChangesObservable;
import beam.sim.BeamConfigChangesObserver;
import beam.sim.config.BeamConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.config.groups.LinkStatsConfigGroup;
import scala.Tuple2;

import java.util.Observable;
import java.util.Observer;

@Singleton
public class MatsimConfigUpdater implements BeamConfigChangesObserver {
    private final ControlerConfigGroup controlerConfigGroup;
    private final LinkStatsConfigGroup linkStatsConfigGroup;

    @Inject
    MatsimConfigUpdater(BeamConfigChangesObservable beamConfigChangesObservable, LinkStatsConfigGroup linkStatsConfigGroup, ControlerConfigGroup controlerConfigGroup) {
        this.controlerConfigGroup = controlerConfigGroup;
        this.linkStatsConfigGroup = linkStatsConfigGroup;

        beamConfigChangesObservable.addObserver(this);
    }

    @Override
    public void update(BeamConfigChangesObservable observable, BeamConfig updatedBeamConfig) {
        controlerConfigGroup.setWritePlansInterval(updatedBeamConfig.beam().physsim().writePlansInterval());
        linkStatsConfigGroup.setWriteLinkStatsInterval(updatedBeamConfig.matsim().modules().linkStats().writeLinkStatsInterval());
    }
}
