use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::CallToolResult;
use rmcp::{ErrorData, schemars, tool, tool_router};
use serde::{Deserialize, Serialize};
use serde_json::json;

use super::{McServer, params};

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct JobStatusArgs {
    /// Job id returned by a build tool. Omit to list recent jobs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub job_id: Option<u32>,
}

#[derive(Debug, Deserialize, Serialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct CancelJobArgs {
    /// Job id to cancel.
    pub job_id: u32,
}

#[tool_router(router = jobs_router, vis = "pub(super)")]
impl McServer {
    #[tool(description = "Status of a build job: state (queued, running, done, failed, cancelled), done/total blocks, \
changed (blocks that actually differed), changeSetId, error. Omit jobId to list recent jobs. \
Errors: 1005 unknown job id.")]
    async fn job_status(&self, Parameters(args): Parameters<JobStatusArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("job.status", params(&args)).await)
    }

    #[tool(description = "Cancel a queued or running job. Blocks already placed stay and are recorded as one undoable \
change, so undo afterwards reverts the partial work.")]
    async fn cancel_job(&self, Parameters(args): Parameters<CancelJobArgs>) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("job.cancel", params(&args)).await)
    }

    #[tool(description = "Undo the most recent change (a place_blocks, fill_region or clear_region call), restoring \
the previous blocks including chest contents and other block entity data. Repeatable up to 50 steps. \
Fails with \"nothing to undo\" when the history is empty.")]
    async fn undo(&self) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("history.undo", json!({})).await)
    }

    #[tool(description = "Redo the most recently undone change. Any new change clears the redo stack.")]
    async fn redo(&self) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward_job("history.redo", json!({})).await)
    }

    #[tool(description = "List undoable and redoable changes, most recent first: id, description, dimension, number of \
blocks and bounding box.")]
    async fn list_history(&self) -> Result<CallToolResult, ErrorData> {
        Ok(self.forward("history.list", json!({})).await)
    }
}
