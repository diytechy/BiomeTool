# BiomeTool Dependency Analysis

## Problem Summary

BiomeTool fails at runtime with:
```
No such com.dfsek.terra.api.addon.BaseAddon matching "biome-provider-pipeline" was found in this registry.
```

This occurs because BiomeTool's `build.gradle.kts` fetches Terra addon JARs from remote Maven repositories using a specific git hash (`af9fb211a`), but these artifacts are either unavailable or incompatible.

## Technical Details

### BiomeTool's Dependency Structure

Location: `C:\Projects\BiomeTool\build.gradle.kts`

BiomeTool uses a `terraGitHash` variable (line 102) to construct version strings:
```kotlin
val terraGitHash = "af9fb211a"
terraAddon("com.dfsek.terra:biome-provider-pipeline:1.0.2-BETA+$terraGitHash")
```

This pulls from these repositories:
- `https://repo.codemc.org/repository/maven-public/`
- `https://jitpack.io`

### Terra's Build Structure

Location: `C:\Projects\Terra`

Terra builds addons locally as subprojects:
- Addons live in `C:\Projects\Terra\common\addons\`
- Each addon has its own `build.gradle.kts`
- Version strings are generated via `Utils.kt`:
  ```kotlin
  fun Project.version(version: String): String {
      return if (!isPrerelease) version
      else "$version-BETA+${getGitHash()}"
  }
  ```

Terra publishes to `https://maven.solo-studios.ca/releases/` but requires credentials.

### Key Addons Required by BiomeTool

| Addon | BiomeTool Version | Terra Version |
|-------|-------------------|---------------|
| biome-provider-pipeline | 1.0.2-BETA | 2.0.0 |
| biome-provider-pipeline v2 | 1.0.1-BETA | N/A (merged) |
| api-addon-loader | 0.1.0-BETA | - |
| manifest-addon-loader | 1.0.0-BETA | - |
| base | 6.6.2-BETA | 7.0.0 |

**Version mismatch**: BiomeTool uses Terra 6.6.2, but Terra has moved to 7.0.0 with significant API changes.

## Solution Options

### Option 1A: Local Maven Publication (Recommended First Step)

Build Terra locally and publish to local Maven cache:

```bash
cd C:\Projects\Terra
.\gradlew.bat build
.\gradlew.bat publishToMavenLocal
```

Then modify BiomeTool's `build.gradle.kts`:
1. Add `mavenLocal()` to repositories (before other maven repos)
2. Update `terraGitHash` to match Terra's current git hash

**Steps:**
1. Get Terra's current git hash: `git rev-parse --short HEAD`
2. Run Terra build: `.\gradlew.bat build publishToMavenLocal`
3. Edit BiomeTool's `build.gradle.kts`:
   - Add `mavenLocal()` to repositories block
   - Update `terraGitHash` to new hash
   - May need to update addon version numbers to match Terra 7.0.0
4. Rebuild BiomeTool: `.\gradlew.bat build`

**Pros:** Quickest path to testing
**Cons:** May require API compatibility fixes due to Terra 6.x to 7.x changes

### Option 1B: Direct JAR Copy

Copy built addon JARs directly into BiomeTool's runtime directory:

```bash
cd C:\Projects\Terra
.\gradlew.bat build

# Copy addon JARs to BiomeTool's addons directory
# From: C:\Projects\Terra\common\addons\*\build\libs\*.jar
# To: C:\Projects\BiomeTool\build\run\addons\
```

Modify BiomeTool to skip Maven resolution for these addons.

**Pros:** Bypasses Maven entirely
**Cons:** Manual, not sustainable long-term

### Option 1B-Alt: Composite Build

Add Terra as a Gradle composite build in BiomeTool:

```kotlin
// settings.gradle.kts
includeBuild("C:/Projects/Terra") {
    dependencySubstitution {
        substitute(module("com.dfsek.terra:biome-provider-pipeline"))
            .using(project(":common:addons:biome-provider-pipeline"))
        // ... repeat for other addons
    }
}
```

