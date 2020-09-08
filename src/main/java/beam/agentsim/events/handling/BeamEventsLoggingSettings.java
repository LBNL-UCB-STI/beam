package beam.agentsim.events.handling;

import org.matsim.api.core.v01.events.Event;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public interface BeamEventsLoggingSettings {

    boolean shouldLogThisEventType(Class<? extends Event> aClass);
    Set<Class<?>> getAllEventsToLog();
    Set<String> getKeysToWrite(Event event, Map<String, String> eventAttributes);

    /** Makes settings instance out of lambdas. */
    static BeamEventsLoggingSettings create(
        final Function<Class<? extends Event>, Boolean> shouldLogThisEventType,
        final Supplier<Set<Class<?>>> getAllEventsToLog,
        final BiFunction<Event, Map<String, String>, Set<String>> getKeysToWrite
    ) {
        return new Impl(shouldLogThisEventType, getAllEventsToLog, getKeysToWrite);
    }

    /** Convenient BeamEventsLoggingSettings impl on lambdas. */
    final class Impl implements BeamEventsLoggingSettings {

        private final Function<Class<? extends Event>, Boolean> shouldLogThisEventType;
        private final Supplier<Set<Class<?>>> getAllEventsToLog;
        private final BiFunction<Event, Map<String, String>, Set<String>> getKeysToWrite;

        public Impl(final Function<Class<? extends Event>, Boolean> shouldLogThisEventType,
                    final Supplier<Set<Class<?>>> getAllEventsToLog,
                    final BiFunction<Event, Map<String, String>, Set<String>> getKeysToWrite) {
            this.shouldLogThisEventType = shouldLogThisEventType;
            this.getAllEventsToLog = getAllEventsToLog;
            this.getKeysToWrite = getKeysToWrite;
        }

        @Override
        public boolean shouldLogThisEventType(final Class<? extends Event> aClass) {
            return shouldLogThisEventType.apply(aClass);
        }

        @Override
        public Set<Class<?>> getAllEventsToLog() {
            return getAllEventsToLog.get();
        }

        @Override
        public Set<String> getKeysToWrite(final Event event, final Map<String, String> eventAttributes) {
            return getKeysToWrite.apply(event, eventAttributes);
        }
    }
}
