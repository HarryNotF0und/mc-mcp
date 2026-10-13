use rmcp::model::CallToolResult;
use rmcp::{ErrorData, tool, tool_router};
use serde_json::json;

use super::McServer;

#[tool_router(router = world_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Get an overview of the world: Minecraft and mod versions, dimension ids \
(e.g. \"minecraft:overworld\"), online players with their dimension and position [x, y, z], \
game time and day time in ticks (24000 per day), and difficulty.")]
    async fn get_world_info(&self) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("world.getInfo", json!({})).await)
    }
}