**Pros:** Automatic dependency resolution, stays in sync
**Cons:** Complex setup, requires understanding both build systems

### Option 2: Python Reimplementation

Create a standalone Python tool to:
1. Parse Terra pack YAML files
2. Evaluate noise functions and biome pipelines
3. Render biome distribution visualization

**Pros:** Independence from Java ecosystem
**Cons:** Significant effort, must reverse-engineer pack format

## Recommended Approach

1. **Immediate**: Try Option 1A (local Maven publication)
   - This validates whether the issue is purely dependency availability vs API compatibility

2. **If API errors occur**: Assess scope of changes needed in BiomeTool to support Terra 7.0.0
   - May be minimal if Terra maintained backward compatibility
   - May require significant refactoring if APIs changed substantially

3. **Long-term**: Consider Option 1B-Alt (composite build) for sustainable development

## Files to Modify (Option 1A)

### C:\Projects\BiomeTool\build.gradle.kts

```kotlin
repositories {
    mavenLocal()  // ADD THIS FIRST
    mavenCentral()
    maven {
        name = "CodeMC"
        url = uri("https://repo.codemc.org/repository/maven-public/")
    }
    // ...
}

// Update to match Terra's current hash
val terraGitHash = "<NEW_HASH>"

// May need version updates:
implementation("com.dfsek.terra:base:7.0.0-BETA+$terraGitHash")
// ... addon versions may change
```

## Current State

- **Terra git hash**: `116453772` (as of this analysis)
- **Local Maven cache**: No Terra artifacts present yet (`~/.m2/repository/com/dfsek/terra/` does not exist)
- **BiomeTool expects hash**: `af9fb211a` (significantly older)

## Resolution (Completed 2026-01-08)

The issue has been resolved using **Option 1A** (local Maven publication).

### Steps Taken

1. **Built Terra's addon modules** and published to local Maven:
   ```bash
   cd C:\Projects\Terra
   .\publish_to_maven_local.bat
   ```
   (This script publishes core API, base implementation, and all 35 addon modules)

2. **Updated BiomeTool's `build.gradle.kts`**:
   - Added `mavenLocal()` as first repository
   - Added Solo Studios repository: `https://maven.solo-studios.ca/releases`
   - Changed `terraGitHash` from `af9fb211a` to `116453772`
   - Updated `base` version from `6.6.2-BETA` to `7.0.0-BETA`
   - Updated addon versions (removed v2- prefixed addons, updated biome-provider-image/pipeline to 2.0.0)

3. **Fixed API compatibility issue** in `BiomeToolPlatform.kt`:
   - Changed `rawConfigRegistry.loadAll(this)` to `loadConfigPacks()`
   - The `ConfigRegistry.loadAll()` signature changed from returning `boolean` to `void`

### Verification

BiomeTool now:
- Compiles successfully
- Loads all 34 Terra addons (including biome-provider-pipeline)
- Parses config packs correctly
- Runs the JavaFX GUI without errors

### Files Modified

- `C:\Projects\BiomeTool\build.gradle.kts` - Updated dependencies and repositories
- `C:\Projects\BiomeTool\src\main\kotlin\com\dfsek\terra\biometool\BiomeToolPlatform.kt` - Fixed reload() method
- `C:\Projects\Terra\publish_to_maven_local.bat` - New script to publish Terra modules to local Maven

### Additional Fixes

1. **Standalone JAR addon distribution**: Added `prepareDistAddons` task to copy addon JARs to `build/libs/addons/` so the standalone JAR works without `gradlew run`.

2. **Missing NBT library**: Added `com.github.Querz:NBT:6.1` as a direct dependency - required by the `structure-sponge-loader` addon.

### Running BiomeTool

**Via Gradle:**
```bash
cd C:\Projects\BiomeTool
.\gradlew.bat run
```

**Standalone JAR:**
```bash
cd C:\Projects\BiomeTool\build\libs
C:\JAVA\jdk-23\bin\java -jar BiomeTool-0.4.9-win.jar
```
