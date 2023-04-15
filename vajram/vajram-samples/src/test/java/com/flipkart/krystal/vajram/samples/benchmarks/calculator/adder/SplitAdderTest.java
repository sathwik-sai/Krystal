package com.flipkart.krystal.vajram.samples.benchmarks.calculator.adder;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.flipkart.krystal.vajram.VajramID.vajramID;
import static com.flipkart.krystal.vajram.samples.Util.javaFuturesBenchmark;
import static com.flipkart.krystal.vajram.samples.Util.javaMethodBenchmark;
import static com.flipkart.krystal.vajram.samples.benchmarks.calculator.adder.Adder.add;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flipkart.krystal.krystex.node.ObservabilityConfig;
import com.flipkart.krystal.krystex.node.ObservationData;
import com.flipkart.krystal.vajram.samples.benchmarks.calculator.adder.ChainAdderTest.RequestContext;
import com.flipkart.krystal.vajramexecutor.krystex.KrystexVajramExecutor;
import com.flipkart.krystal.vajramexecutor.krystex.VajramNodeGraph;
import com.flipkart.krystal.vajramexecutor.krystex.VajramNodeGraph.Builder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SplitAdderTest {
  private Builder graph;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    graph = loadFromClasspath("com.flipkart.krystal.vajram.samples.benchmarks.calculator");
    objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .setSerializationInclusion(NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Test
  void splitAdder_success() throws Exception {
    CompletableFuture<Integer> future;
    ObservationData observationData;
    try (KrystexVajramExecutor<RequestContext> krystexVajramExecutor =
        graph.build().createExecutor(new RequestContext(""), new ObservabilityConfig(true, true))) {
      future = executeVajram(krystexVajramExecutor, 0);
      observationData = krystexVajramExecutor.getKrystalExecutor().getObservationData();
    }
    assertThat(future.get()).isEqualTo(55);
    System.out.println(
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(observationData));
  }

  // @Test
  void vajram_benchmark() throws ExecutionException, InterruptedException, TimeoutException {
    int loopCount = 50_000;
    VajramNodeGraph graph = this.graph.maxParallelismPerCore(1).build();
    long javaNativeTime = javaMethodBenchmark(this::splitAdd, loopCount);
    long javaFuturesTime = javaFuturesBenchmark(this::splitAddAsync, loopCount);
    CompletableFuture<Integer>[] futures = new CompletableFuture[loopCount];
    long startTime = System.nanoTime();
    long timeToCreateExecutors = 0;
    long timeToEnqueueVajram = 0;
    for (int value = 0; value < loopCount; value++) {
      long iterStartTime = System.nanoTime();
      try (KrystexVajramExecutor<RequestContext> krystexVajramExecutor =
          graph.createExecutor(new RequestContext(""))) {
        timeToCreateExecutors += System.nanoTime() - iterStartTime;
        long enqueueStart = System.nanoTime();
        futures[value] = executeVajram(krystexVajramExecutor, value);
        timeToEnqueueVajram += System.nanoTime() - enqueueStart;
      }
    }
    System.out.printf("Avg. time to Create Executors:%,d %n", timeToCreateExecutors / loopCount);
    System.out.printf("Avg. time to Enqueue vajrams:%,d %n", timeToEnqueueVajram / loopCount);
    allOf(futures).join();
    long vajramTime = System.nanoTime() - startTime;
    System.out.printf("Avg. time to execute vajrams:%,d%n", vajramTime / loopCount);
    System.out.printf(
        "Platform overhead over native code: %,.0f ns per request%n",
        (1.0 * vajramTime - javaNativeTime) / loopCount);
    /*
     * Benchmark config:
     *    loopCount = 50_000
     *    maxParallelismPerCore = 1
     *    Processor: 2.6 GHz 6-Core Intel Core i7
     * Benchmark result:
     *    platform overhead = ~300 µs per request
     *    maxPoolSize = 12
     *    maxActiveLeasesPerObject: 4114
     *    peakAvgActiveLeasesPerObject: 4110.5
     */
    System.out.printf(
        "Platform overhead over reactive code: %,.0f ns per request%n",
        (1.0 * vajramTime - javaFuturesTime) / loopCount);
    allOf(futures)
        .whenComplete(
            (unused, throwable) -> {
              for (int i = 0; i < futures.length; i++) {
                CompletableFuture<Integer> future = futures[i];
                assertThat(future.getNow(0)).isEqualTo((i * 100) + 55);
              }
            })
        .get();
    System.out.printf(
        "maxActiveLeasesPerObject: %s, peakAvgActiveLeasesPerObject: %s, maxPoolSize: %s%n",
        graph.getExecutorPool().maxActiveLeasesPerObject(),
        graph.getExecutorPool().peakAvgActiveLeasesPerObject(),
        graph.getExecutorPool().maxPoolSize());
  }

  private static CompletableFuture<Integer> executeVajram(
      KrystexVajramExecutor<RequestContext> krystexVajramExecutor, int multiplier) {
    return krystexVajramExecutor.execute(
        vajramID(SplitAdder.ID),
        rc ->
            SplitAdderRequest.builder()
                .numbers(
                    new ArrayList<>(
                        Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                            .map(integer -> integer + multiplier * 10)
                            .toList()))
                .build(),
        "chainAdderTest" + multiplier);
  }

  private void splitAdd(Integer value) {
    splitAdd(
        new ArrayList<>(
            Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .map(integer -> integer + value * 10)
                .toList()));
  }

  private int splitAdd(List<Integer> numbers) {
    if (numbers.size() == 0) {
      return 0;
    } else if (numbers.size() == 1) {
      return add(numbers.get(0), 0);
    } else {
      int subListSize = numbers.size() / 2;
      return splitAdd(numbers.subList(0, subListSize))
          + splitAdd(numbers.subList(subListSize, numbers.size()));
    }
  }

  private CompletableFuture<Integer> splitAddAsync(Integer value) {
    return splitAddAsync(
        new ArrayList<>(
            Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .map(integer -> integer + value * 10)
                .toList()));
  }

  private CompletableFuture<Integer> splitAddAsync(List<Integer> numbers) {
    if (numbers.size() == 0) {
      return completedFuture(0);
    } else if (numbers.size() == 1) {
      return addAsync(numbers.get(0), 0);
    } else {
      int subListSize = numbers.size() / 2;
      return splitAddAsync(numbers.subList(0, subListSize))
          .thenCombine(splitAddAsync(numbers.subList(subListSize, numbers.size())), Integer::sum);
    }
  }

  private CompletableFuture<Integer> addAsync(int a, int b) {
    return completedFuture(a + b);
  }

  private static Builder loadFromClasspath(String... packagePrefixes) {
    Builder builder = VajramNodeGraph.builder();
    Arrays.stream(packagePrefixes).forEach(builder::loadFromPackage);
    return builder;
  }
}
