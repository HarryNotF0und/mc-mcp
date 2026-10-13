//! Survival agent tools (stage 5): a fake player that acts like a real one.

use std::time::Duration;

use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::CallToolResult;
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{McServer, params};

/// Agent actions may take minutes; wait this long before returning a still-running job.
const AGENT_WAIT: Duration = Duration::from_secs(60);

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentArgs {
    /// Agent name, default "Agent".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct SpawnArgs {
    /// Agent name (3-16 letters, digits, _), default "Agent". The inventory is saved like a player's.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Where to appear [x, y, z]. Default: next to the first real player.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pos: Option<[f64; 3]>,
    /// Dimension id; default "minecraft:overworld".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dimension: Option<String>,
    /// "survival" (default), "creative", "adventure" or "spectator".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub game_mode: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct GotoArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Target feet position [x, y, z].
    pub pos: [i32; 3],
    /// How close is close enough, in blocks (default 1).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub range: Option<f64>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PosArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Block position [x, y, z].
    pub pos: [i32; 3],
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct CollectArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Search radius around the agent, default 8 (max 32).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub radius: Option<f64>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PlaceArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Where to place [x, y, z].
    pub pos: [i32; 3],
    /// Block state, e.g. "minecraft:crafting_table" or "minecraft:oak_stairs[facing=east]". The matching item must be in the inventory.
    pub block: String,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ItemCountArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Item id, e.g. "minecraft:oak_planks".
    pub item: String,
    /// How many (default 1).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct GatherArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Item to end up with, e.g. "minecraft:oak_log" or "minecraft:cobblestone".
    pub item: String,
    /// Target count in the inventory (items already held count), default 1.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub count: Option<u32>,
    /// Search radius around the agent, default 24 (max 48).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub radius: Option<u32>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct EquipArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Item id from the inventory, e.g. "minecraft:wooden_pickaxe".
    pub item: String,
    /// "mainhand" (default), "offhand", "head", "chest", "legs" or "feet".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub slot: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct BuildArgs {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    /// Blueprint JSON (palette + layers). Give this, `name` or `schematic`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blueprint: Option<Value>,
    /// Saved blueprint name.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// WorldEdit schematic file name (needs WorldEdit).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub schematic: Option<String>,
    /// World position of the blueprint's minimum corner.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin: Option<[i32; 3]>,
    /// 0/90/180/270 clockwise from above.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotation: Option<i32>,
    /// "none", "x" or "z".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mirror: Option<String>,
}

#[tool_router(router = agent_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Survival agent: spawn (or re-spawn after death) a fake player that acts like a real one: \
it walks, mines with tools, picks up items, places blocks from its inventory and crafts. Use the agent_* tools to \
play survival without god-mode building tools. Spawning an existing agent just moves it. Returns its state.")]
    async fn agent_spawn(&self, Parameters(args): Parameters<SpawnArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("agent.spawn", params(&args)).await)
    }

    #[tool(description = "Survival agent: remove the agent from the world (its inventory is saved for next time).")]
    async fn agent_despawn(&self, Parameters(args): Parameters<AgentArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("agent.despawn", params(&args)).await)
    }

    #[tool(description = "Survival agent: position, health, food, game mode, held item, inventory totals, armor, \
and the job it is busy with.")]
    async fn agent_state(&self, Parameters(args): Parameters<AgentArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("agent.getState", params(&args)).await)
    }

    #[tool(description = "Survival agent: walk to a position using A* pathfinding (walks, jumps up 1 block, drops \
up to 4; does not swim, dig or build bridges). Fails with a reason if there is no path or it gets stuck. \
Example: {\"pos\":[120,64,-30]}")]
    async fn agent_goto(&self, Parameters(args): Parameters<GotoArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job_waiting("agent.goto", params(&args), AGENT_WAIT).await)
    }

    #[tool(description = "Survival agent: walk within reach of a block and break it with the best tool in the \
inventory, taking as long as a player would. Drops fall on the ground: follow with agent_collect. \
Warns if the block drops nothing without the right tool (e.g. stone without a pickaxe).")]
    async fn agent_mine(&self, Parameters(args): Parameters<PosArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job_waiting("agent.mineBlock", params(&args), AGENT_WAIT).await)
    }

    #[tool(description = "Survival agent: walk to dropped items nearby and pick them up. Returns what was collected.")]
    async fn agent_collect(&self, Parameters(args): Parameters<CollectArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job_waiting("agent.collectItems", params(&args), AGENT_WAIT).await)
    }

    #[tool(description = "Survival agent: place one block from the inventory (consumes the item). Needs a free \
spot next to an existing block. Example: {\"pos\":[10,64,5],\"block\":\"minecraft:crafting_table\"}")]
    async fn agent_place_block(&self, Parameters(args): Parameters<PlaceArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job_waiting("agent.placeBlock", params(&args), AGENT_WAIT).await)
    }

    #[tool(description = "Survival agent: craft an item from the inventory using the game's recipes (instant). \
Recipes bigger than 2x2 (e.g. pickaxes) need a crafting table within reach. Reports what is missing. \
Example: {\"item\":\"minecraft:oak_planks\",\"count\":12} (one log makes 4).")]
    async fn agent_craft(&self, Parameters(args): Parameters<ItemCountArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("agent.craft", params(&args)).await)
    }

    #[tool(description = "Survival agent: put an inventory item in the hand or an armor slot. Mining picks the best \
tool automatically, so this is mainly for armor or holding something specific.")]
    async fn agent_equip(&self, Parameters(args): Parameters<EquipArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("agent.equip", params(&args)).await)
    }

    #[tool(description = "Survival agent: get N of an item by finding nearby blocks that drop it (by the loot \
tables, with the agent's tools), mining them and collecting the drops, until the inventory holds `count`. \
E.g. oak_log from trees, cobblestone from stone (needs a pickaxe). Long-running: may return state \"running\"; \
poll job_status. Example: {\"item\":\"minecraft:cobblestone\",\"count\":10}")]
    async fn agent_gather(&self, Parameters(args): Parameters<GatherArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job_waiting("agent.gather", params(&args), AGENT_WAIT).await)
    }

    #[tool(description = "Survival agent: build a blueprint (as in place_blueprint) by hand, block by block from the \
inventory, bottom-up, mining anything in the way. Blueprint air is skipped. If materials are missing it fails \
immediately with the list of missing items and counts.")]
    async fn agent_build(&self, Parameters(args): Parameters<BuildArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job_waiting("agent.buildBlueprint", params(&args), AGENT_WAIT).await)
    }
}
