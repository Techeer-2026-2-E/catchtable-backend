// Open-loop generator for this lab's three fixed loopback ports. No automatic application retry.
package main

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptrace"
	"os"
	"regexp"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

type Config struct {
	Network                                        string
	AppPrefix                                      string
	Follow                                         bool
	FinalLimit                                     int
	LaunchSpanMS                                   int
	Flow                                           string
	Apps, Users, Window, Before, After, Background int
	Date                                           string
}

type Reply struct {
	ReceiptID    string `json:"receiptId,omitempty"`
	Sequence     int    `json:"intakeSequence,omitempty"`
	ReceivedDate string `json:"receivedDate,omitempty"`
	StoreID      int    `json:"storeId"`
	WaitingID    int    `json:"waitingId,omitempty"`
	WaitNumber   int    `json:"waitNumber,omitempty"`
	TeamsAhead   *int   `json:"teamsAhead,omitempty"`
	HoldID       int    `json:"holdId,omitempty"`
	TableID      int    `json:"tableId,omitempty"`
	Status       string `json:"status,omitempty"`
	Code         string `json:"code,omitempty"`
	Date         string `json:"date,omitempty"`
	PartySize    int    `json:"partySize,omitempty"`
	PartyCount   int    `json:"partyCount,omitempty"`
	Slots        []struct {
		Start     string `json:"reservationStartAt"`
		Count     int    `json:"availableTableCount"`
		Available bool   `json:"available"`
	} `json:"slots,omitempty"`
}

type Sample struct {
	Location, RetryAfter                                        string
	ID, Cohort, Op, Outcome                                     string
	App, Store, Member, Status, Bytes, Slots, Available         int
	DueUS, JourneyStartUS, StartUS, GotConnUS, WrittenUS, EndUS int64
	WireWrites                                                  int32
	Error                                                       string `json:",omitempty"`
	Reply                                                       Reply
}

type Runner struct {
	Config
	origin  time.Time
	http    *http.Client
	samples chan Sample
}

func (r *Runner) now() int64 { return time.Since(r.origin).Microseconds() }

func (r *Runner) call(seed Sample, op string) Sample {
	return r.request(seed, op, "", 0)
}

func (r *Runner) request(seed Sample, op, pathOverride string, attempt int) Sample {
	s := seed
	s.Op = op
	s.ID = fmt.Sprintf("%s-%d-%s", s.Cohort, s.Member, op)
	if attempt > 0 {
		s.ID += fmt.Sprintf("-%d", attempt)
	}
	method := "GET"
	path := fmt.Sprintf("/api/user/stores/%d/availability?date=%s&partySize=2", s.Store, r.Date)
	var body []byte
	switch op {
	case "waiting", "retry":
		method, path = "POST", "/api/user/waitings"
		body = []byte(fmt.Sprintf(`{"storeId":%d,"partyCount":2}`, s.Store))
	case "hold":
		method, path = "POST", "/api/user/reservations/holds"
		body = []byte(fmt.Sprintf(`{"storeId":%d,"partySize":2,"reservationStartAt":"%sT12:00:00+09:00"}`, s.Store, r.Date))
	}
	if pathOverride != "" {
		path = pathOverride
	}
	host := fmt.Sprintf("127.0.0.1:%d", 18681+s.App)
	switch r.Network {
	case "published-from-linux":
		host = fmt.Sprintf("host.docker.internal:%d", 18681+s.App)
	case "bridge":
		host = fmt.Sprintf("%s-%d:8080", r.AppPrefix, s.App)
	}
	req, err := http.NewRequest(method, "http://"+host+path, bytes.NewReader(body))
	if err != nil {
		panic(err)
	}
	req.Host = "localhost" // Same valid HTTP authority on published ports and the private bridge.
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Member-Id", strconv.Itoa(s.Member))
	req.Header.Set("X-Baseline-Id", s.ID)
	if s.Cohort == "hot" && (op == "waiting" || op == "retry") {
		req.Header.Set("Idempotency-Key", fmt.Sprintf("intake-%d", s.Member))
		req.GetBody = nil // Measure the first attempt even on a lost connection.
	}
	var conn atomic.Int64
	var first atomic.Int64
	var writes atomic.Int32
	trace := &httptrace.ClientTrace{GotConn: func(httptrace.GotConnInfo) { conn.CompareAndSwap(0, r.now()) }, WroteRequest: func(info httptrace.WroteRequestInfo) {
		if info.Err == nil {
			first.CompareAndSwap(0, r.now())
			writes.Add(1)
		}
	}}
	s.StartUS = r.now()
	res, err := r.http.Do(req.WithContext(httptrace.WithClientTrace(req.Context(), trace)))
	if err == nil {
		s.Status = res.StatusCode
		s.Location = res.Header.Get("Location")
		s.RetryAfter = res.Header.Get("Retry-After")
		var data []byte
		data, err = io.ReadAll(res.Body)
		res.Body.Close()
		s.EndUS = r.now()
		s.Bytes = len(data)
		if err == nil {
			err = json.Unmarshal(data, &s.Reply)
			if err != nil {
				s.Outcome = "bad_json"
			}
		}
	} else {
		s.EndUS = r.now()
	}
	s.GotConnUS = conn.Load()
	s.WrittenUS = first.Load()
	s.WireWrites = writes.Load()
	if err != nil {
		s.Error = err.Error()
		if s.Outcome == "" {
			s.Outcome = "transport"
		}
	} else {
		s.Outcome = "http_" + strconv.Itoa(s.Status)
		if op == "availability" && s.Status == 200 {
			s.Slots = len(s.Reply.Slots)
			s.Available = -1
			for _, slot := range s.Reply.Slots {
				if slot.Start == r.Date+"T12:00:00+09:00" {
					s.Available = slot.Count
					if slot.Available != (slot.Count > 0) {
						s.Outcome = "bad_slot"
					}
				}
			}
			if s.Available < 0 || s.Reply.StoreID != s.Store || s.Reply.Date != r.Date || s.Reply.PartySize != 2 {
				s.Outcome = "bad_availability"
			}
			s.Reply.Slots = nil
		}
	}
	r.samples <- s
	return s
}

