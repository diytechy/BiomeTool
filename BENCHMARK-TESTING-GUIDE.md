# Benchmark Testing Guide for Auto-Caching Fix

## Quick Start: Test the Fix

### Prerequisites
- Visual Studio Code with Claude Code extension
- Git installed and configured
- Java 17+ installed
- Gradle available

### Step 1: Verify the Fix is Applied

Check that `BiomePipelineAddon.java` has the fix:

```bash
cd C:\Projects\Terra
git diff common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/BiomePipelineAddon.java
```

Look for:
```diff
-var method = psc.getClass().getMethod("getPackSamplers");
+var method = psc.getClass().getMethod("getSamplers");
```

If you don't see this, the fix hasn't been applied yet.

### Step 2: Build Terra with the Fix

```bash
cd C:\Projects\Terra
gradlew build -x test
```

This will take 5-10 minutes. When complete, you'll have the fixed Terra build with auto-caching and diagnostic logging enabled.

### Step 3: Build BiomeTool

```bash
cd C:\Projects\BiomeTool
gradlew build
```

### Step 4: Run the Benchmark (Case 3 with Fix)

Make sure you're on the correct commits:
```bash
cd C:\Projects\Terra
git log --oneline -1
# Should show: b00368d7d Compile fix

cd C:\Projects\ORIGEN2
git log --oneline -1
# Should show: 89e73f85 Cache removal, may need to be reverted
```

Then run:
```bash
cd C:\Projects\BiomeTool
.\RunBenchmark.bat 128 128 1
```

(The default 100x100 tiles should also work; 128x128 gives more data)

### Step 5: Check the Output

Look for these diagnostic lines in the benchmark output:

**EXPECTED (Fix is working):**
```
[BiomePipelineAddon] Found PackSamplerContext, attempting to extract pack samplers
[BiomePipelineAddon] getSamplers() returned: java.util.Map
[BiomePipelineAddon] Extracted 21 pack samplers for auto-caching analysis
[BiomePipelineAddon] Successfully extracted 21 Sampler objects for auto-caching
[PipelineSamplerAnalysis] Found 21 samplers used in pipeline
[PipelineSamplerAnalysis] Pipeline sampler caching: selected 20 samplers (budget allows up to 20)
```

**NOT EXPECTED (Bug still present):**
```
[BiomePipelineAddon] ERROR extracting pack samplers: NoSuchMethodException
[BiomePipelineAddon] Returning empty sampler map - auto-caching will not be active
[PipelineSamplerAnalysis] No pack samplers available - auto-caching disabled
```

### Step 6: Check Performance

At the end of the benchmark, you'll see output like:
```
Benchmark completed:
- Generated: 128x128 tiles
- Time: X.X seconds
- Performance: Y.Y tiles/second
```

**Expected result: ≥18.3 tiles/second** (same as manual-cache version)

If you see 13.6 or lower, auto-caching is still not working. Check the diagnostic output.

---

## Full Test Matrix (All 4 Cases)

To fully validate the fix, run all four combinations:

### Case 1: Terra without auto-caching + CHIMERA with manual caches
```bash
cd C:\Projects\Terra && git checkout 09bc6ecfaf
cd C:\Projects\ORIGEN2 && git checkout 3a40124ed
cd C:\Projects\BiomeTool && gradlew build && .\RunBenchmark.bat
# Expected: 18.3 tiles/sec
```

### Case 2: Terra with auto-caching + CHIMERA with manual caches
```bash
cd C:\Projects\Terra && git checkout b00368d7d
cd C:\Projects\ORIGEN2 && git checkout 3a40124ed
cd C:\Projects\BiomeTool && gradlew build && .\RunBenchmark.bat
# Expected: 18.3 tiles/sec
```

### Case 3: Terra with fixed auto-caching + CHIMERA without manual caches
```bash
cd C:\Projects\Terra && git checkout b00368d7d
# (fix should already be stashed or applied here)
cd C:\Projects\ORIGEN2 && git checkout 89e73f852
cd C:\Projects\BiomeTool && gradlew build && .\RunBenchmark.bat
# Expected: ≥18.3 tiles/sec (THIS IS THE TEST OF THE FIX)
```

### Case 4: Terra without auto-caching + CHIMERA without manual caches
```bash
cd C:\Projects\Terra && git checkout 09bc6ecfaf
cd C:\Projects\ORIGEN2 && git checkout 89e73f852
cd C:\Projects\BiomeTool && gradlew build && .\RunBenchmark.bat
# Expected: ~13.6 tiles/sec (baseline for case 3)
```

---

## Interpreting the Results

### Ideal Outcome
```
Case 1: 18.3 T/s (manual caches, no auto-caching)
Case 2: 18.3 T/s (manual caches, auto-caching broken)
Case 3: 18.3+ T/s (no manual caches, AUTO-CACHING FIXED!)
Case 4: 13.6 T/s (no manual caches, no auto-caching)
```

This clearly shows:
- Manual caches provide ~26% performance boost
- Auto-caching (when fixed) matches manual cache performance
- Auto-caching was indeed broken (Cases 2 and 3 had same 13.6 result before fix)

### If Case 3 is Still Slow (13.6 T/s)
1. Check the diagnostic output - does it say auto-caching is active?
2. If not, check whether the fix was actually applied (see Step 1)
3. You may need to rebuild the JAR completely: `gradlew clean build`

### If Case 3 is Slightly Slower than Case 1 (e.g., 17.5 vs 18.3)
This could indicate the `volatile` keyword on `LastValueSampler.delegate` is adding minor overhead. The fix is still working, just with a small performance impact.

---

## Troubleshooting

### JAR Not Found
```
ERROR: JAR not found at C:\Projects\BiomeTool\build\libs\BiomeToolEnhanced-0.5.1-all.jar
```
Run `gradlew build` in the BiomeTool directory. The script looks for `*-all.jar` specifically.

### Java Version Error
If you get a Java version error, check your Java installation:
```bash
java -version
```

The script uses `C:\JAVA\jdk-23\bin\java.exe` by default. If that's not available, it falls back to `java` in your PATH.

### Gradle Build Failure
If `gradlew build` fails:
```bash
gradlew build --info
```

Common issues:
- Missing dependencies (usually fixed by running again)
- Java version mismatch (need Java 17+)
- IDE still has files locked (close VS Code and try again)

---

## Performance Baseline

For reference, here's what good performance looks like on this hardware:

- **18.3+ tiles/sec:** Excellent (manual caches or fixed auto-caching)
- **13.6 tiles/sec:** Poor (no caching)
- **≥25% improvement:** Expected from either manual or auto-caching

The fix should bring Case 3 from 13.6 to 18.3+, which is a **~35% improvement**.

---

## Questions?

Refer to the detailed investigation report: `AutoCaching-Investigation-Report.md`
