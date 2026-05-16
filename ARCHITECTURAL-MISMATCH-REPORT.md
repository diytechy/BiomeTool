# Auto-Caching Architectural Mismatch Report

**Date:** 2026-03-26
**Issue:** Auto-caching design incompatible with expression-based pipeline architecture
**Severity:** CRITICAL - Feature cannot work without major redesign

---

## Discovery

Auto-caching extracts 126 pack samplers successfully, but finds **zero references** to them in the biome pipeline.

### The Data

From benchmark run:
```
[BiomePipelineAddon] Successfully extracted 126 Sampler objects for auto-caching
[BiomePipelineAddon] Extracted sampler names: elevation, riverSampler, continents, ... (126 total)

[SamplerReferenceWalker] Found stage[0] sampler: ExpressionNoiseFunction
[SamplerReferenceWalker.countSampler] No match in packSamplerInstances (checked 126 entries)

[SamplerReferenceWalker] Found stage[1] sampler: ExpressionNoiseFunction
[SamplerReferenceWalker.countSampler] No match in packSamplerInstances (checked 126 entries)

... (repeated for 88 stages, all finding ExpressionNoiseFunction/DomainWarpedSampler/etc)

[SamplerReferenceWalker] Final counts: {elevation=0, riverSampler=0, continents=0, ... (all zeros)}
```

### What This Means

1. **Pack samplers ARE extracted** — The reflection fix works, and 126 samplers are loaded
2. **Stages ARE found** — The walker finds samplers in source and stages
3. **But they DON'T match** — The samplers found in stages (ExpressionNoiseFunction, etc.) are NOT the same instances as the extracted pack samplers

---

## Root Cause: Architectural Mismatch

### How Auto-Caching Was Designed

The auto-caching feature assumes stages have **direct instance references** to pack samplers:

```
Stage
  └─ sampler: Sampler (this is an INSTANCE of elevation, riverSampler, etc.)
```

The walker uses `IdentityHashMap<Sampler, String>` to check:
```
if (this_sampler_instance == extracted_elevation_instance) {
    count[elevation]++;
}
```

### How CHIMERA Actually Works

CHIMERA (and the entire Terra system) uses **expression-based evaluation**:

```
Stage
  └─ sampler: ExpressionNoiseFunction
       └─ expression: "elevation(x, z) * 0.5 + continents(x, z)"
            └─ at runtime, looks up "elevation" and "continents" by NAME in pack context
                └─ NOT direct instance references
```

The pack samplers are **referenced indirectly through expression strings**, not as direct object instances.

---

## Why Manual Caches Work

The manual `type: CACHE` wrappers in CHIMERA work because they're **in the expression evaluation path**:

```
// CHIMERA config (when using manual caches):
elevation:
  type: CACHE              // <-- This caching happens WITHIN the expression evaluator
  sampler:
    type: EXPRESSION
    expression: "..."
```

When an expression calls `elevation(x, z)`, it:
1. Looks up the sampler by name
2. Finds it wrapped in CACHE
3. The cache hits and returns the cached result

The caching happens **at expression evaluation time**, not at the instance reference level that auto-caching assumes.

---

## Why Auto-Caching Fails

The auto-caching system looks for direct references that don't exist in this architecture:

1. ✓ It extracts 126 pack samplers from PackSamplerContext
2. ✓ It walks the pipeline stages
3. ✗ **But stages only hold ExpressionNoiseFunction, not the pack samplers**
4. ✗ The identity checks fail because the walker is looking at the wrong level of abstraction
5. ✗ Result: All 126 samplers show 0 references

---

## Solutions

### Option 1: Fix the SamplerReferenceWalker (HARD)

Enhance the walker to understand ExpressionNoiseFunction:

1. When encountering an ExpressionNoiseFunction, extract its expression string
2. Parse the expression to find sampler function calls: `elevation(...)`, `continents(...)`, etc.
3. Count those references against the extracted pack samplers
4. This requires understanding the paralithic expression language and AST structure

**Complexity:** High — requires walking expression AST, understanding all possible sampler reference patterns

**Timeline:** Days or weeks of development

