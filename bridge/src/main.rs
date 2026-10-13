use std::sync::Arc;
use std::time::Duration;

use anyhow::Context;
use mc_mcp_bridge::mc_client::{McClient, McClientConfig};
use mc_mcp_bridge::tools::McServer;
use rmcp::ServiceExt;
use tracing_subscriber::EnvFilter;

const DEFAULT_URL: &str = "ws://127.0.0.1:25580";

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // stdout carries MCP messages, so logs must go to stderr.
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_env("MCMCP_LOG").unwrap_or_else(|_| EnvFilter::new("info")))
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    let config = McClientConfig {
        url: std::env::var("MCMCP_URL").unwrap_or_else(|_| DEFAULT_URL.to_string()),
        token: std::env::var("MCMCP_TOKEN").unwrap_or_default(),
        request_timeout: Duration::from_millis(env_u64("MCMCP_TIMEOUT_MS", 30_000)),
        connect_timeout: Duration::from_millis(env_u64("MCMCP_CONNECT_TIMEOUT_MS", 3_000)),
    };
    if config.token.is_empty() {
        tracing::warn!("MCMCP_TOKEN is not set; the mod will reject the connection");
    }

    let mc = Arc::new(McClient::new(config));
    let job_wait = Duration::from_millis(env_u64("MCMCP_JOB_WAIT_MS", 20_000));
    let service = McServer::new(mc, job_wait)
        .serve(rmcp::transport::stdio())
        .await
        .context("failed to start MCP server on stdio")?;
    service.waiting().await?;
    Ok(())
}

fn env_u64(name: &str, default: u64) -> u64 {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}
