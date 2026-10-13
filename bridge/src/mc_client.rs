//! WebSocket client for the Minecraft mod.
//!
//! - Connects lazily on the first call and again after the connection drops,
//!   so the bridge can start before the game and survive world reloads.
//! - Matches responses to requests by JSON-RPC `id`.
//! - Every call has a timeout; pending calls fail immediately on disconnect.
//! - Server-pushed notifications (no `id`) are broadcast to subscribers.

use std::collections::HashMap;
use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tokio::sync::{Mutex, broadcast, mpsc, oneshot};
use tokio_tungstenite::tungstenite::Message;

/// A JSON-RPC error returned by the mod (see `protocol/spec.md` for codes).
#[derive(Debug, Clone, PartialEq)]
pub struct RpcError {
    pub code: i64,
    pub message: String,
    pub data: Option<Value>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum McError {
    /// Could not reach the mod at all (game not running, wrong port, ...).
    Connect(String),
    /// The mod rejected the token.
    Auth(String),
    /// No response within the timeout.
    Timeout(Duration),
    /// The connection dropped while the request was in flight.
    Disconnected,
    /// The mod answered with a JSON-RPC error.
    Rpc(RpcError),
    /// The mod sent something we could not understand.
    Protocol(String),
}

impl fmt::Display for McError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            McError::Connect(e) => write!(
                f,
                "cannot connect to Minecraft ({e}). Is the game running with the mc-mcp mod and a world loaded?"
            ),
            McError::Auth(e) => write!(
                f,
                "Minecraft rejected the token ({e}). Check MCMCP_TOKEN against config/mcmcp.json."
            ),
            McError::Timeout(d) => write!(f, "Minecraft did not respond within {} ms", d.as_millis()),
            McError::Disconnected => write!(f, "connection to Minecraft was lost during the request"),
            McError::Rpc(e) => {
                write!(f, "error {}: {}", e.code, e.message)?;
                if let Some(data) = &e.data {
                    write!(f, " {data}")?;
                }
                Ok(())
            }
            McError::Protocol(e) => write!(f, "protocol error: {e}"),
        }
    }
}

impl std::error::Error for McError {}

type Pending = Arc<std::sync::Mutex<HashMap<u64, oneshot::Sender<Result<Value, McError>>>>>;

/// Handle to one live WebSocket connection.
#[derive(Clone)]
struct Connection {
    outgoing: mpsc::UnboundedSender<Message>,
    /// Cleared by the reader task when the socket closes.
    alive: Arc<AtomicBool>,
}

impl Connection {
    fn is_alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }
}

#[derive(Clone, Debug)]
pub struct McClientConfig {
    pub url: String,
    pub token: String,
    pub request_timeout: Duration,
    pub connect_timeout: Duration,
}

pub struct McClient {
    config: McClientConfig,
    next_id: AtomicU64,
    pending: Pending,
    conn: Mutex<Option<Connection>>,
    notifications: broadcast::Sender<Value>,
}

impl McClient {
    pub fn new(config: McClientConfig) -> Self {
        let (notifications, _) = broadcast::channel(256);
        Self {
            config,
            next_id: AtomicU64::new(1),
            pending: Arc::default(),
            conn: Mutex::new(None),
            notifications,
        }
    }

    /// Subscribe to server-pushed notifications (`job.progress`, `log`, ...).
    pub fn subscribe(&self) -> broadcast::Receiver<Value> {
        self.notifications.subscribe()
    }

    /// Call a mod method with the default timeout.
    pub async fn call(&self, method: &str, params: Value) -> Result<Value, McError> {
        self.call_with_timeout(method, params, self.config.request_timeout).await
    }

