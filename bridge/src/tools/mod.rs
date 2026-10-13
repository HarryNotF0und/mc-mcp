//! MCP tools. Each submodule contributes one `#[tool_router]` block to [`McServer`];
//! [`McServer::new`] merges them.

mod system;
mod world;

use std::sync::Arc;

use rmcp::handler::server::router::tool::ToolRouter;
use rmcp::model::{CallToolResult, ContentBlock, Implementation, ServerCapabilities, ServerConfig};
use rmcp::{ServerHandler, tool_handler};
use serde_json::Value;

use crate::mc_client::{McClient, McError};

const INSTRUCTIONS: &str = "Controls a running Minecraft world through the mc-mcp mod. \
Coordinates are integer world coordinates [x, y, z]; y is up. \
Call `ping` first if unsure whether the game is reachable.";

#[derive(Clone)]
pub struct McServer {
    mc: Arc<McClient>,
    tool_router: ToolRouter<Self>,
}

impl McServer {
    pub fn new(mc: Arc<McClient>) -> Self {
        Self {
            mc,
            tool_router: Self::system_router() + Self::world_router(),
        }
    }

    /// Calls a mod method and turns the outcome into a compact tool result.
    /// Mod-side errors become `isError` results so the model can read and react to them.
    async fn forward(&self, method: &str, params: Value) -> CallToolResult {
        to_tool_result(self.mc.call(method, params).await)
    }
}

pub fn to_tool_result(result: Result<Value, McError>) -> CallToolResult {
    match result {
        Ok(value) => CallToolResult::success(vec![ContentBlock::text(value.to_string())]),
        Err(e) => CallToolResult::error(vec![ContentBlock::text(e.to_string())]),
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for McServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
            .with_server_info(Implementation::new("mc-mcp-bridge", env!("CARGO_PKG_VERSION")))
            .with_instructions(INSTRUCTIONS)
    }
}
