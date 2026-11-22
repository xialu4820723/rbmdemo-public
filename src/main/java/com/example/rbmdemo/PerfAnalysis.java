package com.example.rbmdemo;

import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.Function;

/** Same-input recall-filter benchmark: scan, flat word-wise bitmap AND, and Roaring AND. */
public final class PerfAnalysis {
    static final int UNIVERSE = 5_000_000;
    static final int CANDIDATE_COUNT = 50_000;
    static final int CONDITION_COUNT = 8;
    private static final int WARMUPS = 40;
    private static final int MEASUREMENTS = 31;
    private static volatile int[] resultSink;

    private PerfAnalysis() {}

    /** Immutable common input and prebuilt indexes for all three implementations. */
    static final class Workload {
        final int universe;
        private final int[] candidateIds;
        private final BitSet[] conditions;
        private final long[] candidateWords;
        private final long[][] flatWords;
        private final RoaringBitmap roaringCandidates;
        private final RoaringBitmap[] roaringConditions;
        private final long flatBytes;
        private final long roaringBytes;

        Workload(int universe, BitSet candidates, BitSet[] conditions) {
            if (universe < 0 || candidates.length() > universe) {
                throw new IllegalArgumentException("bits outside universe");
            }
            if (conditions.length == 0) throw new IllegalArgumentException("at least one condition required");
            this.universe = universe;
            this.candidateIds = candidates.stream().toArray();
            this.conditions = new BitSet[conditions.length];
            int wordCount = Math.toIntExact((universe + 63L) / 64L);
            this.candidateWords = new long[wordCount];
            long[] sourceCandidates = candidates.toLongArray();
            System.arraycopy(sourceCandidates, 0, candidateWords, 0, Math.min(sourceCandidates.length, wordCount));
            this.flatWords = new long[conditions.length][wordCount];
            this.roaringConditions = new RoaringBitmap[conditions.length];
            this.roaringCandidates = new RoaringBitmap();
            long roaringSize = 0;

            for (int id = candidates.nextSetBit(0); id >= 0; id = candidates.nextSetBit(id + 1)) {
                roaringCandidates.add(id);
            }
            for (int i = 0; i < conditions.length; i++) {
                if (conditions[i].length() > universe) throw new IllegalArgumentException("bits outside universe");
                this.conditions[i] = (BitSet) conditions[i].clone();
                long[] words = conditions[i].toLongArray();
                System.arraycopy(words, 0, flatWords[i], 0, Math.min(words.length, wordCount));
                RoaringBitmap roaring = new RoaringBitmap();
                for (int id = conditions[i].nextSetBit(0); id >= 0; id = conditions[i].nextSetBit(id + 1)) {
                    roaring.add(id);
                }
                roaringConditions[i] = roaring;
                roaringSize += roaring.serializedSizeInBytes();
            }
            this.flatBytes = (long) wordCount * Long.BYTES * conditions.length;
            this.roaringBytes = roaringSize;
        }

        /** Candidate-by-candidate short-circuit evaluation; IDs are emitted ascending. */
        int[] scan() {
            int[] result = new int[candidateIds.length];
            int size = 0;
            for (int id : candidateIds) {
                boolean match = true;
                for (BitSet condition : conditions) {
                    if (!condition.get(id)) {
                        match = false;
                        break;
                    }
                }
                if (match) result[size++] = id;
            }
            return Arrays.copyOf(result, size);
        }

        /** Intersects equal-capacity long arrays, then enumerates set bits in ascending order. */
        int[] flatAnd() {
            int[] result = new int[candidateIds.length];
            int size = 0;
            for (int wordIndex = 0; wordIndex < candidateWords.length; wordIndex++) {
                long bits = candidateWords[wordIndex];
                for (long[] condition : flatWords) {
                    if (bits == 0) break;
                    bits &= condition[wordIndex];
                }
                while (bits != 0) {
                    result[size++] = (wordIndex << 6) + Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                }
            }
            return Arrays.copyOf(result, size);
        }

        /** Intersects a prebuilt Roaring candidate bitmap with the same eight indexes. */
        int[] roaringAnd() {
            RoaringBitmap result = roaringCandidates.clone();
            for (RoaringBitmap condition : roaringConditions) result.and(condition);
            return result.toArray();
        }

        long flatBytes() { return flatBytes; }
        long roaringBytes() { return roaringBytes; }

