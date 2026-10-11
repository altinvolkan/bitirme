package airplay

// Screen Mirror overlay (bu dosya upstream doubletake'in parçası değildir).
//
// Android'in MediaCodec encoder'ından gelen hazır H.264 access unit'lerini
// GStreamer yakalama hattı yerine MirrorSession.StreamFrames'e besler.

import (
	"io"
	"log"
	"sync"
	"time"
)

const externalQueueDepth = 8

// ExternalVideoSource, dışarıdan itilen Annex-B access unit'lerini
// videoAccessUnitReader olarak sunar. Ağ tıkanırsa kuyruk taşar; bu durumda
// bekleyen kareler atılır ve bir sonraki keyframe'e kadar P-kareleri düşürülür
// (referanssız P-kare göndermek alıcıda bozulmaya yol açar).
type ExternalVideoSource struct {
	ch        chan VideoAccessUnit
	done      chan struct{}
	closeOnce sync.Once

	mu         sync.Mutex
	dropping   bool // keyframe gelene kadar kare at
	startAsked bool
	onNeedKey  func()
	ptsOffset  time.Duration // encode gecikmesi payi; ses ile senkron icin
}

// SetPTSOffset, karenin yakalanmasi ile Push edilmesi arasindaki tahmini encode
// gecikmesini ayarlar. Ses videodan once duyuluyorsa artirin.
func (s *ExternalVideoSource) SetPTSOffset(d time.Duration) {
	s.mu.Lock()
	s.ptsOffset = d
	s.mu.Unlock()
}

// NewExternalVideoSource oluşturur. onNeedKeyframe, akış başlarken ve kuyruk
// taştığında (encoder'dan keyframe istemek için) çağrılır; nil olabilir.
func NewExternalVideoSource(onNeedKeyframe func()) *ExternalVideoSource {
	return &ExternalVideoSource{
		ch:        make(chan VideoAccessUnit, externalQueueDepth),
		done:      make(chan struct{}),
		dropping:  true, // ilk keyframe'e kadar bekle
		onNeedKey: onNeedKeyframe,
		ptsOffset: 30 * time.Millisecond,
	}
}

// Push bir access unit ekler. annexB tam NAL'ları dört byte start code ile
// ayrılmış içermelidir; keyframe ise SPS+PPS+IDR birlikte gelmelidir.
// Veri kopyalanır, çağıran buffer'ı yeniden kullanabilir.
func (s *ExternalVideoSource) Push(annexB []byte, keyframe bool) {
	if len(annexB) == 0 {
		return
	}
	select {
	case <-s.done:
		return
	default:
	}

	s.mu.Lock()
	if s.dropping && !keyframe {
		s.mu.Unlock()
		return
	}
	s.dropping = false
	unit := VideoAccessUnit{AnnexB: append([]byte(nil), annexB...), PTS: time.Now().Add(-s.ptsOffset)}
	select {
	case s.ch <- unit:
		s.mu.Unlock()
		return
	default:
	}

	// Kuyruk dolu: tüm bekleyenleri at, yeni keyframe iste.
	for {
		select {
		case <-s.ch:
			continue
		default:
		}
		break
	}
	s.dropping = true
	cb := s.onNeedKey
	s.mu.Unlock()
	if cb != nil {
		go cb()
	}
}

// ReadVideoAccessUnit videoAccessUnitReader arayüzünü sağlar.
func (s *ExternalVideoSource) ReadVideoAccessUnit() (VideoAccessUnit, error) {
	s.mu.Lock()
	first := !s.startAsked
	s.startAsked = true
	cb := s.onNeedKey
	s.mu.Unlock()
	if first && cb != nil {
		go cb()
	}

	select {
	case unit := <-s.ch:
		return unit, nil
	case <-s.done:
		return VideoAccessUnit{}, io.EOF
	}
}

// Close akışı sonlandırır; bekleyen okuyucular io.EOF alır.
func (s *ExternalVideoSource) Close() {
	s.closeOnce.Do(func() { close(s.done) })
}

// Capture, kaynağı StreamFrames'in beklediği ScreenCapture olarak sarar.
func (s *ExternalVideoSource) Capture() *ScreenCapture {
	return &ScreenCapture{
		frames:     s,
		streamOnly: true,
		cancel:     s.Close,
	}
}

const (
	externalAudioRate     = 44100
	externalAudioMaxBytes = externalAudioRate * 4 / 2 // 500 ms; tasarsa en eskiyi at
)

