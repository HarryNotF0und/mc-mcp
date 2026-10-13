use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::CallToolResult;
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::json;

use super::{McServer, Region, params};

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PlayerStateArgs {
    /// Player name. Omit to use the first online player (the only one in single-player).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct GetBlockArgs {
    /// Block position [x, y, z].
    pub pos: [i32; 3],
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load (and generate if needed) chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AreaSummaryArgs {
    /// Region to summarise; both corners inclusive. At most 262144 blocks (e.g. 64 x 64 x 64).
    pub region: Region,
    /// How many of the most common block types to list (default 10).
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
pub struct LayerSlicesArgs {
    /// Region to read; both corners inclusive; at most 32 blocks along each axis.
    pub region: Region,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[tool_router(router = world_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Get an overview of the world: Minecraft and mod versions, dimension ids \
(e.g. \"minecraft:overworld\"), online players with their dimension and block position [x, y, z], \
game time and day time in ticks (24000 per day), difficulty, and mspt (average server tick time in ms; \
50 or more means the server is lagging).")]
    async fn get_world_info(&self) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("world.getInfo", json!({})).await)
    }

    #[tool(description = "Get a player's state: exact position, blockPos [x, y, z] (y is the block the feet are in), \
yaw/pitch, facing (north = -z, south = +z, west = -x, east = +x), dimension, game mode, health, food, items in hand, \
and lookingAt (the block the crosshair points at, up to 64 blocks away). Use it to find where to build.")]
    async fn get_player_state(
        &self,
        Parameters(args): Parameters<PlayerStateArgs>,
    ) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("world.getPlayerState", params(&args)).await)
    }

    #[tool(description = "Read one block. Returns its state in game syntax, e.g. \
\"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]\", plus block entity NBT if any. \
Example: {\"pos\":[100,64,200]}. For more than a few blocks use get_layer_slices.")]
    async fn get_block(&self, Parameters(args): Parameters<GetBlockArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("world.getBlock", params(&args)).await)
    }

    #[tool(description = "Summarise a region before building: block counts (most common first, air separately), \
a surface heightmap downsampled to at most 32 x 32 (rows[j][k] = y of the highest non-air block at \
x = originXZ[0] + k*step, z = originXZ[1] + j*step; this ignores the region's y range), water/lava counts with sample \
positions, and entities. Region corners are inclusive; at most 262144 blocks. \
Example: {\"region\":{\"min\":[90,60,190],\"max\":[130,80,230]}}")]
    async fn get_area_summary(
        &self,
        Parameters(args): Parameters<AreaSummaryArgs>,
    ) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("world.getAreaSummary", params(&args)).await)
    }

    #[tool(description = "Read every block of a region (max 32 x 32 x 32) as palette + layers: \
{\"origin\":[x,y,z],\"size\":[sx,sy,sz],\"palette\":{\".\":\"minecraft:air\",\"S\":\"minecraft:stone\"},\"layers\":[[\"SS.\",\".S.\"]]}. \
layers[i] is y = origin.y + i (bottom first); within a layer, string j is z = origin.z + j; character k is x = origin.x + k. \
Example: {\"region\":{\"min\":[100,64,200],\"max\":[104,66,204]}}")]
    async fn get_layer_slices(
        &self,
        Parameters(args): Parameters<LayerSlicesArgs>,
    ) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("world.getLayerSlices", params(&args)).await)
    }
}
