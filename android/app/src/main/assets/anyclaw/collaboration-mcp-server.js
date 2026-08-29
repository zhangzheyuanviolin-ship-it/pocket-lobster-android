#!/usr/bin/env node

const BASE_URL = String(process.env.POCKET_LOBSTER_COLLABORATION_URL || "http://127.0.0.1:18923").replace(/\/$/, "");
const CALLER_AGENT_ID = "claude";

function tool(name, description, properties, required = []) {
  return {
    name,
    description,
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: { runId: { type: "string" }, turnNumber: { type: "integer", minimum: 1 }, leaderLeaseId: { type: "string" }, ...properties },
      required: [...new Set(["runId", "turnNumber", "leaderLeaseId", ...required])]
    }
  };
}

const taskIds = { type: "array", items: { type: "string" }, minItems: 1 };
const tools = [
  tool("collaboration_delegate", "Delegate one bounded task to a collaboration member. Returns a durable taskId immediately.", {
    runId: { type: "string" }, agentId: { type: "string", enum: ["codex", "claude", "minis"] }, objective: { type: "string" }, expectedOutput: { type: "string" }, requiresSharedWorkspace: { type: "boolean" }, parentTaskId: { type: "string" }
  }, ["runId", "agentId", "objective"]),
  tool("collaboration_delegate_many", "Delegate independent tasks to one or both collaboration members in parallel.", {
    runId: { type: "string" }, assignments: { type: "array", minItems: 1, maxItems: 2, items: { type: "object", additionalProperties: false, properties: { agentId: { type: "string", enum: ["codex", "claude", "minis"] }, objective: { type: "string" }, expectedOutput: { type: "string" }, requiresSharedWorkspace: { type: "boolean" } }, required: ["agentId", "objective"] } }
  }, ["runId", "assignments"]),
  tool("collaboration_status", "Read authoritative status, heartbeat, errors and completed output for collaboration tasks.", { runId: { type: "string" }, taskIds }, ["runId"]),
  tool("collaboration_wait", "Wait for any or all specified tasks to reach a terminal state and return completed outputs as evidence.", { runId: { type: "string" }, taskIds, waitMode: { type: "string", enum: ["all", "any"] }, timeoutSeconds: { type: "integer", minimum: 1, maximum: 120 } }, ["runId", "taskIds"]),
  tool("collaboration_followup", "Send a bounded follow-up instruction to the member that completed an earlier task.", { runId: { type: "string" }, parentTaskId: { type: "string" }, instruction: { type: "string" }, expectedOutput: { type: "string" } }, ["runId", "parentTaskId", "instruction"]),
  tool("collaboration_cancel", "Cancel an active collaboration member task.", { runId: { type: "string" }, taskId: { type: "string" } }, ["runId", "taskId"]),
  tool("collaboration_finish", "Submit the final user-facing response or clarification. Every task created this turn must be terminal and listed in evidenceTaskIds.", { runId: { type: "string" }, action: { type: "string", enum: ["respond", "ask_user"] }, message: { type: "string" }, evidenceTaskIds: { type: "array", items: { type: "string" } } }, ["runId", "action", "message", "evidenceTaskIds"])
];

async function callTool(name, args) {
  const response = await fetch(BASE_URL + "/collaboration-api/tool", {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({ callerAgentId: CALLER_AGENT_ID, tool: name, arguments: args || {} })
  });
  const text = await response.text();
  let payload;
  try { payload = text ? JSON.parse(text) : {}; } catch { payload = { ok: false, error: text || "invalid_host_response" }; }
  if (!response.ok || payload.ok !== true) throw new Error(String(payload.error || ("HTTP " + response.status)));
  return payload;
}

function write(value) { process.stdout.write(JSON.stringify(value) + "\n"); }
function result(id, value) { return { jsonrpc: "2.0", id, result: value }; }

let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    let message;
    try { message = JSON.parse(line); } catch { continue; }
    void handle(message);
  }
});

async function handle(message) {
  const id = message && message.id;
  const method = message && message.method;
  if (method === "initialize") {
    write(result(id, { protocolVersion: message.params && message.params.protocolVersion || "2025-11-25", capabilities: { tools: {} }, serverInfo: { name: "pocket-lobster-collaboration", version: "1.0.0" } }));
  } else if (method === "notifications/initialized") {
    return;
  } else if (method === "ping") {
    write(result(id, {}));
  } else if (method === "tools/list") {
    write(result(id, { tools }));
  } else if (method === "tools/call") {
    try {
      const payload = await callTool(String(message.params && message.params.name || ""), message.params && message.params.arguments);
      write(result(id, { content: [{ type: "text", text: JSON.stringify(payload) }], isError: false }));
    } catch (error) {
      write(result(id, { content: [{ type: "text", text: "Tool error: " + String(error && error.message || error) }], isError: true }));
    }
  } else if (typeof id !== "undefined") {
    write({ jsonrpc: "2.0", id, error: { code: -32601, message: "Method not found: " + method } });
  }
}