        void verify(int[] expected) {
            if (!Arrays.equals(expected, scan()) || !Arrays.equals(expected, flatAnd())
                    || !Arrays.equals(expected, roaringAnd())) {
                throw new AssertionError("implementations disagree with expected ascending IDs");
            }
        }
    }

    private static final class Timing {
        final String scenario;
        final String implementation;
        final long medianNanos;
        final long minNanos;
        final long maxNanos;
        final int resultCount;
        final int universe;
        final int candidateCount;
        final int conditionCount;
        final long flatBytes;
        final long roaringBytes;

        Timing(String scenario, String implementation, long[] samples, int resultCount, Workload workload) {
            this.scenario = scenario;
            this.implementation = implementation;
            long[] sorted = samples.clone();
            Arrays.sort(sorted);
            this.medianNanos = sorted[sorted.length / 2];
            this.minNanos = sorted[0];
            this.maxNanos = sorted[sorted.length - 1];
            this.resultCount = resultCount;
            this.universe = workload.universe;
            this.candidateCount = workload.candidateIds.length;
            this.conditionCount = workload.conditions.length;
            this.flatBytes = workload.flatBytes();
            this.roaringBytes = workload.roaringBytes();
        }
    }

    private static final class Method {
        final String name;
        final Function<Workload, int[]> run;

        Method(String name, Function<Workload, int[]> run) {
            this.name = name;
            this.run = run;
        }
    }

    public static void main(String[] args) throws IOException {
        Path csv = Path.of(args.length > 0 ? args[0] : "results.csv");
        Path report = Path.of(args.length > 1 ? args[1] : "report.md");
        List<Timing> timings = new ArrayList<>();
        runScenario(timings, "sparse-localized", false, CANDIDATE_COUNT);
        runScenario(timings, "dense-dispersed", true, CANDIDATE_COUNT);
        runScenario(timings, "dense-more-candidates", true, 500_000);
        String csvData = buildCsv(timings);
        Files.writeString(csv, csvData, StandardCharsets.UTF_8);
        Files.writeString(report, buildReport(timings), StandardCharsets.UTF_8);
        System.out.printf("Wrote %s and %s; last result count=%d%n", csv, report, resultSink.length);
    }

    private static void runScenario(List<Timing> timings, String scenario, boolean dispersed, int candidateCount) {
        Workload workload = createWorkload(dispersed, candidateCount);
        int[] expected = new int[candidateCount / 10];
        for (int i = 0; i < expected.length; i++) expected[i] = candidateId(i, dispersed, candidateCount);
        workload.verify(expected);
        timings.addAll(benchmark(scenario, workload, expected));
    }

    private static int candidateId(int i, boolean dispersed, int candidateCount) {
        return dispersed ? i * (UNIVERSE / candidateCount) : 800_000 + i;
    }

    private static Workload createWorkload(boolean dispersed, int candidateCount) {
        BitSet candidates = new BitSet(UNIVERSE);
        BitSet[] conditions = new BitSet[CONDITION_COUNT];
        for (int c = 0; c < CONDITION_COUNT; c++) conditions[c] = new BitSet(UNIVERSE);

        for (int i = 0; i < candidateCount; i++) {
            int id = candidateId(i, dispersed, candidateCount);
            candidates.set(id);
            // The first 10% of candidates form a known non-empty intersection.
            // Later candidates fail at condition (i % conditionCount), avoiding skew from a condition-0-first scan.
            for (int c = 0; c < CONDITION_COUNT; c++) {
                if (i < candidateCount / 10 || c != i % CONDITION_COUNT) conditions[c].set(id);
            }
        }

        if (dispersed) {
            // Dense condition indexes, without changing which candidate IDs match.
            int stride = UNIVERSE / candidateCount;
            for (int c = 0; c < CONDITION_COUNT; c++) {
                for (int id = c; id < UNIVERSE; id += 2) {
                    if (id % stride != 0) conditions[c].set(id);
                }
            }
        } else {
            // Small unrelated localized postings exercise sparse, compact Roaring containers.
            for (int c = 0; c < CONDITION_COUNT; c++) {
                int start = 1_000_000 + c * 20_000;
                conditions[c].set(start, start + 10_000);
            }
        }
        return new Workload(UNIVERSE, candidates, conditions);
    }

