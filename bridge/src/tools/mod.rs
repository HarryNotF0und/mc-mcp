//! MCP tools. Each submodule contributes one `#[tool_router]` block to [`McServer`];
//! [`McServer::new`] merges them.

mod agent;
mod arch;
mod build;
mod jobs;
mod scripts;
mod system;
mod view;
mod world;

use std::sync::Arc;
use std::time::Duration;

use rmcp::handler::server::router::tool::ToolRouter;
use rmcp::model::{
    CallToolResult, ContentBlock, GetPromptRequestParams, GetPromptResponse, Implementation,
    ListPromptsResult, ListResourceTemplatesResult, ListResourcesResult, PaginatedRequestParams,
    ReadResourceRequestParams, ReadResourceResponse, ServerCapabilities, ServerConfig,
};
use rmcp::service::RequestContext;
use rmcp::{ErrorData, RoleServer, ServerHandler, schemars, tool_handler};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::broadcast::error::RecvError;

use crate::mc_client::{McClient, McError};
use crate::resources;

const INSTRUCTIONS: &str = "Controls a running Minecraft world through the mc-mcp mod. \
Coordinates are integer world coordinates [x, y, z]; y is up; north is -z, east is +x. \
Regions are {\"min\":[x,y,z],\"max\":[x,y,z]} with both corners inclusive. \
Recommended workflow (also available as the build_assistant prompt): get_player_state / get_area_summary \
to find the site, get_layer_slices to inspect it, then build with run_script (read mc://docs/script-api first; \
use dryRun or preview), fill_region or place_blocks, check the result with orbit_shots / screenshot \
(single-player), and undo if it is wrong. For survival play without god-mode tools, spawn a fake player with agent_spawn and use the agent_* tools.";

/// An axis-aligned box of blocks; both corners are inclusive.
#[derive(Debug, Clone, Deserialize, Serialize, schemars::JsonSchema)]
pub struct Region {
    /// Corner [x, y, z].
    pub min: [i32; 3],
    /// Opposite corner [x, y, z] (inclusive).
    pub max: [i32; 3],
}

#[derive(Clone)]
pub struct McServer {
    mc: Arc<McClient>,
    job_wait: Duration,
    tool_router: ToolRouter<Self>,
}

impl McServer {
    /// `job_wait`: how long build tools wait for their job to finish before returning its id.
    pub fn new(mc: Arc<McClient>, job_wait: Duration) -> Self {
        Self {
            mc,
            job_wait,
            tool_router: Self::system_router()
                + Self::world_router()
                + Self::build_router()
                + Self::jobs_router()
                + Self::scripts_router()
                + Self::view_router()
                + Self::agent_router()
                + Self::arch_router(),
        }
    }

    /// Calls a mod method and turns the outcome into a compact tool result.
    /// Mod-side errors become `isError` results so the model can read and react to them.
    async fn forward(&self, method: &str, params: Value) -> CallToolResult {
        to_tool_result(self.mc.call(method, params).await)
    }

    /// Calls a method that queues a job, then waits (up to `job_wait`) for its `job.finished` push.
    async fn forward_job(&self, method: &str, params: Value) -> CallToolResult {
        to_tool_result(self.call_job(method, params).await)
    }

    /// Like [`Self::forward_job`] but waits at least `wait` (agent actions take longer than builds).
    async fn forward_job_waiting(&self, method: &str, params: Value, wait: Duration) -> CallToolResult {
        to_tool_result(self.call_job_waiting(method, params, wait.max(self.job_wait)).await)
    }

    async fn call_job(&self, method: &str, params: Value) -> Result<Value, McError> {
        self.call_job_waiting(method, params, self.job_wait).await
    }