**Risk:** High — expressions are complex and could reference samplers in ways the walker doesn't anticipate

---

### Option 2: Disable Auto-Caching, Use Manual Caches (RECOMMENDED)

Since manual caches work perfectly and give 18.3 T/s performance:

1. Revert CHIMERA to commit `3a40124ed` (with manual caches)
2. Keep the method name fix (getSamplers) for other systems that might use auto-caching
3. Set `CACHE_THRESHOLD = 3` in OptomizePackSamplers.py to generate caches for any new packs
4. Document that CHIMERA uses manual caches, not auto-caching

**Complexity:** Minimal — one git checkout

**Timeline:** Immediate (< 1 minute)

**Result:** Performance returns to 18.3+ T/s

---

### Option 3: Redesign Auto-Caching for Expression-Based Architecture (HARD)

Redesign the entire auto-caching system to work with expression-based evaluation:

1. Instead of wrapping pack sampler instances, wrap them in the expression evaluator
2. When expressions are compiled, detect which pack samplers they call
3. Apply caching at the expression evaluation level, not the instance reference level
4. This is essentially reimplementing the manual CACHE system but automatically

**Complexity:** Very High — requires changes to expression compilation, evaluator, and config system

**Timeline:** Weeks of development

**Result:** Would work correctly with CHIMERA, but might break other systems

---

## Recommendation

**Use Option 2: Revert to manual caches.**

The auto-caching feature was designed for a different architecture than what CHIMERA uses. While Option 1 or 3 would make auto-caching work, they would require significant engineering effort with high risk.

The manual caching system:
- Already works perfectly
- Is well-tested (proven by 18.3 T/s performance)
- Is documented and understood
- Has no performance penalty

Until auto-caching is redesigned for the expression-based architecture, manual caches are the optimal solution.

---

## Summary

| Aspect | Manual Caches | Auto-Caching (Current) |
|---|---|---|
| **Status** | Working ✓ | Broken ✗ |
| **Performance** | 18.3 T/s | 13.6 T/s |
| **Architecture** | Compatible ✓ | Incompatible ✗ |
| **Effort to Fix** | None | Days-Weeks |
| **Risk** | None | High |
| **Recommendation** | Use ✓ | Disable |

---

## Implementation

To revert to manual caches:

```bash
cd C:\Projects\ORIGEN2
git checkout 3a40124ed  # Restore manual cache definitions
git log --oneline -1   # Confirm: 3a40124ed CHIMERA with manually defined cache samplers
```

Then rebuild and benchmark to confirm 18.3+ T/s performance.

---

## Technical Details for Developers

### Why the SamplerReferenceWalker Can't Find References

The walker calls `stage.getSampler()` for each stage, expecting to get pack sampler instances. But in CHIMERA:

```java
// What the walker expects to find:
Stage {
    sampler = elevation_instance  // Direct reference to the pack sampler
}

// What it actually finds:
Stage {
    sampler = ExpressionNoiseFunction {
        expression = "elevation(x, z) * 0.5"
        // "elevation" is looked up by NAME at runtime, not stored as an instance
    }
}
```

The `IdentityHashMap.get(sampler)` check fails because:
- `sampler` is an ExpressionNoiseFunction instance
- The map contains elevation, riverSampler, continents, etc. instances
- They're not the same object, so identity check fails

### What Would Be Needed to Fix It

The walker would need to:

1. Detect ExpressionNoiseFunction type
2. Extract the expression string (likely via reflection: `field.get(paralithic_expression)`)
3. Parse expressions like `"elevation(x, z) * 0.5 + continents(x, z)"` to find pack sampler calls
4. Increment reference counts for elevation, continents, etc.

This is non-trivial because:
- Expression parsing requires understanding the paralithic expression language
- Need to handle nested calls, ternary operators, variable references, etc.
- Different expression evaluators might have different structures
- Would need regex or proper AST parsing

Example detection pattern needed:
```regex
\b(elevation|riverSampler|continents|...|oneOfThe126SamplerNames)\s*\(
```

But this is brittle and doesn't handle all edge cases (expressions stored as compiled functions, dynamically generated expressions, etc.).
