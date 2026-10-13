//! MCP resources and prompts (PLAN.md §7).

use rmcp::ErrorData;
use rmcp::model::{
    GetPromptResult, Prompt, PromptMessage, ReadResourceResult, Resource, ResourceContents,
    ResourceTemplate, Role,
};
use serde_json::{Value, json};

use crate::mc_client::McClient;

pub const SCRIPT_API: &str = include_str!("../../docs/script-api.md");

pub const BUILD_ASSISTANT: &str = "build_assistant";

pub fn list() -> Vec<Resource> {
    vec![
        Resource::new("mc://world/info", "world-info")
            .with_description("Versions, dimensions, online players, time, difficulty, server tick time (mspt).")
            .with_mime_type("application/json"),
        Resource::new("mc://history", "history")
            .with_description("Undoable and redoable changes, most recent first.")
            .with_mime_type("application/json"),
        Resource::new("mc://jobs", "jobs")
            .with_description("Recent build jobs with their state and progress.")
            .with_mime_type("application/json"),
        Resource::new("mc://docs/script-api", "script-api")
            .with_description("Reference for build scripts (run_script): the b object, coordinates, limits, examples.")
            .with_mime_type("text/markdown"),
    ]
}

pub fn templates() -> Vec<ResourceTemplate> {
    vec![
        ResourceTemplate::new("mc://player/{name}", "player")
            .with_description("A player's position, facing, game mode, health, items in hand and the block they look at.")
            .with_mime_type("application/json"),
    ]
}

pub async fn read(mc: &McClient, uri: &str) -> Result<ReadResourceResult, ErrorData> {
    if uri == "mc://docs/script-api" {
        return Ok(ReadResourceResult::new(vec![ResourceContents::text(SCRIPT_API, uri)]));
    }
    let (method, params): (&str, Value) = match uri {
        "mc://world/info" => ("world.getInfo", json!({})),
        "mc://history" => ("history.list", json!({})),
        "mc://jobs" => ("job.status", json!({})),
        _ => match uri.strip_prefix("mc://player/") {
            Some(name) if !name.is_empty() => ("world.getPlayerState", json!({ "name": name })),
            _ => return Err(ErrorData::resource_not_found(format!("unknown resource {uri}"), None)),
        },
    };
    let value = mc
        .call(method, params)
        .await
        .map_err(|e| ErrorData::internal_error(e.to_string(), None))?;
    Ok(ReadResourceResult::new(vec![ResourceContents::text(value.to_string(), uri)]))
}

pub fn prompts() -> Vec<Prompt> {
    vec![Prompt::new(
        BUILD_ASSISTANT,
        Some("Recommended workflow for building in Minecraft with these tools."),
        None,
    )]
}

pub fn get_prompt(name: &str) -> Result<GetPromptResult, ErrorData> {
    if name != BUILD_ASSISTANT {
        return Err(ErrorData::invalid_params(format!("unknown prompt {name}"), None));
    }
    let text = "You are building in a live Minecraft world through the mc tools. Work like this:\n\
\n\
1. Perceive first. Use get_player_state to find where the player is and what they look at, \
get_area_summary to see the terrain (surface heightmap, water, entities) around the site, and \
get_layer_slices for exact blocks of small areas. Coordinates are [x, y, z], y up, north = -z, east = +x; \
region corners are inclusive.\n\
2. Plan the structure and its exact coordinates. Pick the ground level from the heightmap.\n\
3. Prefer one run_script call for anything with repetition or curves (read mc://docs/script-api first), \
fill_region for boxes, place_blocks for details. Run scripts with dryRun: true first and check the block \
count and bounding box, or show it with preview, look at it with orbit_shots, then confirm_preview.\n\
4. Build. Each call is one undoable change. For big builds the tool may return state \"running\"; poll job_status. \
A \"warning\" that the game is paused means jobs wait until the player closes the pause menu.\n\
5. Verify with orbit_shots / screenshot (single-player) and get_layer_slices. If something is wrong, undo and rebuild \
rather than patching blindly.\n\
\n\
Errors: 1001 outside allowed region, 1002 too large, 1003 unknown/blacklisted block, 1004 chunk not loaded \
(retry with loadChunks: true), 1006 script error (fix the reported line).";
    Ok(GetPromptResult::new(vec![PromptMessage::new_text(Role::User, text)])
        .with_description("Recommended workflow for building in Minecraft"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mc_client::McClientConfig;
    use std::time::Duration;

    fn offline_client() -> McClient {
        McClient::new(McClientConfig {
            url: "ws://127.0.0.1:1".into(),
            token: String::new(),
            request_timeout: Duration::from_millis(200),
            connect_timeout: Duration::from_millis(200),
        })
    }

    #[tokio::test]
    async fn script_api_doc_is_served_without_the_game() {
        let r = read(&offline_client(), "mc://docs/script-api").await.unwrap();
        let text = serde_json::to_value(&r.contents[0]).unwrap()["text"].as_str().unwrap().to_string();
        assert!(text.contains("b.cylinder"));
    }

    #[tokio::test]
    async fn unknown_resource_is_not_found() {
        assert!(read(&offline_client(), "mc://nope").await.is_err());
        assert!(read(&offline_client(), "mc://player/").await.is_err());
    }

    #[test]
    fn build_assistant_prompt_exists() {
        assert_eq!(prompts()[0].name, BUILD_ASSISTANT);
        assert!(get_prompt(BUILD_ASSISTANT).unwrap().messages.len() == 1);
        assert!(get_prompt("other").is_err());
    }
}
