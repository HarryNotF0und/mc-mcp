use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::CallToolResult;
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{McServer, Region, params, to_tool_result};
use crate::mc_client::{McError, RpcError};

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct RunScriptArgs {
    /// JavaScript source using the global `b` (see resource mc://docs/script-api).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    /// Instead of `source`: path of a .js file on the machine running the bridge,
    /// e.g. "examples/scripts/tower.js" (relative to the bridge's working directory).
    #[serde(skip_serializing)]
    pub source_file: Option<String>,
    /// World position [x, y, z] that script coordinates are relative to.
    pub origin: [i32; 3],
    /// true: run the script and report block count, bounding box and block counts without placing anything.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dry_run: Option<bool>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded (for b.get and for placing) instead of failing.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PlaceBlueprintArgs {
    /// Blueprint in palette + layers format: {"palette":{".":"minecraft:air","S":"minecraft:stone"},
    /// "layers":[["SS","S."]]} (layers bottom-up = y; strings = z; characters = x). Give this or `name`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blueprint: Option<Value>,
    /// Name of a blueprint saved with save_blueprint. Give this, `blueprint` or `schematic`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// WorldEdit schematic file (.schem / .schematic) in WorldEdit's schematics folder, e.g. "castle"
    /// (see list_blueprints). Needs WorldEdit on the server. `origin` is required.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub schematic: Option<String>,
    /// World position of the placed blueprint's minimum corner. Defaults to the blueprint's own origin.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin: Option<[i32; 3]>,
    /// Clockwise rotation seen from above: 0, 90, 180 or 270 (90 turns north into east).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotation: Option<i32>,
    /// Mirror before rotating: "none" (default), "x" (swap east/west) or "z" (swap north/south).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mirror: Option<String>,
    /// true: don't place the blueprint's air, leaving existing blocks there.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub skip_air: Option<bool>,
    /// true: report what would be placed without placing it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dry_run: Option<bool>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct SaveBlueprintArgs {
    /// Region to export; both corners inclusive; at most 262144 blocks.
    pub region: Region,
    /// Save under this name (a-z, 0-9, _ and -) on the server and return only a summary.
    /// Omit to get the blueprint JSON back directly (best for small regions).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// "json" (default, our palette + layers format) or "schem" (a WorldEdit Sponge schematic in
    /// WorldEdit's schematics folder, usable by WorldEdit and other tools; needs WorldEdit and `name`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub format: Option<String>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// Load chunks that are not loaded instead of failing with error 1004.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load_chunks: Option<bool>,
}

#[tool_router(router = scripts_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Build with a JavaScript program. The script calls b.set(x, y, z, block), \
b.fill(x1,y1,z1,x2,y2,z2, block, mode), b.line(...), b.cylinder(x,y,z, radius, height, block, hollow), \
b.sphere(x,y,z, radius, block, hollow), b.get(x,y,z), b.log(...); coordinates are relative to `origin`. \
Read resource mc://docs/script-api for the full API. Everything the script sets is placed as ONE undoable change \
after it finishes. Use dryRun: true first to check the block count and bounding box. \
Limits: 5 s run time, 262144 blocks, no Java access. Script errors return code 1006 with the line number. \
Example: {\"origin\":[100,64,200],\"source\":\"b.cylinder(0,0,0,7,30,'minecraft:stone_bricks',true)\"}")]
    async fn run_script(&self, Parameters(mut args): Parameters<RunScriptArgs>) -> Result<CallToolResult, ErrorData> {
        if let Some(path) = args.source_file.take() {
            match tokio::fs::read_to_string(&path).await {
                Ok(text) => args.source = Some(text),
                Err(e) => {
                    return Ok(to_tool_result(Err(McError::Rpc(RpcError {
                        code: -32602,
                        message: format!("cannot read sourceFile {path}: {e}"),
                        data: None,
                    }))));
                }
            }
        }
        if args.source.is_none() {
            return Ok(to_tool_result(Err(McError::Rpc(RpcError {
                code: -32602,
                message: "pass source or sourceFile".into(),
                data: None,
            }))));
        }
        Ok(self.forward_job("build.runScript", params(&args)).await)
    }

    #[tool(description = "Place a blueprint (palette + layers format, as returned by get_layer_slices or save_blueprint), \
optionally rotated 0/90/180/270 degrees clockwise and/or mirrored. Block states are rotated too \
(stairs, logs, doors face the right way). `origin` is where the rotated footprint's minimum corner goes. \
Also places WorldEdit schematics (schematic: file name) when WorldEdit is installed. \
One undoable change. Example: {\"name\":\"house\",\"origin\":[120,64,200],\"rotation\":90}")]
    async fn place_blueprint(&self, Parameters(args): Parameters<PlaceBlueprintArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("build.placeBlueprint", params(&args)).await)
    }

    #[tool(description = "Export a region of the world as a blueprint (palette + layers). With `name`, the blueprint is \
saved on the server for later place_blueprint calls and only a summary is returned; without it the full JSON is returned. \
Example: {\"region\":{\"min\":[100,64,200],\"max\":[106,70,206]},\"name\":\"house\"}")]
    async fn save_blueprint(&self, Parameters(args): Parameters<SaveBlueprintArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("build.saveRegionAsBlueprint", params(&args)).await)
    }

    #[tool(description = "List saved blueprints (for place_blueprint name) and, when WorldEdit is installed, \
schematic files in WorldEdit's schematics folder (for place_blueprint schematic). Drop downloaded .schem files \
into that folder to build them.")]
    async fn list_blueprints(&self) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("build.listBlueprints", serde_json::json!({})).await)
    }
}
