# Non-Map GUI Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rebuild the non-map RoadWeaver GUIs so list sizing works on 1.21.1, dimension pickers populate reliably, and config screens share a cleaner, more maintainable UI foundation.

**Architecture:** Introduce small shared UI helpers for list widgets, panel rendering, and dimension presentation, then migrate the config screens and widgets onto those helpers. Fix discovery refresh on the client so structure and dimension data stay live instead of depending on stale cache files.

**Tech Stack:** Java 21, Architectury, Minecraft 1.21.1 client GUI APIs, Fabric/NeoForge mixins.

---

### Task 1: Shared list and panel helpers

**Files:**
- Create: `common/src/main/java/net/shiroha233/roadweaver/client/config/RoadWeaverSelectionList.java`
- Create: `common/src/main/java/net/shiroha233/roadweaver/client/config/DimensionUiHelper.java`
- Create: `common/src/main/java/net/shiroha233/roadweaver/client/render/RoadWeaverUi.java`

**Step 1:** Add a shared selection-list base that always passes correct item height on 1.21.1.

**Step 2:** Add shared dimension helpers for discovery refresh, sorting, fallback dimensions, and localized row building.

**Step 3:** Add shared panel/header rendering helpers for consistent screen chrome.

### Task 2: Refactor list widgets

**Files:**
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/DimensionListWidget.java`
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/PresetListWidget.java`
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/MultiDimensionListWidget.java`
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/StructureListWidget.java`

**Step 1:** Migrate the widgets to the shared list base.

**Step 2:** Remove duplicated layout code and standardize row styling.

**Step 3:** Fix any remaining 1.21.1 row-height regressions.

### Task 3: Refactor config screens

**Files:**
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/StructureSelectionScreen.java`
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/MaterialPresetEditorScreen.java`
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/DimensionRoadSettingsScreen.java`
- Modify: `common/src/main/java/net/shiroha233/roadweaver/client/config/StructurePredictionDimensionWhitelistScreen.java`

**Step 1:** Switch screens to shared dimension helpers.

**Step 2:** Use consistent panel rendering, title/subtitle layout, and footer spacing.

**Step 3:** Keep behavior intact while reducing duplicated dropdown and layout code.

### Task 4: Restore client-side structure discovery refresh

**Files:**
- Modify: `common/src/main/java/net/shiroha233/roadweaver/config/structure/StructureDiscoveryService.java`
- Modify: `fabric/src/main/java/net/shiroha233/roadweaver/mixin/fabric/CreateWorldScreenInitMixin.java`

**Step 1:** Restore reflection-based client discovery fallback.

**Step 2:** Update Fabric create-world discovery injection for 1.21.1-safe timing.

**Step 3:** Ensure UI reads prefer fresh data before stale cache.

### Task 5: Verify

**Files:**
- Test: `common`, `fabric`, `neoforge` modules via Gradle compile tasks

**Step 1:** Run `./gradlew.bat :common:compileJava :fabric:compileJava :neoforge:compileJava`.

**Step 2:** Relaunch the Fabric client and spot-check non-map config screens.

**Step 3:** Confirm map GUI files stay untouched.