func (r *Runner) schedule(cohort string, users int, start, span time.Duration, tasks *sync.WaitGroup) {
	for i := 0; i < users; i++ {
		due := start + time.Duration(int64(span)*int64(i)/int64(users))
		time.Sleep(time.Until(r.origin.Add(due)))
		tasks.Add(1)
		go func(i int, due time.Duration) {
			defer tasks.Done()
			s := Sample{Cohort: cohort, App: i % r.Apps, Store: 1, Member: 1000 + i, DueUS: due.Microseconds(), JourneyStartUS: r.now()}
			flow := r.Flow
			if cohort == "other" {
				s.Store, s.Member = 2, 18000+i
				flow = "availability"
				if i%4 == 0 {
					flow = "waiting"
				}
			}
			if flow == "waiting" {
				first := r.call(s, flow)
				if cohort == "hot" && r.Follow {
					r.follow(s, first)
				}
				return
			}
			read := r.call(s, "availability")
			if flow == "journey" && read.Outcome == "http_200" && read.Available > 0 {
				r.call(s, "hold")
			}
		}(i, due)
	}
}

func (r *Runner) follow(seed, reply Sample) {
	limit := seed.JourneyStartUS + int64(r.FinalLimit)*1000000
	for n := 1; (reply.Status == 0 || reply.Status >= 500) && n <= 3 && r.now() < limit; n++ {
		time.Sleep(time.Duration(250+seed.Member%500) * time.Millisecond)
		seed.App = (seed.App + 1) % r.Apps
		reply = r.request(seed, "retry", "", n)
	}
	if reply.Status != 201 && reply.Status != 202 && reply.Status != 200 {
		return
	}
	id := reply.Reply.ReceiptID
	if len(id) != 36 {
		return
	}
	path := "/api/user/waitings/receipts/" + id
	pause := 2 * time.Second
	for n := 1; r.now() < limit; n++ {
		if reply.Reply.WaitingID > 0 || reply.Reply.Status == "WITHDRAWN" || reply.Reply.Status == "CANCELED" {
			return
		}
		if hint, err := strconv.Atoi(reply.RetryAfter); err == nil && hint > 0 && hint <= 60 {
			pause = time.Duration(hint) * time.Second
		}
		// Per-member and per-poll jitter; never query earlier than the server's delay.
		jitter := time.Duration(uint32(seed.Member*2654435761+n*2246822519)%501) * pause / 1000
		time.Sleep(pause + jitter)
		if r.now() > limit {
			return
		}
		seed.App = (seed.App + 1) % r.Apps
		reply = r.request(seed, "poll", path, n)
		if reply.Status > 0 && reply.Status < 500 && reply.Status != 200 {
			return
		}
	}
}

