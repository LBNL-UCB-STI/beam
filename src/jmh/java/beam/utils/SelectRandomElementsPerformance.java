package beam.utils;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import scala.collection.JavaConverters$;
import scala.collection.mutable.Set;
import scala.reflect.ClassTag$;
import scala.util.Random;
import scala.reflect.ClassTag;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author Dmitry Openkov
 */
public class SelectRandomElementsPerformance {

    private static final int n = 10000;
    private static final Set<Integer> intSet = JavaConverters$.MODULE$.asScalaSet(IntStream.range(1, n).boxed().collect(Collectors.toSet()));
    private static final ClassTag<Integer> classTag = ClassTag$.MODULE$.apply(Integer.class);

    @Benchmark
    @Fork(value = 1, warmups = 1)
    @BenchmarkMode(Mode.Throughput)
    public void selectConsecutive10Percent(Blackhole bh) {
        Random rnd = new Random(1777);
        Object result = MathUtils.selectRandomConsecutiveElements(intSet, MathUtils.doubleToInt(n * 0.1), rnd, classTag);
        bh.consume(result);
    }

    @Benchmark
    @Fork(value = 1, warmups = 1)
    @BenchmarkMode(Mode.Throughput)
    public void selectConsecutive90Percent(Blackhole bh) {
        Random rnd = new Random(1777);
        Object result = MathUtils.selectRandomConsecutiveElements(intSet, MathUtils.doubleToInt(n * 0.9), rnd, classTag);
        bh.consume(result);
    }
}
