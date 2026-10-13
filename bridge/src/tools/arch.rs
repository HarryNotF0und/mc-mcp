//! Architectural planning tools (v2 phase 1).

use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::CallToolResult;
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{McServer, params};

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AnalyzeSiteArgs {
    /// Centre of the search area [x, y, z]. Default: the player's position.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub center: Option<[i32; 3]>,
    /// Search radius in blocks (4-48), default 24.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub radius: Option<u32>,
    /// Planned footprint [width, depth] (width along the front), default [9, 7].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub footprint: Option<[u32; 2]>,
    /// Space to keep around the footprint (terrace), default 2.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub margin: Option<u32>,
    /// Preferred entrance direction (north/south/east/west); kept unless clearly worse.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub facing: Option<String>,
    /// Number of candidate sites to return (1-10), default 3.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top: Option<u32>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ValidateBuildingArgs {
    /// Building plan ("format": "mcmcp.building/1"); see resource mc://docs/building-plan.
    pub building: Value,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[tool_router(router = arch_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Find where to build. Scans the terrain around a point (ground height ignoring trees, water, lava, \
existing builds, roads) and returns: a summary (height range, slope, buildable fraction), a character map \
(. flat ^ steep ~ water T tree # built = road), and the best placements for a footprint, each with a ready-to-use \
building-plan origin and facing, the floor level, cut/fill volumes, foundation depth and entrance accessibility. \
Example: {\"footprint\":[11,9],\"radius\":24,\"facing\":\"south\"}")]
    async fn analyze_build_site(&self, Parameters(args): Parameters<AnalyzeSiteArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("site.analyze", params(&args)).await)
    }

    #[tool(description = "Compile and check a building plan without placing it. Read resource mc://docs/building-plan \
for the format (and mc://examples/two-story-house for a complete example). Returns block counts, terrain work, \
per-component bounds, and validation: errors/warnings with codes (REGION, COLLISION, FLOATING, INCOMPLETE_WALL, \
ROOF_GAP, ROOF_ORIENTATION, DOOR_ORIENTATION, MISSING_ENTRANCE, MISSING_STAIRS, STAIRS_BLOCKED, UNREACHABLE_ROOM, ...), \
the components involved and world positions, plus which floors and rooms can be walked to. Fix the named components \
(or add a patch) and validate again; then preview / place_blueprint with {\"building\": ...}.")]
    async fn validate_building(&self, Parameters(args): Parameters<ValidateBuildingArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("arch.validate", params(&args)).await)
    }
}