    private static List<Timing> benchmark(String scenario, Workload workload, int[] expected) {
        List<Method> methods = List.of(
                new Method("short_circuit_scan", Workload::scan),
                new Method("flat_word_and", Workload::flatAnd),
                new Method("roaring_and", Workload::roaringAnd));
        for (int warmup = 0; warmup < WARMUPS; warmup++) {
            for (Method method : methods) consumeAndCheck(method.run.apply(workload), expected);
        }

        long[][] samples = new long[methods.size()][MEASUREMENTS];
        for (int round = 0; round < MEASUREMENTS; round++) {
            for (int offset = 0; offset < methods.size(); offset++) {
                int methodIndex = (round + offset) % methods.size();
                Method method = methods.get(methodIndex);
                long start = System.nanoTime();
                int[] actual = method.run.apply(workload);
                long elapsed = System.nanoTime() - start;
                samples[methodIndex][round] = elapsed;
                consumeAndCheck(actual, expected);
            }
        }

        List<Timing> timings = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++) {
            timings.add(new Timing(scenario, methods.get(i).name, samples[i], expected.length, workload));
        }
        return timings;
    }

    private static void consumeAndCheck(int[] actual, int[] expected) {
        resultSink = actual;
        if (!Arrays.equals(actual, expected)) throw new AssertionError("benchmark result mismatch");
    }

    private static String buildCsv(List<Timing> timings) {
        StringBuilder csv = new StringBuilder("scenario,implementation,median_ns,min_ns,max_ns,measurements,universe,candidates,conditions,result_count,flat_index_bytes,roaring_index_bytes\n");
        for (Timing t : timings) {
            csv.append(String.format(java.util.Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    t.scenario, t.implementation, t.medianNanos, t.minNanos, t.maxNanos, MEASUREMENTS,
                    t.universe, t.candidateCount, t.conditionCount,
                    t.resultCount, t.flatBytes, t.roaringBytes));
        }
        return csv.toString();
    }

    private static String buildReport(List<Timing> timings) {
        StringBuilder md = new StringBuilder();
        md.append("# Recall filter: three-way benchmark\n\n")
          .append("Run command (from the repository root):\n\n")
          .append("```sh\n")
          .append("mvn test dependency:build-classpath -Dmdep.outputFile=target/classpath.txt\n")
          .append("java -cp \"target/classes:$(cat target/classpath.txt)\" com.example.rbmdemo.PerfAnalysis\n")
          .append("```\n\n")
          .append("`results.csv` is the authoritative input for result screenshots and contains all measured rows.\n\n")
          .append("## Measurement scope\n\n")
          .append("- Synthetic data: 5,000,000-ID universe, eight conditions, and either 50,000 or 500,000 candidates. All methods within each scenario use the same inputs.\n")
          .append("- `short_circuit_scan`: visit candidate IDs ascending, check conditions in order, stop at first miss.\n")
          .append("- `flat_word_and`: scan all words in equal-capacity `long[]` indexes, stop processing each word once it is zero, and enumerate set bits.\n")
          .append("- `roaring_and`: prebuilt RoaringBitmap candidate and condition indexes, cloned candidate then intersected with each condition.\n")
          .append("- Index and candidate preparation are excluded. Timed interval includes filtering and ascending result-ID array creation; equality checks are outside the timed interval.\n")
          .append("- ").append(WARMUPS).append(" warmups and ").append(MEASUREMENTS).append(" measurements per method and scenario, with rotating start order during measurement. Median and min/max are reported.\n")
          .append("- Localized candidates occupy one 16-bit bucket; the dispersed workloads span the ID universe and add dense non-candidate postings. The first 10% of candidates match all conditions; every other candidate fails exactly one.\n\n")
          .append("## Environment\n\n")
          .append("- Java: ").append(System.getProperty("java.version")).append("; VM: ").append(System.getProperty("java.vm.name")).append("\n")
          .append("- OS/architecture: ").append(System.getProperty("os.name")).append(" / ").append(System.getProperty("os.arch")).append("\n\n")
          .append("## Results (median; nanoseconds)\n\n")
          .append("| Scenario | Method | Median ns | Min ns | Max ns | Result IDs | Flat index bytes | Roaring index bytes |\n")
          .append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (Timing t : timings) {
            md.append(String.format(java.util.Locale.ROOT, "| %s | %s | %d | %d | %d | %d | %d | %d |%n",
                    t.scenario, t.implementation, t.medianNanos, t.minNanos, t.maxNanos,
                    t.resultCount, t.flatBytes, t.roaringBytes));
        }
        md.append("\nEvery returned array is checked against expected ascending IDs. Index bytes cover the eight conditions only: fixed-width word payload for flat Bitmap, serialized bytes for Roaring. These are representation sizes, not Java heap measurements.\n");
        return md.toString();
    }
}
