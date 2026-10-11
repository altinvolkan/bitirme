// h264send: Android'e geçmeden önce AirPlay göndericiyi bir Linux makinede,
// kendi Apple TV'nize karşı denemek için küçük bir komut satırı aracı.
//
//	ffmpeg -nostats -loglevel error -re -f lavfi -i testsrc2=size=1280x720:rate=30 \
//	  -c:v libx264 -profile:v baseline -preset ultrafast -tune zerolatency \
//	  -g 30 -bsf:v h264_mp4toannexb -x264-params repeat-headers=1 \
//	  -f h264 - | go run ./cmd/h264send -ip 192.168.1.50
//
// stdin'den Annex-B H.264 okur, access unit'lere böler ve oturuma verir.
// Port ve TXT kayıtları verilmezse varsayılan 7000 ve boş TXT kullanılır
// (bazı cihazlar için `avahi-browse -rt _airplay._tcp` çıktısından -txt ekleyin).
package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"runtime"
	"strings"

	"screenmirror/airplay/sender"
)

type listener struct{ pinCh chan struct{} }

func (l *listener) OnStatus(state, detail string) { log.Printf("[%s] %s", state, detail) }
func (l *listener) OnPinRequired()                { l.pinCh <- struct{}{} }
func (l *listener) OnVideoCanvas(w, h int)        { log.Printf("alıcı tuvali: %dx%d", w, h) }
func (l *listener) OnNeedKeyframe()               { log.Printf("keyframe isteniyor (ffmpeg -g ile periyodik gelir)") }
func (l *listener) OnCredentials(id, js string) {
	if err := os.WriteFile("creds-"+strings.ReplaceAll(id, ":", "")+".json", []byte(js), 0o600); err != nil {
		log.Printf("kimlik kaydedilemedi: %v", err)
	}
}

func main() {
	ip := flag.String("ip", "", "Apple TV IP adresi")
	port := flag.Int("port", 7000, "AirPlay portu")
	txt := flag.String("txt", "", "TXT kayıtları, virgülle ayrılmış anahtar=değer")
	creds := flag.String("creds", "", "kayıtlı kimlik JSON dosyası")
	debug := flag.Bool("debug", false, "ayrintili protokol logu")
	flag.Parse()
	sender.SetDebug(*debug)
	if *ip == "" {
		log.Fatal("-ip gerekli")
	}

	saved := ""
	if *creds != "" {
		b, err := os.ReadFile(*creds)
		if err != nil {
			log.Fatal(err)
		}
		saved = string(b)
	}

	l := &listener{pinCh: make(chan struct{}, 1)}
	s := sender.NewSession("h264send", *ip, *port, strings.ReplaceAll(*txt, ",", "\n"), saved, l)

	go func() {
		<-l.pinCh
		fmt.Fprintln(os.Stderr, "\n>>> TV ekraninda gorunen kodu yazip Enter'a basin:")
		console := openConsole() // stdin video akışı olduğu için PIN'i konsoldan oku
		line, _ := bufio.NewReader(console).ReadString('\n')
		s.SubmitPin(strings.TrimSpace(line))
	}()
	go feed(s)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt)
	go func() { <-sig; s.Stop() }()

	if err := s.Run(); err != nil {
		log.Fatal(err)
	}
}

// feed stdin'deki Annex-B akışını AUD (type 9) sınırlarında access unit'lere böler.
func feed(s *sender.Session) {
	r := bufio.NewReaderSize(os.Stdin, 1<<20)
	var pending []byte
	chunk := make([]byte, 64<<10)
	for {
		n, err := r.Read(chunk)
		pending = append(pending, chunk[:n]...)
		for {
			au, rest, ok := nextAccessUnit(pending)
			if !ok {
				break
			}
			s.PushFrame(au, isKey(au))
			pending = rest
		}
		if err != nil {
			if err != io.EOF {
				log.Printf("okuma: %v", err)
			}
			return
		}
	}
}

// openConsole: stdin ffmpeg çıktısıyla dolu olduğundan klavye için terminali açar.
func openConsole() *os.File {
	name := "/dev/tty"
	if runtime.GOOS == "windows" {
		name = "CONIN$"
	}
	f, err := os.Open(name)
	if err != nil {
		return os.Stdin
	}
	return f
}
