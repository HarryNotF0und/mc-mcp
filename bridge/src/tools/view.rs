use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::{CallToolResult, ContentBlock};
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::{McServer, Region, load_building, load_includes, params, read_file, to_tool_result};
use crate::mc_client::McError;

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PreviewArgs {
    /// Building plan ("format": "mcmcp.building/1", see mc://docs/building-plan). Give this instead of a
    /// blueprint/name/schematic (or script source, for preview).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub building: Option<Value>,
    /// Instead of `building`: a .json file with the plan on the bridge's machine.
    #[serde(skip_serializing)]
    pub building_file: Option<String>,
    /// Script helper files evaluated before the script (as in run_script).
    #[serde(skip_serializing)]
    pub include_files: Option<Vec<String>>,
    /// Filled from includeFiles.
    #[serde(skip_deserializing, skip_serializing_if = "Option::is_none")]
    #[schemars(skip)]
    pub libraries: Option<Value>,
    /// Script: never replace existing blocks (as in run_script).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub keep_existing: Option<bool>,
    /// Place a building plan even if validation reports errors.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ignore_validation: Option<bool>,
    /// Script source (as in run_script). Give a script (source/sourceFile) or a blueprint (blueprint/name).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    /// Script file on the bridge's machine, e.g. "examples/scripts/tower.js". Alias: `file`.
    #[serde(skip_serializing, alias = "file")]
    pub source_file: Option<String>,
    /// Blueprint JSON (as in place_blueprint).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blueprint: Option<Value>,
    /// Name of a saved blueprint.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Script origin, or where the blueprint's minimum corner goes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin: Option<[i32; 3]>,
    /// Blueprint rotation 0/90/180/270 (clockwise from above).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotation: Option<i32>,
    /// Blueprint mirror "none", "x" or "z".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mirror: Option<String>,
    /// Blueprint: don't place its air.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub skip_air: Option<bool>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PreviewIdArgs {
    /// Id returned by preview.
    pub preview_id: u32,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ConfirmPreviewArgs {
    /// Id returned by preview.
    pub preview_id: u32,
    /// Explicitly build a building preview even when its architectural validation has errors.
    /// Safety limits still apply. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ignore_validation: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotArgs {
    /// Camera (eye) position [x, y, z]; fractions allowed. Default: the player's eyes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pos: Option<[f64; 3]>,
    /// Point the camera at this position [x, y, z] (overrides yaw/pitch).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub look_at: Option<[f64; 3]>,
    /// Degrees: 0 looks south (+z), 90 west, 180 north, 270 east. Default: the player's (without pos) or 0.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub yaw: Option<f64>,
    /// Degrees: positive looks down, -90 straight up, 90 straight down.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pitch: Option<f64>,
    /// Image width, default 854 (max 1920).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    /// Image height, default 480 (max 1080).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    /// Vertical field of view in degrees, 30-110, default 70.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fov: Option<u32>,
    /// Render as if it were noon (default true; client-side only, the world's time is not changed).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub daylight: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct OrbitShotsArgs {
    /// Bounding box of the build to photograph; both corners inclusive. Give this or `building`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub region: Option<Region>,
    /// Instead of `region`: name of a registered building (previewed or placed from a plan); its bounds are used.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub building: Option<String>,
    /// Number of views evenly spaced around it, default 4 (from north, east, south, west).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub count: Option<u32>,
    /// Downward camera angle in degrees, default 30.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pitch: Option<f64>,
    /// Camera distance from the centre; default fits the whole box in view.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub distance: Option<f64>,
    /// Image width, default 854 (max 1920).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    /// Image height, default 480 (max 1080).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    /// Vertical field of view, default 70.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fov: Option<u32>,
    /// Render as if it were noon (default true; client-side only).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub daylight: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct RefreshViewArgs {
    /// Box to re-send to the players (at most 262144 blocks).
    pub region: Region,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
}

#[tool_router(router = view_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Show a script or blueprint as fake blocks to the players without changing the world \
(nothing is placed, nothing to undo). Takes the same arguments as run_script (source/sourceFile + origin) or \
place_blueprint (blueprint/name + origin, rotation, mirror), or a building plan (building: {...}; the result \
includes its validation, and invalid plans can still be previewed). Returns a previewId; look at it (e.g. with orbit_shots), \
then confirm_preview to build it for real or discard_preview to remove it. Players who reload the chunks see the \
real world again.")]
    async fn preview(&self, Parameters(mut args): Parameters<PreviewArgs>) -> Result<CallToolResult, ErrorData> {
        if let Some(path) = args.source_file.take() {
            match read_file(&path, "sourceFile").await {
                Ok(text) => args.source = Some(text),
                Err(e) => return Ok(e),
            }
        }
        if let Err(e) = load_building(&mut args.building, args.building_file.take()).await {
            return Ok(e);
        }
        match load_includes(args.include_files.take()).await {
            Ok(libs) => args.libraries = libs,
            Err(e) => return Ok(e),
        }
        Ok(self.forward("build.preview", params(&args)).await)
    }

    #[tool(description = "Build a preview for real: places exactly the previewed blocks as one undoable change. \
Building previews with validation errors are rejected (1007); repair them with revise_building or explicitly \
pass ignoreValidation: true. Failed validation keeps the preview active. Safety limits always apply.")]
    async fn confirm_preview(&self, Parameters(args): Parameters<ConfirmPreviewArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("build.confirmPreview", params(&args)).await)
    }

    #[tool(description = "Re-send the blocks of a box to the players' game clients (real blocks, or an active \
