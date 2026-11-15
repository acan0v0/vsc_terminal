package main

import (
	"crypto/subtle"
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	heartbeatInterval = 15 * time.Second
	heartbeatTimeout  = 45 * time.Second
	writeTimeout      = 10 * time.Second
	replayWindow      = 200 // 最近消息回放条数
	maxMessageSize    = 8 << 20
)

// envelope 是两端之间的通用信封：relay 只认 kind，不解释 payload。
// agent/app 各自用统一格式，方便 relay 做回放和路由。
type envelope struct {
	Kind    string          `json:"kind"`             // hello|request|response|event|ping
	ID      string          `json:"id,omitempty"`     // 请求-响应配对
	Seq     uint64          `json:"seq,omitempty"`    // 事件序号，仅 event 使用
	Payload json.RawMessage `json:"payload,omitempty"`
}

type peer struct {
	role string // "agent" | "app"
	conn *websocket.Conn

	send chan []byte
	done chan struct{}
	once sync.Once
}

func newPeer(role string, conn *websocket.Conn) *peer {
	return &peer{role: role, conn: conn, send: make(chan []byte, 256), done: make(chan struct{})}
}

func (p *peer) close() {
	p.once.Do(func() {
		close(p.done)
		p.conn.Close()
	})
}

func (p *peer) enqueue(b []byte) {
	select {
	case p.send <- b:
	case <-p.done:
	default:
		// 发送积压说明对端网络太差，断开让它重连，而不是无限缓冲占内存
		p.close()
	}
}

// writeLoop 独占写操作，gorilla 不允许并发写。
func (p *peer) writeLoop() {
	ping := time.NewTicker(heartbeatInterval)
	defer func() {
		ping.Stop()
		p.close()
	}()
	for {
		select {
		case b := <-p.send:
			p.conn.SetWriteDeadline(time.Now().Add(writeTimeout))
			if err := p.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				return
			}
		case <-ping.C:
			p.conn.SetWriteDeadline(time.Now().Add(writeTimeout))
			if err := p.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		case <-p.done:
			return
		}
	}
}

func (p *peer) readLoop(handler func(*peer, []byte)) {
	defer p.close()
	p.conn.SetReadLimit(maxMessageSize)
	p.conn.SetReadDeadline(time.Now().Add(heartbeatTimeout))
	p.conn.SetPongHandler(func(string) error {
		return p.conn.SetReadDeadline(time.Now().Add(heartbeatTimeout))
	})
	for {
		_, b, err := p.conn.ReadMessage()
		if err != nil {
			return
		}
		p.conn.SetReadDeadline(time.Now().Add(heartbeatTimeout))
		handler(p, b)
	}
}

type room struct {
	mu    sync.Mutex
	agent *peer
	app   *peer
	seq   uint64
	ring  []envelope     // 最近事件，agent 重连时按 seq 回放
	pend  map[string]chan []byte // request id -> 等待中的响应
}

func newRoom() *room {
	return &room{pend: make(map[string]chan []byte)}
}

type server struct {
	token string
	mu    sync.Mutex
	rooms map[string]*room
}

func (s *server) room(name string) *room {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.rooms[name]
	if !ok {
		r = newRoom()
		s.rooms[name] = r
	}
	return r
}

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("room")
	role := r.URL.Query().Get("role")
	if role != "agent" && role != "app" {
		http.Error(w, "bad role", http.StatusBadRequest)
		return
	}
	tok := r.Header.Get("Authorization")
	if strings.HasPrefix(tok, "Bearer ") {
		tok = strings.TrimPrefix(tok, "Bearer ")
	}
	if name == "" || subtle.ConstantTimeCompare([]byte(tok), []byte(s.token)) != 1 {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	up := websocket.Upgrader{
		CheckOrigin: func(*http.Request) bool { return true }, // 仅监听回环，已由 SSH 隧道保护
	}
	conn, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	p := newPeer(role, conn)
	rm := s.room(name)
	attach(rm, p)
	go p.writeLoop()
	p.readLoop(func(_ *peer, b []byte) { rm.dispatch(p, b) })
	detach(rm, p)
}

func attach(rm *room, p *peer) {
	rm.mu.Lock()
	defer rm.mu.Unlock()
	if p.role == "agent" {
		if old := rm.agent; old != nil {
			old.close()
		}
		rm.agent = p
	} else {
		if old := rm.app; old != nil {
			old.close()
		}
		rm.app = p
		// 回放在锁内同步完成，保证历史事件先于之后的新事件入队
		for _, e := range rm.ring {
			b, _ := json.Marshal(e)
			p.enqueue(b)
		}
	}
	log.Printf("attach role=%s room", p.role)
}

func detach(rm *room, p *peer) {
	rm.mu.Lock()
	defer rm.mu.Unlock()
	if p.role == "agent" && rm.agent == p {
		rm.agent = nil
	}
	if p.role == "app" && rm.app == p {
		rm.app = nil
	}
	p.close()
	log.Printf("detach role=%s", p.role)
}

func (rm *room) dispatch(from *peer, b []byte) {
	var env envelope
	if err := json.Unmarshal(b, &env); err != nil {
		return
	}
	if len(b) > maxMessageSize {
		return
	}

	rm.mu.Lock()
	defer rm.mu.Unlock()

	switch {
	case env.Kind == "event" && from.role == "agent":
		rm.seq++
		env.Seq = rm.seq
		nb, _ := json.Marshal(env)
		rm.ring = append(rm.ring, envelope{Kind: env.Kind, Seq: env.Seq, Payload: env.Payload})
		if len(rm.ring) > replayWindow {
			rm.ring = rm.ring[len(rm.ring)-replayWindow:]
		}
		if rm.app != nil {
			rm.app.enqueue(nb)
		}
	case env.Kind == "request" && from.role == "app":
		if rm.agent == nil {
			b, _ := json.Marshal(envelope{Kind: "response", ID: env.ID,
				Payload: mustJSON(map[string]string{"error": "agent offline"})})
			from.enqueue(b)
			return
		}
		ch := make(chan []byte, 1)
		rm.pend[env.ID] = ch
		rm.agent.enqueue(b) // 原样转发，ID 不变
		go func() {
			select {
			case resp := <-ch:
				from.enqueue(resp)
			case <-time.After(30 * time.Second):
				nb, _ := json.Marshal(envelope{Kind: "response", ID: env.ID,
					Payload: mustJSON(map[string]string{"error": "timeout"})})
				from.enqueue(nb)
			case <-from.done:
			}
			rm.mu.Lock()
			delete(rm.pend, env.ID)
			rm.mu.Unlock()
		}()
	case env.Kind == "response" && from.role == "agent":
		if ch, ok := rm.pend[env.ID]; ok {
			select {
			case ch <- b:
			default:
			}
		}
	case env.Kind == "ping":
		from.enqueue(b)
	}
}

func mustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func readToken(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		log.Fatalf("read token: %v", err)
	}
	return strings.TrimSpace(string(b))
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8443", "listen address")
	tokenFile := flag.String("token-file", "/etc/relay/token", "file containing shared token")
	flag.Parse()

	s := &server{token: readToken(*tokenFile), rooms: make(map[string]*room)}

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		s.mu.Lock()
		n := len(s.rooms)
		s.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]int{"rooms": n})
	})

	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("relay listening on %s", *addr)
	log.Fatal(srv.ListenAndServe())
}