// ExternalAudioSource, Android AudioPlaybackCapture'dan gelen PCM'i (S16LE, 44.1 kHz,
// stereo) AirPlay ses akisina besler. PTS, ornek sayaci ile duvar saati arasinda
// yavasca duzeltilen bir capa uzerinden hesaplanir.
type ExternalAudioSource struct {
	mu        sync.Mutex
	cond      *sync.Cond
	buf       []byte
	pushed    uint64 // atilanlar dahil bugune dek itilen ornek sayisi
	anchor    time.Time
	firstPush time.Time
	have      bool
	closed    bool
	reanchors int
	overflows int
}

const (
	// Ilk saniyelerde en erken (jitter'siz) capa secilir, sonra sabit kalir; boylece
	// alicinin gordugu RTP<->zaman eslemesi dalgalanmaz.
	audioAnchorCalibration = 2 * time.Second
	// Kaynak durup devam ederse (veya cok yavas saat kaymasi birikirse) capa bir kez atlar.
	audioReanchorThreshold = 150 * time.Millisecond
)

func NewExternalAudioSource() *ExternalAudioSource {
	a := &ExternalAudioSource{}
	a.cond = sync.NewCond(&a.mu)
	return a
}

func samplesToDuration(n uint64) time.Duration {
	return time.Duration(n) * time.Second / externalAudioRate
}

// Push bir PCM parcasi ekler (4 byte'in kati degilse kuyruk atilir).
func (a *ExternalAudioSource) Push(pcm []byte) {
	n := len(pcm) / 4 * 4
	if n == 0 {
		return
	}
	now := time.Now()
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.closed {
		return
	}
	a.buf = append(a.buf, pcm[:n]...)
	a.pushed += uint64(n / 4)

	candidate := now.Add(-samplesToDuration(a.pushed))
	switch {
	case !a.have:
		a.anchor, a.have, a.firstPush = candidate, true, now
	case candidate.Before(a.anchor):
		a.anchor = candidate // daha erken = gercege daha yakin
	case now.Sub(a.firstPush) > audioAnchorCalibration && candidate.Sub(a.anchor) > audioReanchorThreshold:
		a.reanchors++
		log.Printf("[AUDIO-EXT] kaynak %v gecikti, zaman capasi yenilendi (#%d)",
			candidate.Sub(a.anchor).Round(time.Millisecond), a.reanchors)
		a.anchor = candidate
	}

	if len(a.buf) > externalAudioMaxBytes {
		drop := (len(a.buf) - externalAudioMaxBytes) / 4 * 4
		a.buf = append(a.buf[:0], a.buf[drop:]...)
		a.overflows++
		if a.overflows <= 5 || a.overflows%100 == 0 {
			log.Printf("[AUDIO-EXT] tampon tasti, %d byte atildi (#%d); tuketici yetismiyor", drop, a.overflows)
		}
	}
	a.cond.Broadcast()
}

// ReadPCMFrame audioPCMFrameReader arayuzunu saglar: dst'yi doldurana dek bekler.
func (a *ExternalAudioSource) ReadPCMFrame(dst []byte) (time.Time, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for len(a.buf) < len(dst) && !a.closed {
		a.cond.Wait()
	}
	if len(a.buf) < len(dst) {
		return time.Time{}, io.EOF
	}
	start := a.pushed - uint64(len(a.buf)/4)
	copy(dst, a.buf[:len(dst)])
	a.buf = append(a.buf[:0], a.buf[len(dst):]...)
	return a.anchor.Add(samplesToDuration(start)), nil
}

// Close bekleyen okuyucuyu io.EOF ile serbest birakir.
func (a *ExternalAudioSource) Close() {
	a.mu.Lock()
	a.closed = true
	a.mu.Unlock()
	a.cond.Broadcast()
}

// Capture, kaynagi StreamAudio'nun bekledigi AudioCapture olarak sarar.
// codec, oturumda anlasilan kodek olmalidir (session.AudioCodec()).
func (a *ExternalAudioSource) Capture(codec AudioCodec) *AudioCapture {
	waitCh := make(chan struct{})
	var once sync.Once
	return &AudioCapture{
		pcmFrames: a,
		codec:     codec,
		waitCh:    waitCh,
		cancel: func() {
			once.Do(func() {
				close(waitCh)
				a.Close()
			})
		},
	}
}