func main() {
	if len(os.Args) != 3 {
		panic("client config.json new-output-folder")
	}
	var c Config
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	if err = json.Unmarshal(raw, &c); err != nil {
		panic(err)
	}
	if (c.Apps != 1 && c.Apps != 3) || c.Users < 1 || c.Users > 10000 || c.Window < 1 || c.Window > 10 ||
		(c.Flow != "availability" && c.Flow != "journey" && c.Flow != "waiting") {
		panic("invalid lab config")
	}
	if c.Before < 1 || c.After < 1 || c.Background < 1 || c.Background*(c.Before+c.Window+c.After) > 5000 {
		panic("invalid other store fixture range")
	}
	if c.Network != "" && c.Network != "host" && c.Network != "published-from-linux" && c.Network != "bridge" {
		panic("invalid lab network")
	}
	if c.Network == "bridge" && !regexp.MustCompile("^ct-product-[0-9a-f]{8}-rb0_full[0-9]+$").MatchString(c.AppPrefix) {
		panic("invalid local app prefix")
	}
	if c.FinalLimit == 0 {
		c.FinalLimit = 210
	}
	if c.FinalLimit < 1 || c.FinalLimit > 300 {
		panic("invalid observation limit")
	}
	if c.LaunchSpanMS == 0 {
		c.LaunchSpanMS = 1000 * c.Window
	}
	if c.LaunchSpanMS < 1 || c.LaunchSpanMS > 1000*c.Window {
		panic("invalid launch interval")
	}
	transport := &http.Transport{MaxIdleConns: 3000, MaxIdleConnsPerHost: 1000, IdleConnTimeout: 30 * time.Second, DisableCompression: true}
	r := Runner{c, time.Now().Add(time.Second), &http.Client{Transport: transport, Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, make(chan Sample, 24000)}
	f, err := os.OpenFile(os.Args[2]+"/client.jsonl.gz", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
	if err != nil {
		panic(err)
	}
	anchor := []byte(fmt.Sprint(r.origin.UnixMicro(), "\n"))
	if err = os.WriteFile(os.Args[2]+"/origin-us.txt", anchor, 0644); err != nil {
		panic(err)
	}
	var writer, tasks sync.WaitGroup
	writer.Add(1)
	go func() {
		defer writer.Done()
		z, err := gzip.NewWriterLevel(f, gzip.BestSpeed)
		if err != nil {
			panic(err)
		}
		buf := bufio.NewWriterSize(z, 131072)
		e := json.NewEncoder(buf)
		for s := range r.samples {
			if err := e.Encode(s); err != nil {
				panic(err)
			}
		}
		if err := buf.Flush(); err != nil {
			panic(err)
		}
		if err := z.Close(); err != nil {
			panic(err)
		}
	}()
	var hotDone atomic.Int64
	var hot sync.WaitGroup
	done := make(chan struct{})
	go func() {
		r.schedule("hot", c.Users, time.Duration(c.Before)*time.Second, time.Duration(c.LaunchSpanMS)*time.Millisecond, &hot)
		hot.Wait()
		hotDone.Store(r.now())
		close(done)
	}()
	minimum := int64(c.Before+c.Window+c.After) * 1000000
	for i := 0; ; i++ {
		due := time.Duration(int64(time.Second) * int64(i) / int64(c.Background))
		time.Sleep(time.Until(r.origin.Add(due)))
		finished := hotDone.Load()
		if due.Microseconds() >= minimum && finished > 0 && r.now()-finished >= 8000000 {
			break
		}
		if i >= 5000 {
			panic("background fixture exhausted")
		}
		tasks.Add(1)
		go func(i int, due time.Duration) {
			defer tasks.Done()
			s := Sample{Cohort: "other", App: i % c.Apps, Store: 2, Member: 18000 + i, DueUS: due.Microseconds(), JourneyStartUS: r.now()}
			if i%4 == 0 {
				r.call(s, "waiting")
			} else {
				r.call(s, "availability")
			}
		}(i, due)
	}
	<-done
	tasks.Wait()

	close(r.samples)
	writer.Wait()
	if err = f.Close(); err != nil {
		panic(err)
	}
	transport.CloseIdleConnections()
}
