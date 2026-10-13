use rmcp::model::CallToolResult;
use rmcp::{ErrorData, tool, tool_router};
use serde_json::json;

use super::McServer;

#[tool_router(router = system_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Check that Minecraft is reachable. Returns {\"pong\":true,\"modVersion\":...,\"serverTimeMs\":...}. \
If the game is not running or no world is loaded, returns an error explaining that instead of hanging.")]
    async fn ping(&self) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("system.ping", json!({})).await)
    }
}
