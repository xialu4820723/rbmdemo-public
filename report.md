# Recall filter: three-way benchmark

Run command (from the repository root):

```sh
mvn test dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" com.example.rbmdemo.PerfAnalysis
```

`results.csv` is the authoritative input for result screenshots and contains all measured rows.

## Measurement scope

- Synthetic data: 5,000,000-ID universe, eight conditions, and either 50,000 or 500,000 candidates. All methods within each scenario use the same inputs.
- `short_circuit_scan`: visit candidate IDs ascending, check conditions in order, stop at first miss.
- `flat_word_and`: scan all words in equal-capacity `long[]` indexes, stop processing each word once it is zero, and enumerate set bits.
- `roaring_and`: prebuilt RoaringBitmap candidate and condition indexes, cloned candidate then intersected with each condition.
- Index and candidate preparation are excluded. Timed interval includes filtering and ascending result-ID array creation; equality checks are outside the timed interval.
- 40 warmups and 31 measurements per method and scenario, with rotating start order during measurement. Median and min/max are reported.
- Localized candidates occupy one 16-bit bucket; the dispersed workloads span the ID universe and add dense non-candidate postings. The first 10% of candidates match all conditions; every other candidate fails exactly one.

## Environment

- Java: 21.0.6; VM: OpenJDK 64-Bit Server VM
- OS/architecture: Mac OS X / aarch64

## Results (median; nanoseconds)

| Scenario | Method | Median ns | Min ns | Max ns | Result IDs | Flat index bytes | Roaring index bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| sparse-localized | short_circuit_scan | 123375 | 121292 | 139208 | 5000 | 5000000 | 134120 |
| sparse-localized | flat_word_and | 32875 | 31792 | 42209 | 5000 | 5000000 | 134120 |
| sparse-localized | roaring_and | 18625 | 18208 | 26167 | 5000 | 5000000 | 134120 |
| dense-dispersed | short_circuit_scan | 140625 | 136583 | 163875 | 5000 | 5000000 | 5051264 |
| dense-dispersed | flat_word_and | 248083 | 238583 | 273584 | 5000 | 5000000 | 5051264 |
| dense-dispersed | roaring_and | 176083 | 166959 | 219959 | 5000 | 5000000 | 5051264 |
| dense-more-candidates | short_circuit_scan | 1318625 | 1200375 | 1398042 | 50000 | 5000000 | 5051264 |
| dense-more-candidates | flat_word_and | 635042 | 531208 | 671292 | 50000 | 5000000 | 5051264 |
| dense-more-candidates | roaring_and | 1205791 | 1140084 | 1269459 | 50000 | 5000000 | 5051264 |

Every returned array is checked against expected ascending IDs. Index bytes cover the eight conditions only: fixed-width word payload for flat Bitmap, serialized bytes for Roaring. These are representation sizes, not Java heap measurements.
