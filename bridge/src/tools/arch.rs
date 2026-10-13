//! Architectural planning (v2 phase 1) and the inspect/revise loop over registered buildings (phase 2).

use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::{CallToolResult, ContentBlock};
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{McServer, params, to_tool_result};
use crate::mc_client::McError;

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

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct InspectBuildingArgs {
    /// Building name (the plan's "name").
    pub name: String,
    /// Views to take: front, back, left, right, elevated, interior (one per floor). Default: all.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub views: Option<Vec<String>>,
    /// Return pictures (needs the game client); false = numbers only. Default true.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub images: Option<bool>,
    /// Image width, default 640.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    /// Image height, default 400.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    /// Field of view 30-110, default 70 (interiors at least 85).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fov: Option<u32>,
    /// Render at noon (default true).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub daylight: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ReviseBuildingArgs {
    /// Building name.
    pub name: String,
    /// Component edits by id, applied in order: {"op":"update","id":"upper-front-left","set":{"width":3}} merges
    /// fields (null removes one; the roof is addressed by its id), {"op":"remove","id":"yard-fence"},
    /// {"op":"add","to":"openings","value":{..}} appends a component, {"op":"set","field":"footprint","value":[13,9]}
    /// replaces a top-level field ("merge": true merges an object, e.g. into style).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ops: Option<Value>,
    /// Alternatively a whole replacement plan (same name).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub building: Option<Value>,
    /// Required to change a building that is already built (after asking the user).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub confirm: Option<bool>,
    /// The user approved more revisions; resets the round counter.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_approved: Option<bool>,
    /// Apply even with validation errors.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ignore_validation: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
pub struct GetBuildingArgs {
    /// Building name; omit to list all registered buildings.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
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

    #[tool(description = "Look at a registered building (one previewed or placed with {\"building\": ...}). \
Returns validation, design metrics (facade symmetry, window ratio and alignment, roof overhang and symmetry, \
proportions), the plan's requirements with pass/fail, for built buildings a block-by-block verification against \
the world per component, and pictures from automatically planned viewpoints (front, back, left, right, elevated, \
one interior per floor) with what each view shows. Use it after preview and after every revise_building; compare \
the pictures with the user's request and fix problems with revise_building.")]
    async fn inspect_building(&self, Parameters(args): Parameters<InspectBuildingArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(inspection(self.mc.call("arch.inspect", params(&args)).await))
    }

    #[tool(description = "Change part of a registered building and update only the blocks that differ (the diff \
names the changed components and counts unchanged blocks). Applies to the active preview; a built building needs \
confirm: true (ask the user first) and gets the diff as one undoable change. Waits for a world revision job; \
if job.state is queued/running, poll job_status before inspecting or revising again. Incomplete builds must be \
undone or rebuilt first. At most 3 revisions (config \
maxRevisionRounds) until the user approves more: then show the user the result, ask, and pass userApproved: true. \
Example: {\"name\":\"house\",\"ops\":[{\"op\":\"update\",\"id\":\"roof\",\"set\":{\"overhang\":1}}]}")]
    async fn revise_building(&self, Parameters(args): Parameters<ReviseBuildingArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("arch.revise", params(&args)).await)
    }

    #[tool(description = "The current plan and status (planned / previewing / building / built / incomplete, dimension, \
pending jobId, revision, rounds since approval) of a registered building, or the list of all of them. \
Buildings are saved per world in <world save>/mcmcp/buildings/ with their applied baseline and original block data.")]
    async fn get_building(&self, Parameters(args): Parameters<GetBuildingArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("arch.get", params(&args)).await)
    }
}

/// The inspection as one JSON text block (without image data), then a caption and a PNG per view.
fn inspection(result: Result<Value, McError>) -> CallToolResult {
    let mut value = match result {
        Ok(v) => v,
        Err(e) => return to_tool_result(Err(e)),
    };
    let shots = value.as_object_mut().and_then(|o| o.remove("shots"));
    let mut content = vec![ContentBlock::text(value.to_string())];
    for shot in shots.as_ref().and_then(Value::as_array).into_iter().flatten() {
        let mut caption = shot.clone();
        if let Some(o) = caption.as_object_mut() {
            o.remove("png");
        }
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
    use serde_json::json;

    #[test]
    fn inspection_splits_report_and_pictures() {
        let r = inspection(Ok(json!({"status": {"name": "h"}, "shots": [
            {"view": "front", "label": "front", "png": "iVBORw=="}
        ]})));
        let v = serde_json::to_value(&r).unwrap();
        let content = v["content"].as_array().unwrap();
        assert_eq!(content.len(), 3);
        assert!(!content[0]["text"].as_str().unwrap().contains("shots"));
        assert!(!content[1]["text"].as_str().unwrap().contains("png"));
        assert_eq!(content[2]["type"], "image");
    }
}
