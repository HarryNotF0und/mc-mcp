use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::CallToolResult;
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{McServer, Region, params};

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PlaceBlocksArgs {
    /// Up to 4096 entries, each [x, y, z, "block"], e.g. [100, 64, 200, "minecraft:oak_planks"].
    pub blocks: Vec<Value>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct FillRegionArgs {
    /// Box to fill; both corners inclusive.
    pub region: Region,
    /// Block state, e.g. "minecraft:stone_bricks" or "minecraft:oak_log[axis=x]".
    pub block: String,
    /// "solid" (default): every block. "hollow": shell gets the block, interior becomes air.
    /// "outline": only the shell; the interior is left untouched.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClearRegionArgs {
    /// Box to set to air; both corners inclusive.
    pub region: Region,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

/// Shared tail of every write tool's description (a macro so it can go into `concat!`).
macro_rules! write_notes {
    () => {
        " Each call is one undoable change (see undo). Placement does not trigger block updates: \
no falling sand, no redstone; fences/walls/panes still connect. Doors and beds need both halves placed explicitly. \
Returns when the job finishes: {jobId, state, changed, changeSetId, ...}; very large jobs may return state \"running\" \
— then poll job_status. Errors: 1001 outside allowed region, 1002 too many blocks (limit 262144), \
1003 unknown or blacklisted block, 1004 chunk not loaded."
    };
}

#[tool_router(router = build_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = concat!("Place individual blocks (up to 4096 per call) at world coordinates. \
Example: {\"blocks\":[[100,64,200,\"minecraft:stone_bricks\"],[100,65,200,\"minecraft:oak_stairs[facing=north]\"]]}. \
Block states use game syntax: \"minecraft:<id>[prop=value,...]\". For boxes use fill_region instead.", write_notes!()))]
    async fn place_blocks(&self, Parameters(args): Parameters<PlaceBlocksArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("world.placeBlocks", params(&args)).await)
    }

    #[tool(description = concat!("Fill a box with one block. mode: solid (default), hollow (walls/floor/ceiling + air inside), \
or outline (walls/floor/ceiling only, inside untouched). Corners are inclusive, so min [0,64,0] max [6,68,6] is 7 x 5 x 7. \
Example: {\"region\":{\"min\":[100,64,200],\"max\":[106,68,206]},\"block\":\"minecraft:stone_bricks\",\"mode\":\"hollow\"}", write_notes!()))]
    async fn fill_region(&self, Parameters(args): Parameters<FillRegionArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("world.fillRegion", params(&args)).await)
    }

    #[tool(description = concat!("Set every block of a box to air (corners inclusive). Undoable. \
Example: {\"region\":{\"min\":[100,64,200],\"max\":[106,68,206]}}", write_notes!()))]
    async fn clear_region(&self, Parameters(args): Parameters<ClearRegionArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("world.clearRegion", params(&args)).await)
    }
}
