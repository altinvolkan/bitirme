package airplay

// Screen Mirror overlay (bu dosya upstream doubletake'in parçası değildir).
//
// Android'in MediaCodec encoder'ından gelen hazır H.264 access unit'lerini
// GStreamer yakalama hattı yerine MirrorSession.StreamFrames'e besler.

import (
	"io"
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
}

// NewExternalVideoSource oluşturur. onNeedKeyframe, akış başlarken ve kuyruk
// taştığında (encoder'dan keyframe istemek için) çağrılır; nil olabilir.
func NewExternalVideoSource(onNeedKeyframe func()) *ExternalVideoSource {
	return &ExternalVideoSource{
		ch:        make(chan VideoAccessUnit, externalQueueDepth),
		done:      make(chan struct{}),
		dropping:  true, // ilk keyframe'e kadar bekle
		onNeedKey: onNeedKeyframe,
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
	unit := VideoAccessUnit{AnnexB: append([]byte(nil), annexB...), PTS: time.Now()}
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