    async fn call_job_waiting(&self, method: &str, params: Value, job_wait: Duration) -> Result<Value, McError> {
        // Subscribe before submitting so the finish notification cannot be missed.
        let mut notes = self.mc.subscribe();
        let mut submitted = self.mc.call(method, params).await?;
        let nested = submitted.get("jobId").is_none();
        let job = if nested { &submitted["job"] } else { &submitted };
        let Some(job_id) = job.get("jobId").and_then(Value::as_u64) else {
            return Ok(submitted);
        };

        let wait = async {
            loop {
                match notes.recv().await {
                    Ok(n) if n["method"] == "job.finished" && n["params"]["jobId"] == job_id => {
                        return Some(n["params"].clone());
                    }
                    Ok(_) => {}
                    // Missed some pushes: fall back to asking.
                    Err(RecvError::Lagged(_)) => return None,
                    Err(RecvError::Closed) => return None,
                }
            }
        };
        let finished = match tokio::time::timeout(job_wait, wait).await {
            Ok(Some(done)) => done,
            Ok(None) | Err(_) => {
                let mut status = self.mc.call("job.status", json!({ "jobId": job_id })).await?;
                if status["state"] == "queued" || status["state"] == "running" {
                    status["note"] = json!("still running; call job_status with this jobId to check progress");
                }
                status
            }
        };
        if finished["state"] == "failed" {
            let message = finished["error"].as_str().unwrap_or("job failed").to_string();
            return Err(McError::Rpc(crate::mc_client::RpcError {
                code: -32603,
                message: format!("job {job_id} failed: {message}"),
                data: None,
            }));
        }
        if nested {
            submitted["job"] = finished;
            Ok(submitted)
        } else {
            Ok(finished)
        }
    }
}

/// A -32602 tool error, like the mod's for bad parameters.
fn invalid_params(message: String) -> CallToolResult {
    to_tool_result(Err(McError::Rpc(crate::mc_client::RpcError { code: -32602, message, data: None })))
}

/// Reads a text file named by a tool argument (a path on the machine running the bridge).
async fn read_file(path: &str, arg: &str) -> Result<String, CallToolResult> {
    tokio::fs::read_to_string(path)
        .await
        .map_err(|e| invalid_params(format!("cannot read {arg} {path}: {e}")))
}

/// Fills `building` from `buildingFile` (a JSON file with the plan) when given.
async fn load_building(building: &mut Option<Value>, file: Option<String>) -> Result<(), CallToolResult> {
    if let Some(path) = file {
        let text = read_file(&path, "buildingFile").await?;
        let plan: Value = serde_json::from_str(&text)
            .map_err(|e| invalid_params(format!("buildingFile {path} is not valid JSON: {e}")))?;
        *building = Some(plan);
    }
    Ok(())
}

/// Script libraries from `includeFiles`: [{name, source}] evaluated before the script.
async fn load_includes(files: Option<Vec<String>>) -> Result<Option<Value>, CallToolResult> {
    let Some(files) = files else { return Ok(None) };
    let mut libs = Vec::new();
    for path in files {
        let source = read_file(&path, "includeFiles entry").await?;
        libs.push(json!({"name": path, "source": source}));
    }
    Ok(Some(Value::Array(libs)))
}

/// Drops the per-component list (long for big plans) unless `details` was asked for.
fn brief(mut value: Value, details: bool) -> Value {
    if let (false, Some(o)) = (details, value.as_object_mut())
        && let Some(Value::Array(c)) = o.remove("components")
    {
        o.insert("componentCount".into(), json!(c.len()));
    }
    value
}