preview's): clears leftovers that only exist on the client, such as a stale preview after confirm/discard. \
Changes nothing in the world. Check with get_block / get_layer_slices first that the world itself is right. \
Example: {\"region\":{\"min\":[100,60,200],\"max\":[130,90,230]}}")]
    async fn refresh_view(&self, Parameters(args): Parameters<RefreshViewArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("view.refresh", params(&args)).await)
    }

    #[tool(description = "Remove a preview: players see the real blocks again.")]
    async fn discard_preview(&self, Parameters(args): Parameters<PreviewIdArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("build.discardPreview", params(&args)).await)
    }

    #[tool(description = "Take a screenshot in the game client (single-player only) from any camera position, \
without the HUD. Default: what the player sees. Use lookAt to aim at a point. Previews (fake blocks) are visible. \
Only chunks the client has loaded (within render distance of the player) are drawn; a far target returns a \
warning to move the player closer. Terrain fog is switched off for the picture. \
Example: {\"pos\":[90,80,180],\"lookAt\":[100,66,200]}")]
    async fn screenshot(&self, Parameters(args): Parameters<ScreenshotArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(images(self.mc.call("view.screenshot", params(&args)).await))
    }

    #[tool(description = "Photograph a build from several sides (default 4: from north, east, south, west, looking \
down 30 degrees), framed to fit the region, or a registered building's bounds ({\"building\":\"castle\"}). \
Use it to check a build and spot mistakes. Single-player only; terrain fog is off, but only chunks near the player \
exist on the client, so a far target returns a warning to move the player closer. \
Example: {\"region\":{\"min\":[100,64,200],\"max\":[114,94,214]}}")]
    async fn orbit_shots(&self, Parameters(args): Parameters<OrbitShotsArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(images(self.mc.call("view.orbitShots", params(&args)).await))
    }
}

/// `{"width", "height", "shots": [{"label", "pos", "yaw", "pitch", "png"}]}` -> one caption text and one PNG image per shot.
fn images(result: Result<Value, McError>) -> CallToolResult {
    let value = match result {
        Ok(v) => v,
        Err(e) => return to_tool_result(Err(e)),
    };
    let mut content = Vec::new();
    if let Some(warning) = value["warning"].as_str() {
        content.push(ContentBlock::text(json!({"warning": warning}).to_string()));
    }
    for shot in value["shots"].as_array().into_iter().flatten() {
        let caption = json!({"label": shot["label"], "pos": shot["pos"], "yaw": shot["yaw"], "pitch": shot["pitch"]});
        content.push(ContentBlock::text(caption.to_string()));
        if let Some(png) = shot["png"].as_str() {
            content.push(ContentBlock::image(png, "image/png"));
        }
    }
    CallToolResult::success(content)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shots_become_caption_and_image_blocks() {
        let r = images(Ok(json!({"width": 2, "height": 2, "shots": [
            {"label": "from north", "pos": [1, 2, 3], "yaw": 0, "pitch": 30, "png": "iVBORw=="},
            {"label": "from east", "pos": [1, 2, 3], "yaw": 90, "pitch": 30, "png": "iVBORw=="}
        ]})));
        let v = serde_json::to_value(&r).unwrap();
        let content = v["content"].as_array().unwrap();
        assert_eq!(content.len(), 4);
        assert_eq!(content[0]["type"], "text");
        assert_eq!(content[1]["type"], "image");
        assert_eq!(content[1]["mimeType"], "image/png");
        assert_eq!(content[1]["data"], "iVBORw==");
    }
}