    pub async fn call_with_timeout(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, McError> {
        let conn = self.ensure_connected().await?;
        self.send_request(&conn, method, params, timeout).await
    }

    async fn send_request(
        &self,
        conn: &Connection,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, McError> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        self.pending.lock().unwrap().insert(id, tx);
        // The reader clears `alive` before draining `pending`, so checking after the insert
        // guarantees we either get drained or notice the dead connection here.
        if !conn.is_alive() {
            self.pending.lock().unwrap().remove(&id);
            return Err(McError::Disconnected);
        }
        let msg = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        if conn.outgoing.send(Message::text(msg.to_string())).is_err() {
            self.pending.lock().unwrap().remove(&id);
            return Err(McError::Disconnected);
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(McError::Disconnected),
            Err(_) => {
                self.pending.lock().unwrap().remove(&id);
                Err(McError::Timeout(timeout))
            }
        }
    }

    /// Returns the live connection, (re)connecting and authenticating if needed.
    async fn ensure_connected(&self) -> Result<Connection, McError> {
        let mut guard = self.conn.lock().await;
        if let Some(conn) = guard.as_ref()
            && conn.is_alive()
        {
            return Ok(conn.clone());
        }
        *guard = None;

        let connect = tokio_tungstenite::connect_async(self.config.url.as_str());
        let (ws, _) = match tokio::time::timeout(self.config.connect_timeout, connect).await {
            Ok(Ok(ok)) => ok,
            Ok(Err(e)) => return Err(McError::Connect(e.to_string())),
            Err(_) => return Err(McError::Connect("connect timed out".into())),
        };
        tracing::info!(url = %self.config.url, "connected to Minecraft");

        let (mut sink, mut stream) = ws.split();
        let (outgoing, mut outgoing_rx) = mpsc::unbounded_channel::<Message>();

        let conn = Connection {
            outgoing,
            alive: Arc::new(AtomicBool::new(true)),
        };

        // Writer task: ends when every sender is dropped or the socket fails.
        tokio::spawn(async move {
            while let Some(msg) = outgoing_rx.recv().await {
                if sink.send(msg).await.is_err() {
                    break;
                }
            }
            let _ = sink.close().await;
        });

        // Reader task: routes responses and notifications; on exit, marks the connection dead
        // (so the next call reconnects) and fails every pending call.
        let pending = self.pending.clone();
        let notifications = self.notifications.clone();
        let alive = conn.alive.clone();
        tokio::spawn(async move {
            while let Some(frame) = stream.next().await {
                let text = match frame {
                    Ok(Message::Text(t)) => t,
                    Ok(Message::Close(_)) | Err(_) => break,
                    Ok(_) => continue,
                };
                match serde_json::from_str::<Value>(&text) {
                    Ok(msg) => route_message(msg, &pending, &notifications),
                    Err(e) => tracing::warn!("invalid JSON from Minecraft: {e}"),
                }
            }
            tracing::info!("disconnected from Minecraft");
            alive.store(false, Ordering::SeqCst);
            for (_, tx) in pending.lock().unwrap().drain() {
                let _ = tx.send(Err(McError::Disconnected));
            }
        });

        // The first message must be `auth`.
        let auth = self
            .send_request(
                &conn,
                "auth",
                json!({"token": self.config.token}),
                self.config.connect_timeout,
            )
            .await;
        match auth {
            Ok(_) => {}
            Err(McError::Rpc(e)) => return Err(McError::Auth(e.message)),
            Err(e) => return Err(McError::Auth(e.to_string())),
        }

        *guard = Some(conn.clone());
        Ok(conn)
    }
}

fn route_message(msg: Value, pending: &Pending, notifications: &broadcast::Sender<Value>) {
    let id = msg.get("id").and_then(Value::as_u64);
    let Some(id) = id else {
        if msg.get("method").is_some() {
            let _ = notifications.send(msg);
        }
        return;
    };
    let Some(tx) = pending.lock().unwrap().remove(&id) else {
        tracing::debug!(id, "response for unknown or timed-out request");
        return;
    };
    let result = if let Some(err) = msg.get("error") {
        Err(McError::Rpc(RpcError {
            code: err.get("code").and_then(Value::as_i64).unwrap_or(-32603),
            message: err
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("unknown error")
                .to_string(),
            data: err.get("data").cloned(),
        }))
    } else if let Some(result) = msg.get("result") {
        Ok(result.clone())
    } else {
        Err(McError::Protocol("response has neither result nor error".into()))
    };
    let _ = tx.send(result);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;
    use tokio::net::TcpListener;

    /// Behaviour of the fake mod for one connection.
    #[derive(Clone, Copy)]
    enum Mode {
        Normal,
        /// Never answers non-auth requests.
        Silent,
        /// Closes the socket upon receiving the first non-auth request.
        DropOnRequest,
    }

    /// Starts a fake mod server; returns its ws:// URL and a count of accepted connections.
    async fn fake_mod(token: &'static str, mode: Mode) -> (String, Arc<AtomicUsize>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let accepted = Arc::new(AtomicUsize::new(0));
        let counter = accepted.clone();
        tokio::spawn(async move {
            loop {
                let Ok((tcp, _)) = listener.accept().await else { return };
                counter.fetch_add(1, Ordering::SeqCst);
                tokio::spawn(async move {
                    let mut ws = tokio_tungstenite::accept_async(tcp).await.unwrap();
                    while let Some(Ok(Message::Text(text))) = ws.next().await {
                        let req: Value = serde_json::from_str(&text).unwrap();
                        let id = req["id"].clone();
                        let method = req["method"].as_str().unwrap_or_default();
                        let reply = match method {
                            "auth" if req["params"]["token"] == token => {
                                json!({"jsonrpc":"2.0","id":id,"result":{"ok":true}})
                            }
                            "auth" => {
                                let r = json!({"jsonrpc":"2.0","id":id,"error":{"code":-32001,"message":"bad token"}});
                                let _ = ws.send(Message::text(r.to_string())).await;
                                return;
                            }
                            _ => match mode {
                                Mode::Silent => continue,
                                Mode::DropOnRequest => return,
                                Mode::Normal => match method {
                                    "system.ping" => {
                                        // Push a notification before the response.
                                        let n = json!({"jsonrpc":"2.0","method":"log","params":{"msg":"hi"}});
                                        ws.send(Message::text(n.to_string())).await.unwrap();
                                        json!({"jsonrpc":"2.0","id":id,"result":{"pong":true}})
                                    }
                                    _ => json!({"jsonrpc":"2.0","id":id,"error":{"code":1001,"message":"outside allowed region","data":{"x":1}}}),
                                },
                            },
                        };
                        ws.send(Message::text(reply.to_string())).await.unwrap();
                    }
                });
            }
        });
        (format!("ws://{addr}"), accepted)
    }

    fn client(url: String, token: &str) -> McClient {
        McClient::new(McClientConfig {
            url,
            token: token.into(),
            request_timeout: Duration::from_millis(300),
            connect_timeout: Duration::from_millis(500),
        })
    }

    #[tokio::test]
    async fn call_matches_response_and_forwards_notifications() {
        let c = client(fake_mod("t", Mode::Normal).await.0, "t");
        let mut notes = c.subscribe();
        let r = c.call("system.ping", json!({})).await.unwrap();
        assert_eq!(r, json!({"pong": true}));
        let n = notes.recv().await.unwrap();
        assert_eq!(n["method"], "log");
    }

    #[tokio::test]
    async fn concurrent_calls_get_their_own_responses() {
        let c = Arc::new(client(fake_mod("t", Mode::Normal).await.0, "t"));
        let (a, b) = tokio::join!(c.call("system.ping", json!({})), c.call("world.fillRegion", json!({})));
        assert_eq!(a.unwrap(), json!({"pong": true}));
        assert!(matches!(b, Err(McError::Rpc(RpcError { code: 1001, .. }))));
    }

    #[tokio::test]
    async fn rpc_error_is_mapped() {
        let c = client(fake_mod("t", Mode::Normal).await.0, "t");
        let err = c.call("world.fillRegion", json!({})).await.unwrap_err();
        assert_eq!(
            err,
            McError::Rpc(RpcError {
                code: 1001,
                message: "outside allowed region".into(),
                data: Some(json!({"x": 1}))
            })
        );
        assert!(err.to_string().starts_with("error 1001: outside allowed region"));
    }

    #[tokio::test]
    async fn wrong_token_is_auth_error() {
        let c = client(fake_mod("t", Mode::Normal).await.0, "wrong");
        let err = c.call("system.ping", json!({})).await.unwrap_err();
        assert!(matches!(err, McError::Auth(_)), "{err:?}");
    }

    #[tokio::test]
    async fn nothing_listening_is_connect_error() {
        // Bind then drop to get a port that is (almost certainly) closed.
        let port = TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap().port();
        let c = client(format!("ws://127.0.0.1:{port}"), "t");
        let start = std::time::Instant::now();
        let err = c.call("system.ping", json!({})).await.unwrap_err();
        assert!(matches!(err, McError::Connect(_)), "{err:?}");
        assert!(start.elapsed() < Duration::from_secs(2));
    }

    #[tokio::test]
    async fn silent_server_times_out() {
        let c = client(fake_mod("t", Mode::Silent).await.0, "t");
        let err = c.call("system.ping", json!({})).await.unwrap_err();
        assert_eq!(err, McError::Timeout(Duration::from_millis(300)));
        assert!(c.pending.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn disconnect_fails_pending_then_reconnects() {
        let (url, accepted) = fake_mod("t", Mode::DropOnRequest).await;
        let c = client(url, "t");
        let err = c.call("system.ping", json!({})).await.unwrap_err();
        assert_eq!(err, McError::Disconnected);
        let err = c.call("system.ping", json!({})).await.unwrap_err();
        assert_eq!(err, McError::Disconnected);
        assert_eq!(accepted.load(Ordering::SeqCst), 2, "second call must open a new connection");
    }

    #[tokio::test]
    async fn healthy_connection_is_reused() {
        let (url, accepted) = fake_mod("t", Mode::Normal).await;
        let c = client(url, "t");
        c.call("system.ping", json!({})).await.unwrap();
        c.call("system.ping", json!({})).await.unwrap();
        assert_eq!(accepted.load(Ordering::SeqCst), 1);
    }
}