/// Serialises tool arguments into mod params, dropping absent optional fields.
fn params<T: Serialize>(args: &T) -> Value {
    serde_json::to_value(args).unwrap_or_else(|_| json!({}))
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
        ServerConfig::new(
            ServerCapabilities::builder()
                .enable_tools()
                .enable_resources()
                .enable_prompts()
                .build(),
        )
            .with_server_info(Implementation::new("mc-mcp-bridge", env!("CARGO_PKG_VERSION")))
            .with_instructions(INSTRUCTIONS)
    }

    async fn list_resources(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> Result<ListResourcesResult, ErrorData> {
        Ok(ListResourcesResult::with_all_items(resources::list()))
    }

    async fn list_resource_templates(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> Result<ListResourceTemplatesResult, ErrorData> {
        Ok(ListResourceTemplatesResult::with_all_items(resources::templates()))
    }

    async fn read_resource(
        &self,
        request: ReadResourceRequestParams,
        _context: RequestContext<RoleServer>,
    ) -> Result<ReadResourceResponse, ErrorData> {
        Ok(resources::read(&self.mc, &request.uri).await?.into())
    }

    async fn list_prompts(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> Result<ListPromptsResult, ErrorData> {
        Ok(ListPromptsResult::with_all_items(resources::prompts()))
    }

    async fn get_prompt(
        &self,
        request: GetPromptRequestParams,
        _context: RequestContext<RoleServer>,
    ) -> Result<GetPromptResponse, ErrorData> {
        Ok(resources::get_prompt(&request.name)?.into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mc_client::McClientConfig;
    use futures_util::{SinkExt, StreamExt};
    use tokio::net::TcpListener;
    use tokio_tungstenite::tungstenite::Message;

    /// Fake mod: jobs 1 (finishes), 2 (fails), 3 (never finishes).
    async fn fake_mod() -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let mut ws = tokio_tungstenite::accept_async(tcp).await.unwrap();
            while let Some(Ok(Message::Text(text))) = ws.next().await {
                let req: Value = serde_json::from_str(&text).unwrap();
                let id = req["id"].clone();
                let (result, push) = match req["method"].as_str().unwrap() {
                    "auth" => (json!({"ok": true}), None),
                    "world.fillRegion" => (
                        json!({"jobId": 1, "state": "queued"}),
                        Some(json!({"jobId": 1, "state": "done", "changed": 3})),
                    ),
                    "history.undo" => (
                        json!({"jobId": 2, "state": "queued"}),
                        Some(json!({"jobId": 2, "state": "failed", "error": "nothing to undo"})),
                    ),
                    "world.clearRegion" => (json!({"jobId": 3, "state": "queued"}), None),
                    "arch.revise" if req["params"]["preview"] == true => (json!({"applied": "preview", "diff": {"changed": 2}}), None),
                    "arch.revise" if req["params"]["slow"] == true => (json!({"applied": "world", "diff": {"changed": 2}, "job": {"jobId": 3, "state": "queued"}}), None),
                    "arch.revise" if req["params"]["fail"] == true => (
                        json!({"applied": "world", "job": {"jobId": 2, "state": "queued"}}),
                        Some(json!({"jobId": 2, "state": "failed", "error": "building failed"})),
                    ),
                    "arch.revise" => (
                        json!({"applied": "world", "diff": {"changed": 2}, "job": {"jobId": 4, "state": "queued"}}),
                        Some(json!({"jobId": 4, "state": "done", "changed": 2})),
                    ),
                    "job.status" => (json!({"jobId": 3, "state": "running", "done": 10, "total": 99}), None),
                    m => panic!("unexpected {m}"),
                };
                let reply = json!({"jsonrpc": "2.0", "id": id, "result": result});
                ws.send(Message::text(reply.to_string())).await.unwrap();
                if let Some(p) = push {
                    // An unrelated push first, to check filtering by job id.
                    let other = json!({"jsonrpc": "2.0", "method": "job.finished", "params": {"jobId": 99, "state": "done"}});
                    ws.send(Message::text(other.to_string())).await.unwrap();
                    let n = json!({"jsonrpc": "2.0", "method": "job.finished", "params": p});
                    ws.send(Message::text(n.to_string())).await.unwrap();
                }
            }
        });
        format!("ws://{addr}")
    }

    async fn server() -> McServer {
        let mc = McClient::new(McClientConfig {
            url: fake_mod().await,
            token: "t".into(),
            request_timeout: Duration::from_millis(500),
            connect_timeout: Duration::from_millis(500),
        });
        McServer::new(Arc::new(mc), Duration::from_millis(300))
    }

    #[tokio::test]
    async fn job_tools_wait_for_their_own_finish_push() {
        let s = server().await;
        let r = s.call_job("world.fillRegion", json!({})).await.unwrap();
        assert_eq!(r, json!({"jobId": 1, "state": "done", "changed": 3}));
    }

    #[tokio::test]
    async fn failed_job_is_an_error() {
        let s = server().await;
        let err = s.call_job("history.undo", json!({})).await.unwrap_err();
        assert!(err.to_string().contains("nothing to undo"), "{err}");
    }

    #[tokio::test]
    async fn slow_job_returns_status_after_wait() {
        let s = server().await;
        let r = s.call_job("world.clearRegion", json!({})).await.unwrap();
        assert_eq!(r["state"], "running");
        assert_eq!(r["jobId"], 3);
        assert!(r["note"].as_str().unwrap().contains("job_status"));
    }

    #[tokio::test]
    async fn revision_waits_for_nested_job_and_keeps_the_report() {
        let r = server().await.call_job("arch.revise", json!({})).await.unwrap();
        assert_eq!(r, json!({"applied": "world", "diff": {"changed": 2}, "job": {"jobId": 4, "state": "done", "changed": 2}}));
    }

    #[tokio::test]
    async fn preview_revision_returns_without_waiting_for_a_job() {
        let r = server().await.call_job("arch.revise", json!({"preview": true})).await.unwrap();
        assert_eq!(r, json!({"applied": "preview", "diff": {"changed": 2}}));
    }

    #[tokio::test]
    async fn failed_revision_job_is_an_error() {
        let e = server().await.call_job("arch.revise", json!({"fail": true})).await.unwrap_err();
        assert!(e.to_string().contains("building failed"), "{e}");
    }

    #[tokio::test]
    async fn slow_revision_keeps_the_report_and_returns_polling_instructions() {
        let r = server().await.call_job("arch.revise", json!({"slow": true})).await.unwrap();
        assert_eq!(r["diff"]["changed"], 2);
        assert_eq!(r["job"]["state"], "running");
        assert!(r["job"]["note"].as_str().unwrap().contains("job_status"));
    }

    #[test]
    fn validation_is_brief_unless_details_are_asked_for() {
        let v = json!({"blocks": 10, "components": [{"id": "roof"}, {"id": "walls"}], "validation": {"ok": true}});
        assert_eq!(brief(v.clone(), false), json!({"blocks": 10, "componentCount": 2, "validation": {"ok": true}}));
        assert_eq!(brief(v.clone(), true), v);
    }

    #[tokio::test]
    async fn building_and_include_files_are_read() {
        let dir = std::env::temp_dir().join(format!("mcmcp-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let plan = dir.join("plan.json");
        std::fs::write(&plan, r#"{"name": "keep"}"#).unwrap();
        let lib = dir.join("lib.js");
        std::fs::write(&lib, "function f() {}").unwrap();

        let mut building = None;
        load_building(&mut building, Some(plan.to_string_lossy().into())).await.unwrap();
        assert_eq!(building, Some(json!({"name": "keep"})));
        let libs = load_includes(Some(vec![lib.to_string_lossy().into()])).await.unwrap().unwrap();
        assert_eq!(libs[0]["source"], "function f() {}");

        let missing = load_building(&mut building, Some("no/such/file.json".into())).await.unwrap_err();
        let text = serde_json::to_value(&missing).unwrap()["content"][0]["text"].as_str().unwrap().to_string();
        assert!(text.contains("cannot read buildingFile no/such/file.json"), "{text}");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn script_file_accepts_the_file_alias() {
        let a: scripts::RunScriptArgs = serde_json::from_value(json!({"file": "x.js", "origin": [0, 0, 0]})).unwrap();
        assert_eq!(a.source_file.as_deref(), Some("x.js"));
    }

    #[test]
    fn absent_optional_args_are_not_sent() {
        let v = params(&build::FillRegionArgs {
            region: Region { min: [0, 1, 2], max: [3, 4, 5] },
            block: "minecraft:stone".into(),
            mode: None,
            split: None,
            dimension: None,
            load_chunks: Some(true),
        });
        assert_eq!(
            v,
            json!({"region": {"min": [0, 1, 2], "max": [3, 4, 5]}, "block": "minecraft:stone", "loadChunks": true})
        );
    }
}
