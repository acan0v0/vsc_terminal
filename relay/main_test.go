package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func startRelay(t *testing.T) (*httptest.Server, string) {
	t.Helper()
	dir := t.TempDir()
	tokPath := filepath.Join(dir, "token")
	if err := os.WriteFile(tokPath, []byte("test-token\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	s := &server{token: readToken(tokPath), rooms: make(map[string]*room)}
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, "test-token"
}

func dial(t *testing.T, srv *httptest.Server, room, role, tok string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ws?room=" + room + "&role=" + role
	h := http.Header{}
	if tok != "" {
		h.Set("Authorization", "Bearer "+tok)
	}
	return websocket.DefaultDialer.Dial(url, h)
}

func send(t *testing.T, c *websocket.Conn, env envelope) {
	t.Helper()
	b, _ := json.Marshal(env)
	if err := c.WriteMessage(websocket.TextMessage, b); err != nil {
		t.Fatal(err)
	}
}

func recv(t *testing.T, c *websocket.Conn) envelope {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, b, err := c.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	var e envelope
	if err := json.Unmarshal(b, &e); err != nil {
		t.Fatal(err)
	}
	return e
}

func TestBadTokenRejected(t *testing.T) {
	srv, _ := startRelay(t)
	_, _, err := dial(t, srv, "r", "app", "")
	if err == nil {
		t.Fatal("expected unauthorized")
	}
}

func TestEventReplayOnAppReconnect(t *testing.T) {
	srv, tok := startRelay(t)

	agent, _, err := dial(t, srv, "r", "agent", tok)
	if err != nil {
		t.Fatal(err)
	}
	defer agent.Close()

	app1, _, err := dial(t, srv, "r", "app", tok)
	if err != nil {
		t.Fatal(err)
	}
	send(t, agent, envelope{Kind: "event", Payload: mustJSON(map[string]string{"text": "hello"})})
	if got := recv(t, app1); got.Kind != "event" || got.Seq != 1 {
		t.Fatalf("unexpected event: %+v", got)
	}
	app1.Close()
	time.Sleep(50 * time.Millisecond) // 让 relay 处理 detach

	// 新 app 连上应收到历史回放
	app2, _, err := dial(t, srv, "r", "app", tok)
	if err != nil {
		t.Fatal(err)
	}
	defer app2.Close()
	got := recv(t, app2)
	if got.Kind != "event" || got.Seq != 1 || string(got.Payload) != `{"text":"hello"}` {
		t.Fatalf("replay mismatch: %+v", got)
	}

	// 继续推送 seq 递增
	send(t, agent, envelope{Kind: "event", Payload: mustJSON(map[string]string{"text": "world"})})
	if got := recv(t, app2); got.Seq != 2 {
		t.Fatalf("expected seq 2, got %d", got.Seq)
	}
}

func TestRequestResponseRouting(t *testing.T) {
	srv, tok := startRelay(t)

	agent, _, err := dial(t, srv, "r", "agent", tok)
	if err != nil {
		t.Fatal(err)
	}
	defer agent.Close()
	app, _, err := dial(t, srv, "r", "app", tok)
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()

	send(t, app, envelope{Kind: "request", ID: "q1", Payload: mustJSON(map[string]string{"op": "tree"})})
	req := recv(t, agent)
	if req.Kind != "request" || req.ID != "q1" {
		t.Fatalf("agent got %+v", req)
	}
	send(t, agent, envelope{Kind: "response", ID: "q1", Payload: mustJSON(map[string]string{"ok": "yes"})})
	resp := recv(t, app)
	if resp.Kind != "response" || resp.ID != "q1" || string(resp.Payload) != `{"ok":"yes"}` {
		t.Fatalf("app got %+v", resp)
	}
}

func TestRequestWhenAgentOffline(t *testing.T) {
	srv, tok := startRelay(t)
	app, _, err := dial(t, srv, "r", "app", tok)
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()

	send(t, app, envelope{Kind: "request", ID: "q1"})
	resp := recv(t, app)
	if resp.Kind != "response" || resp.ID != "q1" || !strings.Contains(string(resp.Payload), "agent offline") {
		t.Fatalf("unexpected: %+v", resp)
	}
}

func TestAppOfflineDoesNotBlockAgent(t *testing.T) {
	srv, tok := startRelay(t)
	agent, _, err := dial(t, srv, "r", "agent", tok)
	if err != nil {
		t.Fatal(err)
	}
	defer agent.Close()

	// 没有 app 时连续发事件应不报错、不阻塞
	for i := 0; i < 5; i++ {
		send(t, agent, envelope{Kind: "event", Payload: mustJSON(map[string]int{"i": i})})
	}
	time.Sleep(50 * time.Millisecond)
	// agent 仍可收 request（说明没被卡住）
	app, _, err := dial(t, srv, "r", "app", tok)
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	// 回放应在
	for i := 0; i < 5; i++ {
		if got := recv(t, app); got.Seq != uint64(i+1) {
			t.Fatalf("replay seq %d", got.Seq)
		}
	}
}
