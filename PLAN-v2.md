# MC-MCP — Vanilla AI Architect & Engineer

## Mission

Extend the existing MC-MCP project into an intelligent Minecraft architecture and engineering system, focusing exclusively on **Vanilla Minecraft 1.21.1**.

The system should enable an AI assistant such as Claude or Codex to:

- Understand the surrounding Minecraft terrain.
- Independently plan coherent architectural structures.
- Generate and preview complete buildings.
- Visually inspect its own constructions.
- Identify and correct architectural defects.
- Design and construct functional Vanilla Redstone machinery.
- Test whether those mechanisms behave as intended.
- Safely modify, undo, and improve existing constructions.

**Do not implement Create Mod integration, modded machinery, or third-party engineering systems.**

WorldEdit may remain an optional building utility, but all generated structures and mechanisms must use Vanilla Minecraft blocks and mechanics.

## 1. Existing Architecture

Preserve the existing structure:

- Java Fabric Mod — world operations and game integration.
- Rust MCP Bridge — tool registration and communication.
- Existing JavaScript building engine.
- Existing preview, blueprint, screenshot, undo, and job systems.

Read `USAGE.md`, `protocol/spec.md`, `docs/script-api.md`, and the actual source code before making changes.

Do not rewrite existing working components.

The AI model should handle planning and judgment. The Minecraft mod should handle deterministic geometry, world-state inspection, construction, and simulation-related operations.

---

## Phase 1 — Intelligent Architectural Planning

**Priority: P0**

Implement terrain-aware architectural planning.

### Terrain understanding

Extend the existing world inspection capabilities to analyze:

- Heightmaps and terrain slopes.
- Buildable surfaces.
- Water and environmental obstacles.
- Existing structures.
- Building orientation.
- Suitable foundations.
- Road and entrance accessibility.
- Terrain modification requirements.

Introduce `analyze_build_site`, reusing existing scanning operations.

The AI should recommend building locations based on available space, terrain constraints, and the user's requirements.

### Building design system

Introduce a structured, versioned building-plan format.

A building plan should define:

- Global origin and bounding box.
- Building style and materials.
- Overall footprint and height.
- Number of floors.
- Individual rooms and functions.
- Doors, windows, and staircases.
- Roofing and foundations.
- Outdoor structures and landscaping.
- Relationships between components.

Build reusable components for walls, roofs, staircases, rooms, doors, windows, arches, and towers.

Each component should have a stable identifier to support later modification.

### Architectural validation

Implement deterministic checks for:

- Unreachable rooms.
- Incomplete walls.
- Missing stairs.
- Inconsistent floor heights.
- Incorrectly oriented blocks.
- Collisions with existing structures.
- Floating architectural elements.
- Missing entrances.
- Violations of allowed building regions.

Compile validated designs into existing building scripts or blueprints.

**Acceptance test:** Given an uneven landscape, the AI can design and preview a complete two-story house with a usable interior, stairs, roof, doors, and windows.

---

## Phase 2 — Autonomous Visual Iteration

**Priority: P0**

Create an AI-controlled feedback workflow:

**Plan → Preview → Observe → Evaluate → Revise → Preview Again → Build**

Reuse existing `screenshot`, `orbit_shots`, `preview`, `run_script`, and `undo` tools.

### Visual inspection

Allow the AI to inspect structures from multiple viewpoints:

- Front view.
- Rear view.
- Left and right views.
- Elevated perspective.
- Interior views where possible.

Evaluate:

- Architectural proportions.
- Structural completeness.
- Roof consistency.
- Window and door alignment.
- Material and style consistency.
- Terrain integration.
- Visual symmetry where appropriate.

### Iterative modification

The AI should produce structured feedback identifying which components need changes.

Implement targeted modifications rather than rebuilding everything.

Example:

"The second-floor windows are too small and the roof is asymmetrical."

Expected behavior:

1. Identify the affected components.
2. Generate modification operations.
3. Apply modifications to the preview.
4. Capture new screenshots.
5. Compare against the original design requirements.
6. Continue until requirements pass or an iteration limit is reached.

Use a configurable default limit of three autonomous revision rounds.

Require user confirmation before committing destructive world modifications.

### Structural and visual verification

Use block-level inspection to verify objective correctness.

Use vision models for subjective architectural judgments.

Do not assume visual inspection alone proves structural correctness.

**Acceptance test:** The AI can detect an intentionally malformed roof, modify the relevant components, and verify the corrected preview without rebuilding the entire house.

---

## Phase 3 — Vanilla Redstone Engineering

**Priority: P1**

Enable the AI to design, construct, and debug functional Redstone mechanisms using only Vanilla Minecraft components.

### 3.1 Redstone understanding

Implement `inspect_redstone_network`.

Inspect:

- Redstone dust.
- Repeaters and comparators.
- Redstone torches.
- Levers and buttons.
- Pistons and sticky pistons.
- Observers.
- Hoppers, droppers, and dispensers.
- Redstone lamps.
- Relevant block states and power levels.

Where feasible, derive a connectivity graph that describes how signals are transmitted.

Avoid assuming that physical adjacency automatically implies a valid connection.

### 3.2 Engineering design

Introduce reusable, testable Redstone modules.

Start with:

1. Lever-controlled lighting.
2. Logic gates.
3. Timers and pulse generators.
4. Piston doors.
5. Automatic item sorters.
6. Basic automated farms.

Each module should include:

- Required blocks.
- Spatial layout.
- Input and output definitions.
- Expected behavior.
- Test procedure.
- Known mechanical constraints.

### 3.3 Simulation and debugging

Implement safe testing tools, such as:

- `inspect_redstone_network`
- `trigger_redstone_input`
- `observe_redstone_state`
- `test_redstone_circuit`

The AI should be able to:

1. Construct a circuit.
2. Trigger its inputs.
3. Observe relevant blocks over game ticks.
4. Compare actual and expected output states.
5. Locate likely faults.
6. Apply a targeted fix.
7. Retest the circuit.

Investigate how the existing suppressed neighbor-update behavior affects Redstone functionality.

Ensure Redstone mechanisms receive the necessary updates while keeping changes bounded and safe.

Do not claim a circuit works solely because its blocks were placed successfully.

**Acceptance test:** The AI can design, construct, activate, and verify a working 2×2 piston door.

---

## Phase 4 — Unified AI Construction Workflow

**Priority: P1**

Create a reusable MCP workflow that supports both architecture and Redstone engineering.

The workflow should:

1. Interpret the user's request.
2. Inspect the world.
3. Determine architectural and functional requirements.
4. Generate a structured plan.
5. Validate geometry and relevant engineering constraints.
6. Create a preview.
7. Inspect and revise the preview.
8. Request user confirmation.
9. Execute the construction.
10. Verify the final result.
11. Repair errors or report unresolved problems.

Keep the reasoning workflow on the AI client side rather than hardcoding model-specific API calls into the Fabric mod.

Support follow-up instructions such as:

- "Make the building three blocks taller."
- "Replace the entrance with an automatic piston door."
- "Add a Redstone-controlled lighting system."
- "Fix the broken item sorter."
- "Connect the farm output to the storage room."

Preserve previous constructions unless the user explicitly requests their replacement.

---

## Phase 5 — Testing, Reliability, and Safety

**Priority: Continuous**

### Testing

Maintain and extend existing Minecraft GameTests and Rust tests.

Required test scenarios:

- Terrain-aware house generation.
- Architectural component validation.
- Visual defect detection and correction.
- Redstone power propagation.
- Functional piston-door operation.
- Targeted structure modification.
- Building cancellation and undo.
- Invalid coordinates and protected regions.
- Interrupted building jobs.

Clearly distinguish automated deterministic tests from tests that require a connected vision model.

### Safety

Preserve:

- Localhost authentication.
- Allowed-region restrictions.
- Block blacklists.
- Operation volume limits.
- Sandboxed building scripts.
- Building previews.
- Undo and redo functionality.

Do not allow AI-generated scripts to access arbitrary host files or execute operating-system commands through Minecraft tools.

### Performance

Prefer high-level construction commands over individual block tool calls.

Use bounded scans, incremental revisions, and existing asynchronous building jobs.

Avoid blocking Minecraft's main thread with expensive planning or analysis.

---

## Implementation Priorities

| Order | Component | Priority |
|---|---|---|
| 1 | Source audit and baseline tests | P0 |
| 2 | Terrain analysis | P0 |
| 3 | Structured architectural plans | P0 |
| 4 | Architectural validation | P0 |
| 5 | Autonomous visual revision loop | P0 |
| 6 | Redstone network inspection | P1 |
| 7 | Redstone functional testing | P1 |
| 8 | Unified construction workflow | P1 |
| 9 | Reliability and performance improvements | Ongoing |

## Instructions to Claude Code

Begin by inspecting the current repository and identifying reusable implementations.

Proceed incrementally, starting with terrain analysis and structured architectural planning.

For each completed phase:

1. Implement actual working code.
2. Add or update relevant tests.
3. Run the tests and report real results.
4. Update the MCP tool documentation.
5. Provide examples of how to use the new capabilities.
6. Summarize changed files and known limitations.

Do not stop after generating a conceptual design.

Do not introduce Create Mod dependencies, modded-machine APIs, or unnecessary compatibility layers.

Do not change the Minecraft version without a compelling technical reason.

**Primary objective:** Turn MC-MCP into a capable Vanilla Minecraft AI architect and Redstone engineer that can autonomously plan, construct, inspect, test, and improve its creations.

Start with Phase 0 repository inspection and Phase 1 implementation.
